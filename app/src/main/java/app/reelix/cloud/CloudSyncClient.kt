package app.reelix.cloud

import app.reelix.core.ReelixLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

sealed interface CloudUploadResult {
    data class Success(val driveFileId: String, val sizeBytes: Long) : CloudUploadResult
    data class Failure(val error: String) : CloudUploadResult
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
 * Android client for Google Drive v3 persistent storage using least-privilege OAuth 2.0.
 *
 * Implements chunked streaming resumable uploads so video files are NEVER loaded entirely into RAM.
 * Handles token expiration, duplicate avoidance, and transient network errors.
 */
class GoogleDriveCloudClient(
    private val client: OkHttpClient,
    private val folderIdProvider: suspend () -> String?,
    private val tokenProvider: suspend () -> String?,
    private val tokenRefresher: (suspend () -> String?)? = null,
) : CloudSyncClient {

    override suspend fun isConfigured(): Boolean {
        val folder = folderIdProvider()
        val token = tokenProvider()
        return !folder.isNullOrBlank() && !token.isNullOrBlank()
    }

    override suspend fun uploadMedia(file: File, mimeType: String, originalFileName: String): CloudUploadResult =
        withContext(Dispatchers.IO) {
            if (!file.exists()) {
                return@withContext CloudUploadResult.Failure("Media file does not exist on disk: ${file.absolutePath}")
            }

            var token = tokenProvider()
                ?: return@withContext CloudUploadResult.Failure("Google Drive authentication token missing")
            val rootFolderId = folderIdProvider()
                ?: return@withContext CloudUploadResult.Failure("Google Drive folder ID missing")

            try {
                // 1. Ensure/get media folder
                val mediaFolderId = ensureSubfolder("media", rootFolderId, token)

                // 2. Duplicate avoidance check
                val existingFileId = findFileInFolder(originalFileName, mediaFolderId, token)
                if (existingFileId != null) {
                    ReelixLog.d("File $originalFileName already uploaded to Drive (id=$existingFileId); reusing")
                    return@withContext CloudUploadResult.Success(driveFileId = existingFileId, sizeBytes = file.length())
                }

                // 3. Initiate Resumable Upload session (avoids loading media file into RAM)
                val mimeTypeStr = mimeType.ifBlank { "application/octet-stream" }
                val initUrl = "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable"
                val metadata = JSONObject().apply {
                    put("name", originalFileName)
                    put("parents", org.json.JSONArray().put(mediaFolderId))
                }

                fun buildInitRequest(authToken: String): Request = Request.Builder()
                    .url(initUrl)
                    .addHeader("Authorization", "Bearer $authToken")
                    .addHeader("X-Upload-Content-Type", mimeTypeStr)
                    .addHeader("X-Upload-Content-Length", file.length().toString())
                    .post(metadata.toString().toByteArray().toRequestBody("application/json; charset=UTF-8".toMediaType()))
                    .build()

                var initResp = client.newCall(buildInitRequest(token)).execute()

                // Token expiration recovery
                if (initResp.code == 401 && tokenRefresher != null) {
                    initResp.close()
                    ReelixLog.d("Drive access token expired during upload initiation; refreshing...")
                    val refreshed = tokenRefresher.invoke()
                    if (!refreshed.isNullOrBlank()) {
                        token = refreshed
                        initResp = client.newCall(buildInitRequest(token)).execute()
                    }
                }

                val uploadLocation = initResp.use { response ->
                    if (!response.isSuccessful) {
                        val body = response.body?.string().orEmpty()
                        return@withContext CloudUploadResult.Failure("Failed to initiate Drive resumable upload (${response.code}): $body")
                    }
                    response.header("Location")
                        ?: return@withContext CloudUploadResult.Failure("Missing Location header in resumable upload session")
                }

                // 4. Stream the file directly from disk into the resumable session (Okio 8KB buffer streaming)
                val streamBody = file.asRequestBody(mimeTypeStr.toMediaType())
                val uploadReq = Request.Builder()
                    .url(uploadLocation)
                    .put(streamBody)
                    .build()

                client.newCall(uploadReq).execute().use { uploadResp ->
                    if (!uploadResp.isSuccessful) {
                        val body = uploadResp.body?.string().orEmpty()
                        return@withContext CloudUploadResult.Failure("Streaming upload failed (${uploadResp.code}): $body")
                    }
                    val json = JSONObject(uploadResp.body?.string().orEmpty())
                    val fileId = json.getString("id")
                    ReelixLog.d("Streaming upload succeeded for $originalFileName (driveFileId=$fileId)")
                    CloudUploadResult.Success(driveFileId = fileId, sizeBytes = file.length())
                }
            } catch (e: Exception) {
                ReelixLog.w("Failed to stream media to Google Drive", e)
                CloudUploadResult.Failure("Exception during Drive streaming upload: ${e.message}")
            }
        }

    override suspend fun submitJobManifest(manifest: CloudJobManifest): Boolean = withContext(Dispatchers.IO) {
        val token = tokenProvider() ?: return@withContext false
        val rootFolderId = folderIdProvider() ?: return@withContext false

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
        val rootFolderId = folderIdProvider() ?: return@withContext null

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
        val rootFolderId = folderIdProvider() ?: return@withContext emptyList()

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

            val completed = mutableListOf<CloudJobManifest>()
            for ((id, name) in files) {
                if (!name.endsWith(".json")) continue
                val contentUrl = "https://www.googleapis.com/drive/v3/files/$id?alt=media"
                val fetchReq = Request.Builder().url(contentUrl).addHeader("Authorization", "Bearer $token").get().build()
                client.newCall(fetchReq).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string().orEmpty()
                        val manifest = CloudJobManifest.fromJson(body)
                        if (manifest.status == "COMPLETED") {
                            completed.add(manifest)
                        }
                    }
                }
            }
            completed
        } catch (e: Exception) {
            ReelixLog.w("Failed to list completed jobs from Drive", e)
            emptyList()
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
class MockCloudSyncClient : CloudSyncClient {
    val jobs = mutableMapOf<String, CloudJobManifest>()
    val uploadedFiles = mutableMapOf<String, MockUploadedFile>()
    var isEnabled: Boolean = true

    override suspend fun isConfigured(): Boolean = isEnabled

    override suspend fun uploadMedia(file: File, mimeType: String, originalFileName: String): CloudUploadResult {
        if (!isEnabled) return CloudUploadResult.Failure("Cloud client is disabled")
        val fileId = "drive_${System.currentTimeMillis()}_${file.name}"
        // Record upload metadata without loading entire file into memory
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
        jobs.values.filter { it.status == "COMPLETED" }
}
