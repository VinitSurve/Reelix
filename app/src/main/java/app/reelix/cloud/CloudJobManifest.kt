package app.reelix.cloud

import app.reelix.domain.model.ProcessingStage
import app.reelix.domain.model.ProcessingStatus
import app.reelix.domain.model.StageStatus
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manifest representation shared between Android and the GitHub Actions cloud processor.
 * Stored persistently in Google Drive under `jobs/<item_id>.json`.
 */
data class CloudJobManifest(
    val version: Int = 1,
    val itemId: String,
    val source: String,
    val originalUrl: String? = null,
    val caption: String? = null,
    val userNotes: String? = null,
    val status: String = "QUEUED",
    val claimedBy: String? = null,
    val claimedAt: Long? = null,
    val leaseTimeoutMs: Long? = 900_000L,
    val etag: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val retryCount: Int = 0,
    val maxRetries: Int = 3,
    val errorMessage: String? = null,
    val media: CloudMediaInfo? = null,
    val stages: CloudStageStates = CloudStageStates(),
    val results: CloudJobResults = CloudJobResults(),
) {
    fun toJson(): String {
        val root = JSONObject()
        root.put("version", version)
        root.put("item_id", itemId)
        root.put("source", source)
        if (originalUrl != null) root.put("original_url", originalUrl)
        if (caption != null) root.put("caption", caption)
        if (userNotes != null) root.put("user_notes", userNotes)
        root.put("status", status)
        if (claimedBy != null) root.put("claimed_by", claimedBy)
        if (claimedAt != null) root.put("claimed_at", claimedAt)
        if (leaseTimeoutMs != null) root.put("lease_timeout_ms", leaseTimeoutMs)
        if (etag != null) root.put("etag", etag)
        root.put("created_at", createdAt)
        root.put("updated_at", updatedAt)
        root.put("retry_count", retryCount)
        root.put("max_retries", maxRetries)
        if (errorMessage != null) root.put("error_message", errorMessage)

        if (media != null) {
            val m = JSONObject()
            m.put("file_name", media.fileName)
            m.put("mime_type", media.mimeType)
            m.put("drive_file_id", media.driveFileId)
            m.put("size_bytes", media.sizeBytes)
            if (media.durationMs != null) m.put("duration_ms", media.durationMs)
            root.put("media", m)
        }

        val st = JSONObject()
        st.put("audio_extraction", stages.audioExtraction)
        st.put("transcription", stages.transcription)
        st.put("gemini_analysis", stages.geminiAnalysis)
        root.put("stages", st)

        val res = JSONObject()
        if (results.audioDriveFileId != null) res.put("audio_drive_file_id", results.audioDriveFileId)
        if (results.audioSizeBytes != null) res.put("audio_size_bytes", results.audioSizeBytes)
        if (results.transcript != null) res.put("transcript", results.transcript)
        if (results.transcriptModel != null) res.put("transcript_model", results.transcriptModel)

        if (results.analysis != null) {
            val a = JSONObject()
            a.put("summary", results.analysis.summary)
            a.put("detailed_summary", results.analysis.detailedSummary)
            a.put("main_topic", results.analysis.mainTopic)
            a.put("category", results.analysis.category)
            a.put("keywords", JSONArray(results.analysis.keywords))
            a.put("tags", JSONArray(results.analysis.tags))
            a.put("key_takeaways", JSONArray(results.analysis.keyTakeaways))
            a.put("important_concepts", JSONArray(results.analysis.importantConcepts))
            a.put("actionable_steps", JSONArray(results.analysis.actionableSteps))
            a.put("model_name", results.analysis.modelName)
            a.put("generated_at", results.analysis.generatedAt)
            res.put("analysis", a)
        }
        root.put("results", res)

        return root.toString(2)
    }

    companion object {
        fun fromJson(jsonStr: String): CloudJobManifest {
            val root = JSONObject(jsonStr)

            val mediaObj = root.optJSONObject("media")
            val media = mediaObj?.let {
                CloudMediaInfo(
                    fileName = it.optString("file_name", ""),
                    mimeType = it.optString("mime_type", ""),
                    driveFileId = it.optString("drive_file_id", ""),
                    sizeBytes = it.optLong("size_bytes", 0L),
                    durationMs = if (it.has("duration_ms")) it.optLong("duration_ms") else null,
                )
            }

            val stagesObj = root.optJSONObject("stages")
            val stages = CloudStageStates(
                audioExtraction = stagesObj?.optString("audio_extraction", "PENDING") ?: "PENDING",
                transcription = stagesObj?.optString("transcription", "PENDING") ?: "PENDING",
                geminiAnalysis = stagesObj?.optString("gemini_analysis", "PENDING") ?: "PENDING",
            )

            val resObj = root.optJSONObject("results")
            val analysisObj = resObj?.optJSONObject("analysis")
            val analysis = analysisObj?.let {
                CloudAnalysisResult(
                    summary = it.optString("summary", ""),
                    detailedSummary = it.optString("detailed_summary", ""),
                    mainTopic = it.optString("main_topic", ""),
                    category = it.optString("category", ""),
                    keywords = it.optJSONArray("keywords")?.toStringList() ?: emptyList(),
                    tags = it.optJSONArray("tags")?.toStringList() ?: emptyList(),
                    keyTakeaways = it.optJSONArray("key_takeaways")?.toStringList() ?: emptyList(),
                    importantConcepts = it.optJSONArray("important_concepts")?.toStringList() ?: emptyList(),
                    actionableSteps = it.optJSONArray("actionable_steps")?.toStringList() ?: emptyList(),
                    modelName = it.optString("model_name", "gemini-3.6-flash"),
                    generatedAt = it.optLong("generated_at", 0L),
                )
            }

            val results = CloudJobResults(
                audioDriveFileId = resObj?.optString("audio_drive_file_id")?.takeIf { it.isNotBlank() },
                audioSizeBytes = if (resObj?.has("audio_size_bytes") == true) resObj.optLong("audio_size_bytes") else null,
                transcript = resObj?.optString("transcript")?.takeIf { it.isNotBlank() },
                transcriptModel = resObj?.optString("transcript_model")?.takeIf { it.isNotBlank() },
                analysis = analysis,
            )

            return CloudJobManifest(
                version = root.optInt("version", 1),
                itemId = root.getString("item_id"),
                source = root.optString("source", "MEDIA_SHARE"),
                originalUrl = root.optString("original_url").takeIf { it.isNotBlank() },
                caption = root.optString("caption").takeIf { it.isNotBlank() },
                userNotes = root.optString("user_notes").takeIf { it.isNotBlank() },
                status = root.optString("status", "QUEUED"),
                claimedBy = root.optString("claimed_by").takeIf { it.isNotBlank() },
                claimedAt = if (root.has("claimed_at")) root.optLong("claimed_at") else null,
                leaseTimeoutMs = if (root.has("lease_timeout_ms")) root.optLong("lease_timeout_ms") else 900_000L,
                etag = root.optString("etag").takeIf { it.isNotBlank() },
                createdAt = root.optLong("created_at", 0L),
                updatedAt = root.optLong("updated_at", 0L),
                retryCount = root.optInt("retry_count", 0),
                maxRetries = root.optInt("max_retries", 3),
                errorMessage = root.optString("error_message").takeIf { it.isNotBlank() },
                media = media,
                stages = stages,
                results = results,
            )
        }

        private fun JSONArray.toStringList(): List<String> {
            val list = mutableListOf<String>()
            for (i in 0 until length()) {
                val str = optString(i).trim()
                if (str.isNotBlank()) list.add(str)
            }
            return list
        }
    }
}

data class CloudMediaInfo(
    val fileName: String,
    val mimeType: String,
    val driveFileId: String,
    val sizeBytes: Long = 0L,
    val durationMs: Long? = null,
)

data class CloudStageStates(
    val audioExtraction: String = "PENDING",
    val transcription: String = "PENDING",
    val geminiAnalysis: String = "PENDING",
)

data class CloudAnalysisResult(
    val summary: String,
    val detailedSummary: String,
    val mainTopic: String,
    val category: String,
    val keywords: List<String>,
    val tags: List<String>,
    val keyTakeaways: List<String>,
    val importantConcepts: List<String>,
    val actionableSteps: List<String>,
    val modelName: String,
    val generatedAt: Long,
)

data class CloudJobResults(
    val audioDriveFileId: String? = null,
    val audioSizeBytes: Long? = null,
    val transcript: String? = null,
    val transcriptModel: String? = null,
    val analysis: CloudAnalysisResult? = null,
)
