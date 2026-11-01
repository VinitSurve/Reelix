package app.reelix.cloud

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
import java.util.concurrent.ConcurrentHashMap

sealed interface CloudUploadResult {
    data class Success(val driveFileId: String, val sizeBytes: Long) : CloudUploadResult
    data class Failure(val error: String) : CloudUploadResult
}

interface ResumableUploadStore {
    fun getSessionUri(key: String): String?
    fun saveSessionUri(key: String, uri: String)
    fun removeSession(key: String)
}

class InMemoryResumableUploadStore : ResumableUploadStore {
    private val map = ConcurrentHashMap<String, String>()
    override fun getSessionUri(key: String): String? = map[key]
    override fun saveSessionUri(key: String, uri: String) { map[key] = uri }
    override fun removeSession(key: String) { map.remove(key) }
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
    suspend fun uploadMedia(file: File, mimeType: String, originalFileName: String): CloudUploadResult
    suspend fun submitJobManifest(manifest: CloudJobManifest): Boolean
    suspend fun fetchJobManifest(itemId: String): CloudJobManifest?
    suspend fun listCompletedJobs(): List<CloudJobManifest>
}

data class MockUploadedFile(
    val name: String,
    val sizeBytes: Long,
    val mimeType: String,
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

    override suspend fun uploadMedia(file: File, mimeType: String, originalFileName: String): CloudUploadResult =
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

                // 2. Duplicate avoidance check in remote media folder
                val existingFileId = findFileInFolder(originalFileName, mediaFolderId, token)
                if (existingFileId != null) {
                    ReelixLog.d("File $originalFileName already uploaded to Drive (id=$existingFileId); reusing")
                    return@withContext CloudUploadResult.Success(driveFileId = existingFileId, sizeBytes = file.length())
                }

                val totalLength = file.length()
                val uploadKey = "${file.name}_$totalLength"
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
            val listUrl = "https://www.googleapis.com/drive/v3/files?q='${jobsFolderId}'+in+parents+and+trashed=false&fields=files(id,name)&pageSize=50"
            val request = Request.Builder()
                .url(listUrl)
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            val files = client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val json = JSONObject(resp.body?.string().orEmpty())
                val arr = json.optJSONArray("files") ?: return@withContext emptyList()
                val result = mutableListOf<Pair<String, String>>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    result.add(obj.getString("id") to obj.getString("name"))
                }
                result
            }

            val settled = mutableListOf<CloudJobManifest>()
            for ((id, name) in files) {
                if (!name.endsWith(".json")) continue
                val contentUrl = "https://www.googleapis.com/drive/v3/files/$id?alt=media"
                val fetchReq = Request.Builder().url(contentUrl).addHeader("Authorization", "Bearer $token").get().build()
                client.newCall(fetchReq).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string().orEmpty()
                        val manifest = CloudJobManifest.fromJson(body)
                        // Return settled manifests (COMPLETED, PARTIAL, FAILED) for honest synchronization
                        if (manifest.status in listOf("COMPLETED", "PARTIAL", "FAILED")) {
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

    private fun ensureVaultFolder(token: String): String {
        val q = "name = 'Reelix_Vault' and mimeType = 'application/vnd.google-apps.folder' and 'root' in parents and trashed = false"
        val url = "https://www.googleapis.com/drive/v3/files?q=${URLEncoder.encode(q, "UTF-8")}&fields=files(id)"
        val req = Request.Builder().url(url).addHeader("Authorization", "Bearer $token").get().build()
        client.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) {
                val json = JSONObject(resp.body?.string().orEmpty())
                val arr = json.optJSONArray("files")
                if (arr != null && arr.length() > 0) {
                    return arr.getJSONObject(0).getString("id")
                }
            }
        }

        val meta = JSONObject().apply {
            put("name", "Reelix_Vault")
            put("mimeType", "application/vnd.google-apps.folder")
            put("parents", org.json.JSONArray().put("root"))
        }
        val createReq = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files")
            .addHeader("Authorization", "Bearer $token")
            .post(meta.toString().toByteArray().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(createReq).execute().use { resp ->
            val json = JSONObject(resp.body?.string().orEmpty())
            return json.getString("id")
        }
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

    override suspend fun uploadMedia(file: File, mimeType: String, originalFileName: String): CloudUploadResult {
        if (!isEnabled) return CloudUploadResult.Failure("Cloud client is disabled")
        val uploadKey = "${file.name}_${file.length()}"
        val existingSession = resumableStore.getSessionUri(uploadKey)

        if (simulateInterruptionOnce && existingSession == null) {
            val sessionUri = "https://mock.upload.drive/resumable/${file.name}"
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

        val fileId = "drive_${System.currentTimeMillis()}_${file.name}"
        uploadedFiles[fileId] = MockUploadedFile(
            name = originalFileName,
            sizeBytes = file.length(),
            mimeType = mimeType,
        )
        return CloudUploadResult.Success(driveFileId = fileId, sizeBytes = file.length())
    }

    override suspend fun submitJobManifest(manifest: CloudJobManifest): Boolean {
        jobs[manifest.itemId] = manifest
        return true
    }

    override suspend fun fetchJobManifest(itemId: String): CloudJobManifest? = jobs[itemId]

    override suspend fun listCompletedJobs(): List<CloudJobManifest> =
        jobs.values.filter { it.status in listOf("COMPLETED", "PARTIAL", "FAILED") }
}
