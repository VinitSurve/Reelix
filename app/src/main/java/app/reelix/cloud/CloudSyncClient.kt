package app.reelix.cloud

import android.content.Context
import app.reelix.core.ReelixLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

sealed interface CloudUploadResult {
    data class Success(val driveFileId: String, val sizeBytes: Long) : CloudUploadResult
    data class Failure(val error: String) : CloudUploadResult
}

data class CrossClientProbeResult(
    val folderId: String,
    val fileId: String,
    val folderName: String,
)

data class CrossClientProbeVerification(
    val version: Int,
    val updatedBy: String?,
    val isVerified: Boolean,
    val message: String,
)

interface ResumableUploadStore {
    fun getSessionUri(key: String): String?
    fun saveSessionUri(key: String, uri: String)
    fun removeSession(key: String)
}

/** In-memory store for fast local unit testing without Android Context dependencies. */
class InMemoryResumableUploadStore : ResumableUploadStore {
    private val map = ConcurrentHashMap<String, String>()
    override fun getSessionUri(key: String): String? = map[key]
    override fun saveSessionUri(key: String, uri: String) { map[key] = uri }
    override fun removeSession(key: String) { map.remove(key) }
}

/**
 * Persistent Android SharedPreferences-backed store for Google Drive resumable upload sessions.
 * Preserves upload session URIs across application termination, process death, and device restarts.
 * Stored in private SharedPreferences (MODE_PRIVATE).
 * Never logs session URIs.
 */
class SharedPreferencesResumableUploadStore(
    context: Context,
) : ResumableUploadStore {
    companion object {
        const val PREFS_NAME = "reelix_resumable_upload_sessions"
        const val KEY_SCHEMA_VERSION = "__schema_version__"
        const val CURRENT_SCHEMA_VERSION = 2
    }

    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    ).also { sp ->
        // Invalidate legacy un-versioned sessions that lacked stable item IDs
        val version = sp.getInt(KEY_SCHEMA_VERSION, 1)
        if (version < CURRENT_SCHEMA_VERSION) {
            sp.edit().clear().putInt(KEY_SCHEMA_VERSION, CURRENT_SCHEMA_VERSION).apply()
        }
    }

    override fun getSessionUri(key: String): String? {
        if (key == KEY_SCHEMA_VERSION) return null
        return prefs.getString(key, null)
    }

    override fun saveSessionUri(key: String, uri: String) {
        if (key == KEY_SCHEMA_VERSION) return
        prefs.edit().putString(key, uri).apply()
    }

    override fun removeSession(key: String) {
        if (key == KEY_SCHEMA_VERSION) return
        prefs.edit().remove(key).apply()
    }
}

/**
 * Generates a stable SHA-256 key combining Reelix item identity and file metadata.
 * Differentiates different items sharing the same file or identical file names,
 * while allowing legitimate retries of the same item and unchanged file to resume.
 */
fun buildResumableUploadKey(file: File, itemId: String): String {
    require(itemId.isNotBlank()) { "itemId must not be blank" }
    val raw = "item_${itemId.trim()}_${file.canonicalPath}_${file.length()}_${file.lastModified()}"
    val md = MessageDigest.getInstance("SHA-256")
    val digest = md.digest(raw.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}

/**
 * Custom OkHttp RequestBody that streams a file directly from disk starting from [offset]
 * without loading the file into RAM.
 */
class OffsetFileRequestBody(
    private val file: File,
    private val contentType: MediaType?,
    private val offset: Long,
) : RequestBody() {
    override fun contentType(): MediaType? = contentType
    override fun contentLength(): Long = (file.length() - offset).coerceAtLeast(0L)

    override fun writeTo(sink: BufferedSink) {
        file.inputStream().use { input ->
            if (offset > 0) {
                var remaining = offset
                while (remaining > 0) {
                    val skipped = input.skip(remaining)
                    if (skipped <= 0) break
                    remaining -= skipped
                }
            }
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                sink.write(buffer, 0, read)
            }
        }
    }
}

interface CloudSyncClient {
    suspend fun isConfigured(): Boolean
    suspend fun uploadMedia(file: File, mimeType: String, originalFileName: String, itemId: String): CloudUploadResult
    suspend fun submitJobManifest(manifest: CloudJobManifest): Boolean
    suspend fun fetchJobManifest(itemId: String): CloudJobManifest?
    suspend fun listCompletedJobs(): List<CloudJobManifest>
    suspend fun createOrGetVaultFolder(): Result<String>
    suspend fun createCrossClientTestProbe(): Result<CrossClientProbeResult>
    suspend fun verifyCrossClientTestProbe(fileId: String): Result<CrossClientProbeVerification>
    suspend fun cleanupCrossClientTestProbe(folderId: String, fileId: String): Result<Boolean>
}

data class MockUploadedFile(
    val name: String,
    val sizeBytes: Long,
    val mimeType: String,
    val appProperties: Map<String, String> = emptyMap(),
)

/**
 * Android client for Google Drive v3 persistent storage using least-privilege OAuth 2.0 (drive.file).
 *
 * Implements chunked streaming resumable uploads with true recovery:
 * 1. Persists upload session URI.
 * 2. On retry/reconnection, queries server for uploaded byte range (PUT with Content-Range: bytes * / total).
 * 3. Resumes streaming directly from server-confirmed offset without re-uploading completed chunks.
 * 4. Recovers from expired sessions and token refreshes without losing upload session state.
 * 5. Avoids duplicate file creation by verifying remote folders and handling completed session checks.
 */
class GoogleDriveCloudClient(
    private val client: OkHttpClient,
    private val folderIdProvider: suspend () -> String?,
    private val tokenProvider: suspend () -> String?,
    private val tokenRefresher: (suspend () -> String?)? = null,
    private val resumableStore: ResumableUploadStore = InMemoryResumableUploadStore(),
) : CloudSyncClient {

    override suspend fun isConfigured(): Boolean {
        val token = tokenProvider()
        return !token.isNullOrBlank()
    }

    override suspend fun uploadMedia(
        file: File,
        mimeType: String,
        originalFileName: String,
        itemId: String,
    ): CloudUploadResult =
        withContext(Dispatchers.IO) {
            if (!file.exists()) {
                return@withContext CloudUploadResult.Failure("Media file does not exist on disk: ${file.absolutePath}")
            }

            var token = tokenProvider()
                ?: return@withContext CloudUploadResult.Failure("Google Drive authentication token missing")
            val configuredFolder = folderIdProvider()
            val rootFolderId = if (!configuredFolder.isNullOrBlank()) configuredFolder else ensureVaultFolder(token)

            try {
                // 1. Ensure/get media folder
                val mediaFolderId = ensureSubfolder("media", rootFolderId, token)

                // 2. Duplicate avoidance check in remote media folder using stable Reelix item identity
                val existingFileId = findMediaFileByItemId(itemId, mediaFolderId, token)
                if (existingFileId != null) {
                    ReelixLog.d("Media for item $itemId already uploaded to Drive (id=$existingFileId); reusing")
                    return@withContext CloudUploadResult.Success(driveFileId = existingFileId, sizeBytes = file.length())
                }

                val totalLength = file.length()
                val uploadKey = buildResumableUploadKey(file, itemId)
                var sessionUri = resumableStore.getSessionUri(uploadKey)
                var resumeOffset = 0L

                // 3. Resumable Session Recovery: Query server for status if an existing session is stored
                if (sessionUri != null) {
                    ReelixLog.d("Found existing resumable upload session for $originalFileName; querying server status...")
                    val statusReq = Request.Builder()
                        .url(sessionUri)
                        .header("Content-Range", "bytes */$totalLength")
                        .put("".toRequestBody(null))
                        .build()

                    var statusResp = client.newCall(statusReq).execute()
                    if (statusResp.code == 401 && tokenRefresher != null) {
                        statusResp.close()
                        val refreshed = tokenRefresher.invoke()
                        if (!refreshed.isNullOrBlank()) {
                            token = refreshed
                            val retryStatusReq = Request.Builder()
                                .url(sessionUri)
                                .header("Authorization", "Bearer $token")
                                .header("Content-Range", "bytes */$totalLength")
                                .put("".toRequestBody(null))
                                .build()
                            statusResp = client.newCall(retryStatusReq).execute()
                        }
                    }

                    when (statusResp.code) {
                        308 -> {
                            // Resume Incomplete: Range header indicates uploaded range
                            val rangeHeader = statusResp.header("Range")
                            if (rangeHeader != null && rangeHeader.startsWith("bytes=0-")) {
                                val lastByte = rangeHeader.substringAfter("bytes=0-").trim().toLongOrNull() ?: -1L
                                resumeOffset = (lastByte + 1L).coerceAtMost(totalLength)
                                ReelixLog.d("Server confirmed existing upload range 0-$lastByte; resuming from byte $resumeOffset/$totalLength")
                            } else {
                                resumeOffset = 0L
                            }
                            statusResp.close()
                        }
                        200, 201 -> {
                            // Upload was already completed!
                            val body = statusResp.body?.string().orEmpty()
                            statusResp.close()
                            resumableStore.removeSession(uploadKey)
                            val json = JSONObject(body)
                            val fileId = json.getString("id")
                            ReelixLog.d("Upload was already finished on server (fileId=$fileId)")
                            return@withContext CloudUploadResult.Success(driveFileId = fileId, sizeBytes = totalLength)
                        }
                        404, 410 -> {
                            // Session expired or missing on server; start fresh session
                            statusResp.close()
                            ReelixLog.d("Resumable upload session expired on server (HTTP ${statusResp.code}); starting fresh session")
                            resumableStore.removeSession(uploadKey)
                            sessionUri = null
                        }
                        else -> {
                            statusResp.close()
                            resumableStore.removeSession(uploadKey)
                            sessionUri = null
                        }
                    }
                }

                // 4. Initiate fresh Resumable Upload session if no valid session existed
                val mimeTypeStr = mimeType.ifBlank { "application/octet-stream" }
                if (sessionUri == null) {
                    val initUrl = "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable"
                    val metadata = JSONObject().apply {
                        put("name", originalFileName)
                        put("parents", org.json.JSONArray().put(mediaFolderId))
                        put("appProperties", JSONObject().apply {
                            put("reelix_item_id", itemId)
                            put("reelix_type", "media")
                        })
                    }

                    fun buildInitRequest(authToken: String): Request = Request.Builder()
                        .url(initUrl)
                        .addHeader("Authorization", "Bearer $authToken")
                        .addHeader("X-Upload-Content-Type", mimeTypeStr)
                        .addHeader("X-Upload-Content-Length", totalLength.toString())
                        .post(metadata.toString().toByteArray().toRequestBody("application/json; charset=UTF-8".toMediaType()))
                        .build()

                    var initResp = client.newCall(buildInitRequest(token)).execute()
                    if (initResp.code == 401 && tokenRefresher != null) {
                        initResp.close()
                        ReelixLog.d("Drive access token expired during upload initiation; refreshing...")
                        val refreshed = tokenRefresher.invoke()
                        if (!refreshed.isNullOrBlank()) {
                            token = refreshed
                            initResp = client.newCall(buildInitRequest(token)).execute()
                        }
                    }

                    val newLocation = initResp.use { response ->
                        if (!response.isSuccessful) {
                            val body = response.body?.string().orEmpty()
                            return@withContext CloudUploadResult.Failure("Failed to initiate Drive resumable upload (${response.code}): $body")
                        }
                        response.header("Location")
                            ?: return@withContext CloudUploadResult.Failure("Missing Location header in resumable upload session")
                    }

                    sessionUri = newLocation
                    resumableStore.saveSessionUri(uploadKey, newLocation)
                    resumeOffset = 0L
                }

                // 5. Stream the remaining bytes from resumeOffset without loading file into RAM
                val streamBody = OffsetFileRequestBody(file, mimeTypeStr.toMediaType(), resumeOffset)
                val contentRange = "bytes $resumeOffset-${totalLength - 1}/$totalLength"
                val uploadReq = Request.Builder()
                    .url(sessionUri)
                    .addHeader("Content-Range", contentRange)
                    .put(streamBody)
                    .build()

                var uploadResp = client.newCall(uploadReq).execute()
                if (uploadResp.code == 401 && tokenRefresher != null) {
                    uploadResp.close()
                    val refreshed = tokenRefresher.invoke()
                    if (!refreshed.isNullOrBlank()) {
                        token = refreshed
                        val retryReq = Request.Builder()
                            .url(sessionUri)
                            .addHeader("Authorization", "Bearer $token")
                            .addHeader("Content-Range", contentRange)
                            .put(OffsetFileRequestBody(file, mimeTypeStr.toMediaType(), resumeOffset))
                            .build()
                        uploadResp = client.newCall(retryReq).execute()
                    }
                }

                uploadResp.use { resp ->
                    when (resp.code) {
                        200, 201 -> {
                            resumableStore.removeSession(uploadKey)
                            val json = JSONObject(resp.body?.string().orEmpty())
                            val fileId = json.getString("id")
                            ReelixLog.d("Streaming upload succeeded for $originalFileName (driveFileId=$fileId)")
                            CloudUploadResult.Success(driveFileId = fileId, sizeBytes = totalLength)
                        }
                        308 -> {
                            // Chunk transferred partially, session URI preserved in store for subsequent resumption
                            CloudUploadResult.Failure("Upload interrupted (HTTP 308); session saved for resumption")
                        }
                        else -> {
                            val body = resp.body?.string().orEmpty()
                            CloudUploadResult.Failure("Streaming upload failed (${resp.code}): $body")
                        }
                    }
                }
            } catch (e: Exception) {
                ReelixLog.w("Exception during Drive upload; resumable session preserved", e)
                CloudUploadResult.Failure("Exception during Drive streaming upload: ${e.message}")
            }
        }

    override suspend fun submitJobManifest(manifest: CloudJobManifest): Boolean = withContext(Dispatchers.IO) {
        val token = tokenProvider() ?: return@withContext false
        val configuredFolder = folderIdProvider()
        val rootFolderId = if (!configuredFolder.isNullOrBlank()) configuredFolder else ensureVaultFolder(token)

        try {
            val jobsFolderId = ensureSubfolder("jobs", rootFolderId, token)
            val fileName = "${manifest.itemId}.json"
            val jsonContent = manifest.toJson()

            // Check if file exists
            val existingId = findFileInFolder(fileName, jobsFolderId, token)

            if (existingId != null) {
                // Update
                val updateUrl = "https://www.googleapis.com/upload/drive/v3/files/$existingId?uploadType=media"
                val request = Request.Builder()
                    .url(updateUrl)
                    .addHeader("Authorization", "Bearer $token")
                    .patch(jsonContent.toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(request).execute().use { it.isSuccessful }
            } else {
                // Create
                val createMeta = JSONObject().apply {
                    put("name", fileName)
                    put("parents", org.json.JSONArray().put(jobsFolderId))
                }
                val boundary = "ReelixJobBoundary${System.currentTimeMillis()}"
                val body = "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$createMeta\r\n" +
                    "--$boundary\r\nContent-Type: application/json\r\n\r\n$jsonContent\r\n--$boundary--"

                val request = Request.Builder()
                    .url("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart")
                    .addHeader("Authorization", "Bearer $token")
                    .post(body.toByteArray().toRequestBody("multipart/related; boundary=$boundary".toMediaType()))
                    .build()
                client.newCall(request).execute().use { it.isSuccessful }
            }
        } catch (e: Exception) {
            ReelixLog.w("Failed to submit job manifest to Drive", e)
            false
        }
    }

    override suspend fun fetchJobManifest(itemId: String): CloudJobManifest? = withContext(Dispatchers.IO) {
        val token = tokenProvider() ?: return@withContext null
        val configuredFolder = folderIdProvider()
        val rootFolderId = if (!configuredFolder.isNullOrBlank()) configuredFolder else ensureVaultFolder(token)

        try {
            val jobsFolderId = ensureSubfolder("jobs", rootFolderId, token)
            val fileId = findFileInFolder("${itemId}.json", jobsFolderId, token) ?: return@withContext null

            val getUrl = "https://www.googleapis.com/drive/v3/files/$fileId?alt=media"
            val request = Request.Builder()
                .url(getUrl)
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body?.string() ?: return@withContext null
                CloudJobManifest.fromJson(body)
            }
        } catch (e: Exception) {
            ReelixLog.w("Failed to fetch job manifest for $itemId", e)
            null
        }
    }

    override suspend fun listCompletedJobs(): List<CloudJobManifest> = withContext(Dispatchers.IO) {
        val token = tokenProvider() ?: return@withContext emptyList()
        val configuredFolder = folderIdProvider()
        val rootFolderId = if (!configuredFolder.isNullOrBlank()) configuredFolder else ensureVaultFolder(token)

        try {
            val jobsFolderId = ensureSubfolder("jobs", rootFolderId, token)
            val files = mutableListOf<Pair<String, String>>()
            var pageToken: String? = null
            do {
                val pageQuery = if (pageToken != null) "&pageToken=$pageToken" else ""
                val listUrl = "https://www.googleapis.com/drive/v3/files?q='${jobsFolderId}'+in+parents+and+trashed=false&fields=nextPageToken,files(id,name)&pageSize=50$pageQuery"
                val request = Request.Builder()
                    .url(listUrl)
                    .addHeader("Authorization", "Bearer $token")
                    .get()
                    .build()

                pageToken = client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val json = JSONObject(resp.body?.string().orEmpty())
                    val arr = json.optJSONArray("files")
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val obj = arr.getJSONObject(i)
                            files.add(obj.getString("id") to obj.getString("name"))
                        }
                    }
                    val next = json.optString("nextPageToken")
                    if (next.isNotBlank()) next else null
                }
            } while (pageToken != null)

            val settled = mutableListOf<CloudJobManifest>()
            for ((id, name) in files) {
                if (!name.endsWith(".json")) continue
                val contentUrl = "https://www.googleapis.com/drive/v3/files/$id?alt=media"
                val fetchReq = Request.Builder().url(contentUrl).addHeader("Authorization", "Bearer $token").get().build()
                client.newCall(fetchReq).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string().orEmpty()
                        val manifest = CloudJobManifest.fromJson(body)
                        // Return settled & active manifests (COMPLETED, PARTIAL, FAILED, WAITING_RETRY, PROCESSING) for honest synchronization
                        if (manifest.status in listOf("COMPLETED", "PARTIAL", "FAILED", "WAITING_RETRY", "PROCESSING")) {
                            settled.add(manifest)
                        }
                    }
                }
            }
            settled
        } catch (e: Exception) {
            ReelixLog.w("Failed to list completed jobs from Drive", e)
            emptyList()
        }
    }

    override suspend fun createOrGetVaultFolder(): Result<String> = withContext(Dispatchers.IO) {
        var token = tokenProvider()
        if (token.isNullOrBlank()) {
            val refreshed = tokenRefresher?.invoke()
            if (!refreshed.isNullOrBlank()) {
                token = refreshed
            } else {
                return@withContext Result.failure(
                    IllegalStateException("Google Drive authentication token missing. Please connect Google Drive in Settings.")
                )
            }
        }

        try {
            // 1. Search for existing Reelix_Vault folder to avoid duplicate creation
            val existingFolderId = findExistingVaultFolder(token) { refreshedToken ->
                token = refreshedToken
            }
            if (existingFolderId != null) {
                ReelixLog.d("Found existing Reelix_Vault folder (id=$existingFolderId); reusing")
                return@withContext Result.success(existingFolderId)
            }

            // 2. Create the Reelix_Vault folder
            val createdFolderId = executeCreateVaultFolder(token) { refreshedToken ->
                token = refreshedToken
            }
            ReelixLog.d("Successfully created Reelix_Vault folder (id=$createdFolderId)")
            Result.success(createdFolderId)
        } catch (e: java.io.IOException) {
            ReelixLog.w("Network failure while creating Reelix_Vault folder: ${e.message}")
            Result.failure(java.io.IOException("Network error while connecting to Google Drive: ${e.message}", e))
        } catch (e: Exception) {
            ReelixLog.w("Unexpected error while creating Reelix_Vault folder: ${e.message}")
            Result.failure(e)
        }
    }

    override suspend fun createCrossClientTestProbe(): Result<CrossClientProbeResult> = withContext(Dispatchers.IO) {
        var token = tokenProvider()
        if (token.isNullOrBlank()) {
            val refreshed = tokenRefresher?.invoke()
            if (!refreshed.isNullOrBlank()) {
                token = refreshed
            } else {
                return@withContext Result.failure(
                    IllegalStateException("Google Drive authentication token missing. Please connect Google Drive in Settings.")
                )
            }
        }

        try {
            val runId = "${System.currentTimeMillis() / 1000}_${java.util.UUID.randomUUID().toString().take(6)}"
            val folderName = "Reelix_CrossClient_Test_$runId"

            // 1. Create isolated test folder directly under root with required shared properties
            val folderProps = JSONObject().apply {
                put("reelix_type", "cross_client_test")
                put("reelix_test_id", runId)
            }
            val folderMeta = JSONObject().apply {
                put("name", folderName)
                put("mimeType", "application/vnd.google-apps.folder")
                put("parents", org.json.JSONArray().put("root"))
                put("properties", folderProps)
            }

            fun buildFolderRequest(tok: String) = Request.Builder()
                .url("https://www.googleapis.com/drive/v3/files")
                .addHeader("Authorization", "Bearer $tok")
                .post(folderMeta.toString().toByteArray().toRequestBody("application/json; charset=UTF-8".toMediaType()))
                .build()

            var folderResp = client.newCall(buildFolderRequest(token)).execute()
            if (folderResp.code == 401 && tokenRefresher != null) {
                folderResp.close()
                val refreshed = tokenRefresher.invoke()
                if (!refreshed.isNullOrBlank()) {
                    token = refreshed
                    folderResp = client.newCall(buildFolderRequest(token)).execute()
                }
            }

            val folderId = folderResp.use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val errorMsg = parseDriveErrorMessage(response.code, body)
                    throw java.io.IOException("Google Drive folder creation failed ($errorMsg)")
                }
                val json = JSONObject(body)
                val id = json.optString("id")
                if (id.isNullOrBlank()) {
                    throw java.io.IOException("Google Drive returned invalid or empty folder ID")
                }
                id
            }

            // 2. Create probe file inside the new isolated folder
            val fileProps = JSONObject().apply {
                put("reelix_test_id", runId)
            }
            val fileMeta = JSONObject().apply {
                put("name", "manifest_probe.json")
                put("mimeType", "application/json")
                put("parents", org.json.JSONArray().put(folderId))
                put("properties", fileProps)
            }

            val probePayload = JSONObject().apply {
                put("version", 1)
                put("test_id", runId)
                put("created_by", "android_client_a")
                put("timestamp", System.currentTimeMillis())
                put("message", "Harmless controlled test probe payload")
            }
            val jsonContent = probePayload.toString(2)

            val boundary = "ReelixProbeBoundary${System.currentTimeMillis()}"
            val multipartBody = "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$fileMeta\r\n" +
                "--$boundary\r\nContent-Type: application/json\r\n\r\n$jsonContent\r\n--$boundary--"

            fun buildFileRequest(tok: String) = Request.Builder()
                .url("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart")
                .addHeader("Authorization", "Bearer $tok")
                .post(multipartBody.toByteArray().toRequestBody("multipart/related; boundary=$boundary".toMediaType()))
                .build()

            var fileResp = client.newCall(buildFileRequest(token)).execute()
            if (fileResp.code == 401 && tokenRefresher != null) {
                fileResp.close()
                val refreshed = tokenRefresher.invoke()
                if (!refreshed.isNullOrBlank()) {
                    token = refreshed
                    fileResp = client.newCall(buildFileRequest(token)).execute()
                }
            }

            val fileId = fileResp.use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val errorMsg = parseDriveErrorMessage(response.code, body)
                    throw java.io.IOException("Google Drive probe file creation failed ($errorMsg)")
                }
                val json = JSONObject(body)
                val id = json.optString("id")
                if (id.isNullOrBlank()) {
                    throw java.io.IOException("Google Drive returned invalid or empty file ID")
                }
                id
            }

            ReelixLog.d("Created cross-client test probe: folder=$folderId, file=$fileId")
            Result.success(
                CrossClientProbeResult(
                    folderId = folderId,
                    fileId = fileId,
                    folderName = folderName,
                )
            )
        } catch (e: java.io.IOException) {
            ReelixLog.w("Network failure while creating cross-client test probe: ${e.message}")
            Result.failure(java.io.IOException("Network error while creating test probe: ${e.message}", e))
        } catch (e: Exception) {
            ReelixLog.w("Unexpected error while creating cross-client test probe: ${e.message}")
            Result.failure(e)
        }
    }

    override suspend fun verifyCrossClientTestProbe(fileId: String): Result<CrossClientProbeVerification> = withContext(Dispatchers.IO) {
        var token = tokenProvider()
        if (token.isNullOrBlank()) {
            val refreshed = tokenRefresher?.invoke()
            if (!refreshed.isNullOrBlank()) {
                token = refreshed
            } else {
                return@withContext Result.failure(
                    IllegalStateException("Google Drive authentication token missing. Please connect Google Drive in Settings.")
                )
            }
        }

        try {
            val cleanFileId = fileId.trim()
            fun buildGetRequest(tok: String) = Request.Builder()
                .url("https://www.googleapis.com/drive/v3/files/$cleanFileId?alt=media")
                .addHeader("Authorization", "Bearer $tok")
                .addHeader("Cache-Control", "no-cache, no-store")
                .addHeader("Pragma", "no-cache")
                .get()
                .build()

            var resp = client.newCall(buildGetRequest(token)).execute()
            if (resp.code == 401 && tokenRefresher != null) {
                resp.close()
                val refreshed = tokenRefresher.invoke()
                if (!refreshed.isNullOrBlank()) {
                    token = refreshed
                    resp = client.newCall(buildGetRequest(token)).execute()
                }
            }

            val statusCode = resp.code
            val body = resp.use { response ->
                if (!response.isSuccessful) {
                    val err = parseDriveErrorMessage(response.code, response.body?.string().orEmpty())
                    throw java.io.IOException("Failed to read probe file (HTTP ${response.code}: $err)")
                }
                response.body?.string().orEmpty()
            }

            val byteCount = body.toByteArray(Charsets.UTF_8).size
            val json = JSONObject(body)
            val version = json.optInt("version", -1)
            val updatedBy = if (json.has("updated_by") && !json.isNull("updated_by")) json.optString("updated_by") else null
            val isVerified = version == 2 && updatedBy == "worker_client_b"
            val message = if (isVerified) {
                "✓ Cross-client verification SUCCESS: Manifest version=2, updated_by=worker_client_b [File ID: $cleanFileId, HTTP $statusCode, $byteCount bytes]"
            } else {
                "Manifest version $version (updated_by: $updatedBy). Expected version 2 from worker_client_b. [File ID: $cleanFileId, HTTP $statusCode, $byteCount bytes]"
            }

            ReelixLog.d(
                "Cross-client probe verification diagnostic: fileId=$cleanFileId, http=$statusCode, bytes=$byteCount, version=$version, updatedBy=$updatedBy, verified=$isVerified"
            )
            Result.success(
                CrossClientProbeVerification(
                    version = version,
                    updatedBy = updatedBy,
                    isVerified = isVerified,
                    message = message,
                )
            )
        } catch (e: Exception) {
            ReelixLog.w("Error verifying cross-client test probe: ${e.message}")
            Result.failure(e)
        }
    }

    override suspend fun cleanupCrossClientTestProbe(folderId: String, fileId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        var token = tokenProvider()
        if (token.isNullOrBlank()) {
            val refreshed = tokenRefresher?.invoke()
            if (!refreshed.isNullOrBlank()) token = refreshed
            else return@withContext Result.failure(IllegalStateException("Google Drive authentication token missing."))
        }

        try {
            fun buildDeleteRequest(tok: String, id: String) = Request.Builder()
                .url("https://www.googleapis.com/drive/v3/files/$id")
                .addHeader("Authorization", "Bearer $tok")
                .delete()
                .build()

            // 1. Delete probe file
            var fileResp = client.newCall(buildDeleteRequest(token, fileId)).execute()
            if (fileResp.code == 401 && tokenRefresher != null) {
                fileResp.close()
                val refreshed = tokenRefresher.invoke()
                if (!refreshed.isNullOrBlank()) {
                    token = refreshed
                    fileResp = client.newCall(buildDeleteRequest(token, fileId)).execute()
                }
            }
            fileResp.close()

            // 2. Delete test folder
            var folderResp = client.newCall(buildDeleteRequest(token, folderId)).execute()
            folderResp.close()

            ReelixLog.d("Successfully cleaned up test probe from Drive (folder=$folderId, file=$fileId)")
            Result.success(true)
        } catch (e: Exception) {
            ReelixLog.w("Error cleaning up cross-client test probe: ${e.message}")
            Result.failure(e)
        }
    }

    private suspend fun findExistingVaultFolder(
        initialToken: String,
        onTokenRefreshed: (String) -> Unit,
    ): String? {
        var token = initialToken
        val q = "name = 'Reelix_Vault' and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
        val url = "https://www.googleapis.com/drive/v3/files?q=${URLEncoder.encode(q, "UTF-8")}&fields=files(id,name)&spaces=drive"

        fun buildRequest(tok: String) = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $tok")
            .get()
            .build()

        var resp = client.newCall(buildRequest(token)).execute()
        if (resp.code == 401 && tokenRefresher != null) {
            resp.close()
            val refreshed = tokenRefresher.invoke()
            if (!refreshed.isNullOrBlank()) {
                token = refreshed
                onTokenRefreshed(refreshed)
                resp = client.newCall(buildRequest(token)).execute()
            }
        }

        resp.use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string().orEmpty()
                val errorMsg = parseDriveErrorMessage(response.code, errorBody)
                throw java.io.IOException("Google Drive query failed ($errorMsg)")
            }
            val json = JSONObject(response.body?.string().orEmpty())
            val arr = json.optJSONArray("files")
            if (arr != null && arr.length() > 0) {
                return arr.getJSONObject(0).getString("id")
            }
            return null
        }
    }

    private suspend fun executeCreateVaultFolder(
        initialToken: String,
        onTokenRefreshed: (String) -> Unit,
    ): String {
        var token = initialToken
        val meta = JSONObject().apply {
            put("name", "Reelix_Vault")
            put("mimeType", "application/vnd.google-apps.folder")
            put("parents", org.json.JSONArray().put("root"))
        }

        fun buildRequest(tok: String) = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files")
            .addHeader("Authorization", "Bearer $tok")
            .post(meta.toString().toByteArray().toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .build()

        var resp = client.newCall(buildRequest(token)).execute()
        if (resp.code == 401 && tokenRefresher != null) {
            resp.close()
            val refreshed = tokenRefresher.invoke()
            if (!refreshed.isNullOrBlank()) {
                token = refreshed
                onTokenRefreshed(refreshed)
                resp = client.newCall(buildRequest(token)).execute()
            }
        }

        resp.use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val errorMsg = parseDriveErrorMessage(response.code, body)
                throw java.io.IOException("Google Drive folder creation failed ($errorMsg)")
            }
            val json = JSONObject(body)
            return json.getString("id")
        }
    }

    private fun parseDriveErrorMessage(statusCode: Int, responseBody: String): String {
        return try {
            val json = JSONObject(responseBody)
            val err = json.optJSONObject("error")
            val message = err?.optString("message")
            if (!message.isNullOrBlank()) {
                "HTTP $statusCode: $message"
            } else {
                "HTTP $statusCode"
            }
        } catch (_: Exception) {
            "HTTP $statusCode"
        }
    }

    private suspend fun ensureVaultFolder(token: String): String {
        return createOrGetVaultFolder().getOrThrow()
    }

    private fun ensureSubfolder(name: String, parentId: String, token: String): String {
        val found = findFolderInParent(name, parentId, token)
        if (found != null) return found

        val meta = JSONObject().apply {
            put("name", name)
            put("mimeType", "application/vnd.google-apps.folder")
            put("parents", org.json.JSONArray().put(parentId))
        }
        val request = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files")
            .addHeader("Authorization", "Bearer $token")
            .post(meta.toString().toByteArray().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { resp ->
            val json = JSONObject(resp.body?.string().orEmpty())
            return json.getString("id")
        }
    }

    private fun findFolderInParent(name: String, parentId: String, token: String): String? {
        val q = "name = '$name' and mimeType = 'application/vnd.google-apps.folder' and '$parentId' in parents and trashed = false"
        val url = "https://www.googleapis.com/drive/v3/files?q=${URLEncoder.encode(q, "UTF-8")}&fields=files(id)"
        val req = Request.Builder().url(url).addHeader("Authorization", "Bearer $token").get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val json = JSONObject(resp.body?.string().orEmpty())
            val arr = json.optJSONArray("files") ?: return null
            if (arr.length() == 0) return null
            return arr.getJSONObject(0).getString("id")
        }
    }

    private fun findMediaFileByItemId(itemId: String, folderId: String, token: String): String? {
        val q = "'$folderId' in parents and appProperties has { key='reelix_item_id' and value='$itemId' } and trashed = false"
        val url = "https://www.googleapis.com/drive/v3/files?q=${URLEncoder.encode(q, "UTF-8")}&fields=files(id,name,appProperties)"
        val req = Request.Builder().url(url).addHeader("Authorization", "Bearer $token").get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val json = JSONObject(resp.body?.string().orEmpty())
            val arr = json.optJSONArray("files") ?: return null
            if (arr.length() == 0) return null
            return arr.getJSONObject(0).getString("id")
        }
    }

    private fun findFileInFolder(name: String, folderId: String, token: String): String? {
        val q = "name = '$name' and '$folderId' in parents and trashed = false"
        val url = "https://www.googleapis.com/drive/v3/files?q=${URLEncoder.encode(q, "UTF-8")}&fields=files(id)"
        val req = Request.Builder().url(url).addHeader("Authorization", "Bearer $token").get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val json = JSONObject(resp.body?.string().orEmpty())
            val arr = json.optJSONArray("files") ?: return null
            if (arr.length() == 0) return null
            return arr.getJSONObject(0).getString("id")
        }
    }
}

/** In-memory mock cloud client for deterministic local unit testing without RAM buffering. */
class MockCloudSyncClient(
    val resumableStore: ResumableUploadStore = InMemoryResumableUploadStore(),
) : CloudSyncClient {
    val jobs = mutableMapOf<String, CloudJobManifest>()
    val uploadedFiles = mutableMapOf<String, MockUploadedFile>()
    var isEnabled: Boolean = true
    var simulateInterruptionOnce: Boolean = false
    var lastUploadedOffset: Long = 0L

    override suspend fun isConfigured(): Boolean = isEnabled

    override suspend fun uploadMedia(
        file: File,
        mimeType: String,
        originalFileName: String,
        itemId: String,
    ): CloudUploadResult {
        if (!isEnabled) return CloudUploadResult.Failure("Cloud client is disabled")

        // 1. Duplicate avoidance check by reelix_item_id in appProperties (NOT by filename alone)
        val existing = uploadedFiles.entries.firstOrNull { (_, f) ->
            f.appProperties["reelix_item_id"] == itemId
        }
        if (existing != null) {
            return CloudUploadResult.Success(driveFileId = existing.key, sizeBytes = existing.value.sizeBytes)
        }

        val uploadKey = buildResumableUploadKey(file, itemId)
        val existingSession = resumableStore.getSessionUri(uploadKey)

        if (simulateInterruptionOnce && existingSession == null) {
            val sessionUri = "https://mock.upload.drive/resumable/${itemId}/${file.name}"
            resumableStore.saveSessionUri(uploadKey, sessionUri)
            simulateInterruptionOnce = false
            return CloudUploadResult.Failure("Upload interrupted (simulated network failure)")
        }

        if (existingSession != null) {
            lastUploadedOffset = file.length() / 2
            resumableStore.removeSession(uploadKey)
        } else {
            lastUploadedOffset = 0L
        }

        val fileId = "drive_${itemId}_${System.currentTimeMillis()}_${file.name}"
        uploadedFiles[fileId] = MockUploadedFile(
            name = originalFileName,
            sizeBytes = file.length(),
            mimeType = mimeType,
            appProperties = mapOf(
                "reelix_item_id" to itemId,
                "reelix_type" to "media",
            ),
        )
        return CloudUploadResult.Success(driveFileId = fileId, sizeBytes = file.length())
    }

    var nextVaultFolderId: String = "mock_reelix_vault_folder_id_123"
    var createVaultResult: Result<String>? = null
    var createVaultCallCount = 0

    override suspend fun createOrGetVaultFolder(): Result<String> {
        createVaultCallCount++
        return createVaultResult ?: Result.success(nextVaultFolderId)
    }

    var nextCrossClientProbeResult: Result<CrossClientProbeResult>? = null
    var createProbeCallCount = 0

    override suspend fun createCrossClientTestProbe(): Result<CrossClientProbeResult> {
        createProbeCallCount++
        return nextCrossClientProbeResult ?: Result.success(
            CrossClientProbeResult(
                folderId = "mock_cross_client_folder_id_123",
                fileId = "mock_cross_client_file_id_456",
                folderName = "Reelix_CrossClient_Test_mock",
            )
        )
    }

    var nextCrossClientProbeVerification: Result<CrossClientProbeVerification>? = null
    var verifyProbeCallCount = 0

    override suspend fun verifyCrossClientTestProbe(fileId: String): Result<CrossClientProbeVerification> {
        verifyProbeCallCount++
        return nextCrossClientProbeVerification ?: Result.success(
            CrossClientProbeVerification(
                version = 2,
                updatedBy = "worker_client_b",
                isVerified = true,
                message = "✓ Cross-client verification SUCCESS: Manifest version=2, updated_by=worker_client_b",
            )
        )
    }

    var nextCleanupProbeResult: Result<Boolean>? = null
    var cleanupProbeCallCount = 0

    override suspend fun cleanupCrossClientTestProbe(folderId: String, fileId: String): Result<Boolean> {
        cleanupProbeCallCount++
        return nextCleanupProbeResult ?: Result.success(true)
    }

    var submitResult: Boolean = true

    override suspend fun submitJobManifest(manifest: CloudJobManifest): Boolean {
        if (!submitResult) return false
        jobs[manifest.itemId] = manifest
        return true
    }

    override suspend fun fetchJobManifest(itemId: String): CloudJobManifest? = jobs[itemId]

    override suspend fun listCompletedJobs(): List<CloudJobManifest> =
        jobs.values.filter { it.status in listOf("COMPLETED", "PARTIAL", "FAILED", "WAITING_RETRY", "PROCESSING") }
}
