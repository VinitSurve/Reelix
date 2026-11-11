package app.reelix

import app.reelix.cloud.CloudAnalysisResult
import app.reelix.cloud.CloudJobManifest
import app.reelix.cloud.CloudJobResults
import app.reelix.cloud.CloudMediaInfo
import app.reelix.cloud.CloudStageStates
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ManifestContractTest {

    @Test
    fun androidManifestSerializesWithExactPythonSnakeCaseFieldNames() {
        val manifest = CloudJobManifest(
            schemaVersion = 1,
            recordRevision = 3L,
            version = 1,
            itemId = "item_uuid_999",
            source = "URL",
            originalUrl = "https://www.instagram.com/reel/C8xyz123/",
            caption = "Test caption",
            userNotes = "Test note",
            category = "Education",
            userTags = listOf("study", "focus"),
            status = "QUEUED",
            claimedBy = "runner-1",
            claimedAt = 1730000000000L,
            leaseTimeoutMs = 900000L,
            etag = "rev_3_etag",
            createdAt = 1729990000000L,
            updatedAt = 1730000500000L,
            retryCount = 1,
            maxRetries = 3,
            nextRetryAt = 1730000560000L,
            errorMessage = "None",
            media = CloudMediaInfo(
                fileName = "sample.mp4",
                mimeType = "video/mp4",
                driveFileId = "file_media_123",
                sizeBytes = 2048L,
                durationMs = 15000L,
            ),
            stages = CloudStageStates(
                acquisition = "SUCCEEDED",
                audioExtraction = "SUCCEEDED",
                transcription = "PENDING",
                ocr = "PENDING",
                geminiAnalysis = "PENDING",
            ),
        )

        val jsonStr = manifest.toJson()
        val json = JSONObject(jsonStr)

        // Strict snake_case contract verification
        assertEquals(1, json.getInt("schema_version"))
        assertEquals(3L, json.getLong("record_revision"))
        assertEquals(1, json.getInt("version"))
        assertEquals("item_uuid_999", json.getString("job_id"))
        assertEquals("item_uuid_999", json.getString("item_id"))
        assertEquals("URL", json.getString("source"))
        assertEquals("https://www.instagram.com/reel/C8xyz123/", json.getString("original_url"))
        assertEquals("Test caption", json.getString("caption"))
        assertEquals("Test note", json.getString("user_notes"))
        assertEquals("Education", json.getString("category"))
        assertEquals(2, json.getJSONArray("user_tags").length())
        assertEquals("QUEUED", json.getString("status"))
        assertEquals("runner-1", json.getString("claimed_by"))
        assertEquals(1730000000000L, json.getLong("claimed_at"))
        assertEquals(900000L, json.getLong("lease_timeout_ms"))
        assertEquals("rev_3_etag", json.getString("etag"))
        assertEquals(1729990000000L, json.getLong("created_at"))
        assertEquals(1730000500000L, json.getLong("updated_at"))
        assertEquals(1, json.getInt("retry_count"))
        assertEquals(3, json.getInt("max_retries"))
        assertEquals(1730000560000L, json.getLong("next_retry_at"))
        assertEquals("None", json.getString("error_message"))

        val media = json.getJSONObject("media")
        assertEquals("sample.mp4", media.getString("file_name"))
        assertEquals("video/mp4", media.getString("mime_type"))
        assertEquals("file_media_123", media.getString("drive_file_id"))
        assertEquals(2048L, media.getLong("size_bytes"))
        assertEquals(15000L, media.getLong("duration_ms"))

        val stages = json.getJSONObject("stages")
        assertEquals("SUCCEEDED", stages.getString("acquisition"))
        assertEquals("SUCCEEDED", stages.getString("audio_extraction"))
        assertEquals("PENDING", stages.getString("transcription"))
        assertEquals("PENDING", stages.getString("ocr"))
        assertEquals("PENDING", stages.getString("gemini_analysis"))
    }

    @Test
    fun androidParsesPythonGeneratedJsonWithStageDetailsAndArtifacts() {
        val pythonJson = """
            {
              "schema_version": 1,
              "record_revision": 5,
              "version": 1,
              "job_id": "item_py_100",
              "item_id": "item_py_100",
              "source": "URL",
              "status": "COMPLETED",
              "created_at": 1730000000000,
              "updated_at": 1730000100000,
              "stages": {
                "acquisition": "SUCCEEDED",
                "audio_extraction": "SUCCEEDED",
                "transcription": "SUCCEEDED",
                "ocr": "SUCCEEDED",
                "gemini_analysis": "SUCCEEDED"
              },
              "stage_details": {
                "acquisition": {
                  "status": "SUCCEEDED",
                  "attempts": 1,
                  "completed_at": 1730000010000
                }
              },
              "artifacts": {
                "audio_extraction": {
                  "drive_file_id": "drive_audio_py_555",
                  "content_hash": "a1b2c3d4e5f6"
                }
              },
              "results": {
                "audio_drive_file_id": "drive_audio_py_555",
                "audio_size_bytes": 102400,
                "transcript": "Transcribed by Python Whisper worker",
                "transcript_model": "base",
                "ocr_text": "Python OCR text",
                "analysis": {
                  "summary": "Summary by Python worker",
                  "detailed_summary": "Detailed summary by Python worker",
                  "main_topic": "Contract Testing",
                  "category": "Engineering",
                  "keywords": ["python", "android"],
                  "tags": ["contract", "test"],
                  "key_takeaways": ["Tests pass"],
                  "important_concepts": ["Interoperability"],
                  "actionable_steps": ["Ship it"],
                  "model_name": "gemini-3.6-flash",
                  "generated_at": 1730000100000
                }
              }
            }
        """.trimIndent()

        val parsed = CloudJobManifest.fromJson(pythonJson)

        assertEquals("item_py_100", parsed.itemId)
        assertEquals("COMPLETED", parsed.status)
        assertEquals(5L, parsed.recordRevision)
        assertEquals("SUCCEEDED", parsed.stages.acquisition)
        assertEquals("SUCCEEDED", parsed.stages.geminiAnalysis)

        assertNotNull(parsed.results.analysis)
        assertEquals("Summary by Python worker", parsed.results.analysis?.summary)
        assertEquals("Transcribed by Python Whisper worker", parsed.results.transcript)
        assertEquals("drive_audio_py_555", parsed.results.audioDriveFileId)
    }
}
