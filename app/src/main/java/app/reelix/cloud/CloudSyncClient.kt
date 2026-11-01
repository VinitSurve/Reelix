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

/**
 * Android client for Google Drive v3 persistent storage.
 * Communicates with the Google Drive REST API using a scoped user OAuth token or service proxy.
 */
class GoogleDriveCloudClient(
    private val client: OkHttpClient,
    private val folderIdProvider: () -> String?,
    private val tokenProvider: () -> String?,
) : CloudSyncClient {

    override suspend fun isConfigured(): Boolean {
        val folder = folderIdProvider()
        val token = tokenProvider()
        return !folder.isNullOrBlank() && !token.isNullOrBlank()
    }

    override suspend fun uploadMedia(file: File, mimeType: String, originalFileName: String): CloudUploadResult =
        withContext(Dispatchers.IO) {
            val token = tokenProvider() ?: return@withContext CloudUploadResult.Failure("Google Drive authentication token missing")
            val rootFolderId = folderIdProvider() ?: return@withContext CloudUploadResult.Failure("Google Drive folder ID missing")

            try {
                // 1. Ensure/get media folder
                val mediaFolderId = ensureSubfolder("media", rootFolderId, token)

                // 2. Upload file
                val metadata = JSONObject().apply {
                    put("name", originalFileName)
                    put("parents", org.json.JSONArray().put(mediaFolderId))
                }

                // Simple upload using Drive upload endpoint
                val url = "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart"
                val boundary = "ReelixMultipartBoundary${System.currentTimeMillis()}"
                val mimeTypeStr = mimeType.ifBlank { "application/octet-stream" }

                val metadataHeader = "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n${metadata}\r\n"
                val fileHeader = "--$boundary\r\nContent-Type: $mimeTypeStr\r\n\r\n"
                val closing = "\r\n--$boundary--"

                val metaBytes = metadataHeader.toByteArray(Charsets.UTF_8)
                val fileHeaderBytes = fileHeader.toByteArray(Charsets.UTF_8)
                val closingBytes = closing.toByteArray(Charsets.UTF_8)

                val fileBytes = file.readBytes()
                val totalBytes = metaBytes + fileHeaderBytes + fileBytes + closingBytes

                val request = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $token")
                    .post(totalBytes.toRequestBody("multipart/related; boundary=$boundary".toMediaType()))
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val body = response.body?.string().orEmpty()
                        return@withContext CloudUploadResult.Failure("Drive upload failed (${response.code}): $body")
                    }
                    val json = JSONObject(response.body?.string().orEmpty())
                    val fileId = json.getString("id")
                    CloudUploadResult.Success(driveFileId = fileId, sizeBytes = file.length())
                }
            } catch (e: Exception) {
                ReelixLog.w("Failed to upload media to Google Drive", e)
                CloudUploadResult.Failure("Exception during upload: ${e.message}")
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
        val url = "https://www.googleapis.com/drive/v3/files?q=${java.net.URLEncoder.encode(q, "UTF-8")}&fields=files(id)"
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
        val url = "https://www.googleapis.com/drive/v3/files?q=${java.net.URLEncoder.encode(q, "UTF-8")}&fields=files(id)"
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

/** In-memory mock cloud client for deterministic local unit testing. */
class MockCloudSyncClient : CloudSyncClient {
    val jobs = mutableMapOf<String, CloudJobManifest>()
    val uploadedFiles = mutableMapOf<String, ByteArray>()
    var isEnabled: Boolean = true

    override suspend fun isConfigured(): Boolean = isEnabled

    override suspend fun uploadMedia(file: File, mimeType: String, originalFileName: String): CloudUploadResult {
        if (!isEnabled) return CloudUploadResult.Failure("Cloud client is disabled")
        val fileId = "drive_${System.currentTimeMillis()}_${file.name}"
        uploadedFiles[fileId] = file.readBytes()
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
