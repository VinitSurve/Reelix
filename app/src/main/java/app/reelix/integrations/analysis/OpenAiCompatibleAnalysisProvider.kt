package app.reelix.integrations.analysis

import app.reelix.core.safeDescription
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Calls any OpenAI-compatible `/chat/completions` endpoint chosen by the user — for example a free,
 * self-hosted Ollama or llama.cpp server.
 */
class OpenAiCompatibleAnalysisProvider(
    private val client: OkHttpClient,
    private val baseUrl: HttpUrl,
    private val model: String,
    private val apiKey: String?,
) : ContentAnalysisProvider {

    override val id: String = "openai-compatible"
    override val displayName: String = "OpenAI-compatible ($model)"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun testConnection(): ConnectionTestResult = withContext(Dispatchers.IO) {
        val testUrl = baseUrl.newBuilder().addPathSegments("models").build()
        val requestBuilder = Request.Builder().url(testUrl).get()
        apiKey?.takeIf { it.isNotBlank() }?.let { requestBuilder.header("Authorization", "Bearer $it") }

        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val models = extractModelNames(body)
                    ConnectionTestResult.Success(
                        message = "Connected successfully to ${baseUrl.host}:${baseUrl.port}",
                        modelsAvailable = models,
                    )
                } else if (response.code == 401 || response.code == 403) {
                    ConnectionTestResult.Failure("Authentication failed: Provider rejected API key (HTTP ${response.code})")
                } else {
                    // Try alternative Ollama /api/tags endpoint if /v1/models returned 404
                    val altUrl = baseUrl.newBuilder().encodedPath("/api/tags").build()
                    try {
                        client.newCall(Request.Builder().url(altUrl).get().build()).execute().use { altResp ->
                            if (altResp.isSuccessful) {
                                val altBody = altResp.body?.string().orEmpty()
                                val altModels = extractModelNames(altBody)
                                return@withContext ConnectionTestResult.Success(
                                    message = "Connected successfully to Ollama at ${baseUrl.host}",
                                    modelsAvailable = altModels,
                                )
                            }
                        }
                    } catch (_: Exception) {}

                    ConnectionTestResult.Failure("Provider returned HTTP ${response.code}: ${response.message}")
                }
            }
        } catch (e: IOException) {
            ConnectionTestResult.Failure("Connection failed: ${e.message ?: e.javaClass.simpleName} (check that your server is running and reachable)")
        } catch (e: Exception) {
            ConnectionTestResult.Failure("Error testing connection: ${e.safeDescription()}")
        }
    }

    override suspend fun analyze(input: AnalysisInput): AnalysisOutcome = withContext(Dispatchers.IO) {
        if (!input.hasEnoughText) return@withContext AnalysisOutcome.NoResult("Not enough text to analyse")

        val url = baseUrl.newBuilder().addPathSegments("chat/completions").build()
        val payload = buildJsonObject {
            put("model", model)
            put("temperature", 0.2)
            put("stream", false)
            putJsonArray("messages") {
                add(buildJsonObject { put("role", "system"); put("content", SYSTEM_PROMPT) })
                add(buildJsonObject { put("role", "user"); put("content", input.sourceText().take(MAX_INPUT_CHARS)) })
            }
            putJsonObject("response_format") { put("type", "json_object") }
        }
        val requestBuilder = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
        apiKey?.takeIf { it.isNotBlank() }?.let { requestBuilder.header("Authorization", "Bearer $it") }

        val (code, body) = try {
            client.newCall(requestBuilder.build()).execute().use { it.code to it.body?.string().orEmpty() }
        } catch (e: IOException) {
            return@withContext AnalysisOutcome.TransientFailure("Network error (${e.javaClass.simpleName})")
        }

        when {
            code == 429 || code >= 500 -> return@withContext AnalysisOutcome.TransientFailure("Provider returned HTTP $code")
            code == 401 || code == 403 -> return@withContext AnalysisOutcome.PermanentFailure("Provider rejected credentials (HTTP $code)")
            code !in 200..299 -> return@withContext AnalysisOutcome.PermanentFailure("Provider returned HTTP $code: $body")
        }

        try {
            parseResponse(body)
        } catch (e: Exception) {
            AnalysisOutcome.PermanentFailure("Unreadable provider response: ${e.safeDescription()}")
        }
    }

    internal fun parseResponse(body: String): AnalysisOutcome {
        val root = json.parseToJsonElement(body).jsonObject
        val content = (root["choices"] as? JsonArray)?.firstOrNull()?.jsonObject
            ?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
            ?: return AnalysisOutcome.PermanentFailure("Provider response had no message content")
        val objectText = extractJsonObject(content)
            ?: return AnalysisOutcome.PermanentFailure("Provider did not return a valid JSON object")
        val result = json.parseToJsonElement(objectText).jsonObject
        val summary = result.string("summary")
        if (summary.isNullOrBlank()) {
            return AnalysisOutcome.NoResult("The model returned an empty summary")
        }
        val modelName = (root["model"] as? JsonPrimitive)?.contentOrNull ?: model
        return AnalysisOutcome.Success(
            AnalysisResult(
                summary = summary.take(2_000),
                detailedSummary = result.string("detailed_summary")?.take(10_000),
                mainTopic = result.string("main_topic")?.take(200),
                keywords = result.stringList("keywords").take(25),
                tags = result.stringList("tags").take(15),
                category = result.string("category")?.take(60),
                keyTakeaways = result.stringList("key_takeaways").take(15),
                importantConcepts = result.stringList("important_concepts").take(15),
                actionableSteps = result.stringList("actionable_steps").take(15),
                modelName = modelName,
            ),
        )
    }

    private fun extractModelNames(body: String): List<String> = runCatching {
        val root = json.parseToJsonElement(body).jsonObject
        val data = (root["data"] as? JsonArray) ?: (root["models"] as? JsonArray)
        data?.mapNotNull { item ->
            (item as? JsonObject)?.let {
                (it["id"] as? JsonPrimitive)?.contentOrNull ?: (it["name"] as? JsonPrimitive)?.contentOrNull
            }
        } ?: emptyList()
    }.getOrDefault(emptyList())

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.ifEmpty { null }

    private fun JsonObject.stringList(key: String): List<String> = when (val value: JsonElement? = this[key]) {
        is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.ifEmpty { null } }
        is JsonPrimitive -> value.contentOrNull?.split('\n', ',')?.map { it.trim().trimStart('-', '•', '*') }?.filter { it.isNotEmpty() }.orEmpty()
        else -> emptyList()
    }

    private fun extractJsonObject(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        return if (start >= 0 && end > start) text.substring(start, end + 1) else null
    }

    companion object {
        private const val MAX_INPUT_CHARS = 24_000

        val SYSTEM_PROMPT = """
            You organise saved Instagram reels, posts, audio and video for a personal knowledge vault.
            Use ONLY the provided transcript, caption, title and user notes. Do NOT invent facts.
            Reply strictly with a single JSON object with these keys:
            - "summary": A concise 2-3 sentence overview.
            - "detailed_summary": An in-depth structured explanation of the content.
            - "main_topic": A short phrase describing the primary topic.
            - "category": One or two words classifying the subject.
            - "keywords": Array of up to 15 relevant search terms.
            - "tags": Array of up to 8 short lowercase tags without hash prefix.
            - "key_takeaways": Array of key takeaways / main learnings.
            - "important_concepts": Array of important concepts, principles, or terms explained.
            - "actionable_steps": Array of actionable steps, tips, or instructions (empty if not applicable).
            If there is not enough information to summarise, return {"summary": ""}.
        """.trimIndent()

        fun parseBaseUrl(raw: String): HttpUrl? {
            val trimmed = raw.trim().trimEnd('/')
            val withScheme = if (!trimmed.contains("://")) "http://$trimmed" else trimmed
            val url = withScheme.toHttpUrlOrNull() ?: return null
            val localHosts = setOf("localhost", "127.0.0.1", "10.0.2.2")
            if (!url.isHttps && url.host !in localHosts) return null
            return url
        }
    }
}
