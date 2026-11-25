package app.reelix.cloud

import app.reelix.core.ReelixLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

data class WorkflowRunInfo(
    val id: Long,
    val htmlUrl: String,
    val status: String,
    val conclusion: String?,
    val createdAt: String?,
)

sealed class WorkflowDispatchResult {
    data class Success(val runInfo: WorkflowRunInfo?) : WorkflowDispatchResult()
    data class Failure(val message: String, val statusCode: Int? = null) : WorkflowDispatchResult()
}

sealed class TokenValidationResult {
    data class Success(
        val username: String?,
        val workflowName: String?,
        val repoAccessible: Boolean,
    ) : TokenValidationResult()

    data class Failure(
        val message: String,
        val statusCode: Int? = null,
    ) : TokenValidationResult()
}

/**
 * Client for triggering GitHub Actions workflows via the REST API (`workflow_dispatch`).
 *
 * Implements:
 * - Direct authenticated POST to `https://api.github.com/repos/{owner}/{repo}/actions/workflows/{workflow_id}/dispatches`
 * - Strict HTTP 204 No Content verification (never falsely reports success)
 * - Detailed error extraction for 401 (invalid/expired PAT), 403 (missing actions:write scope),
 *   404 (workflow/repo not found), and 422 (invalid ref/branch)
 * - Safe retrieval of newly scheduled workflow run ID and URL for immediate user verification
 */
open class GitHubWorkflowClient(
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val baseUrl: String = "https://api.github.com",
) {

    /**
     * Dispatches a workflow on GitHub Actions.
     *
     * @param repo The repository in "owner/repo" format (e.g. "VinitSurve/Reelix").
     * @param workflowId The workflow filename or ID (e.g. "process-queue.yml").
     * @param ref The git branch or tag to run against (e.g. "main").
     * @param token A GitHub Personal Access Token (classic with `repo` or fine-grained with `actions:write`).
     */
    open suspend fun dispatchWorkflow(
        repo: String,
        workflowId: String,
        ref: String,
        token: String,
    ): WorkflowDispatchResult = withContext(Dispatchers.IO) {
        val trimmedToken = token.trim()
        if (trimmedToken.isBlank()) {
            return@withContext WorkflowDispatchResult.Failure("GitHub Personal Access Token is required.", 401)
        }

        val cleanRepo = repo.trim().removePrefix("https://github.com/").trim('/')
        if (!cleanRepo.contains('/')) {
            return@withContext WorkflowDispatchResult.Failure("Invalid repository format '$cleanRepo'. Expected 'owner/repo'.")
        }

        val cleanWorkflow = workflowId.trim().ifBlank { "process-queue.yml" }
        val cleanRef = ref.trim().ifBlank { "main" }

        val rootUrl = baseUrl.trimEnd('/')
        val url = "$rootUrl/repos/$cleanRepo/actions/workflows/$cleanWorkflow/dispatches"
        val payload = JSONObject().apply {
            put("ref", cleanRef)
        }.toString()

        val request = Request.Builder()
            .url(url)
            .post(payload.toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $trimmedToken")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                when (response.code) {
                    204 -> {
                        ReelixLog.d("Successfully dispatched workflow $cleanWorkflow on $cleanRepo ($cleanRef)")
                        // Attempt to query the newly created run metadata
                        val runInfo = fetchLatestRun(cleanRepo, cleanWorkflow, trimmedToken)
                        WorkflowDispatchResult.Success(runInfo)
                    }
                    401 -> {
                        val body = response.body?.string().orEmpty()
                        ReelixLog.w("GitHub dispatch 401 Unauthorized: $body")
                        WorkflowDispatchResult.Failure("Invalid or expired GitHub Personal Access Token (HTTP 401).", 401)
                    }
                    403 -> {
                        val body = response.body?.string().orEmpty()
                        ReelixLog.w("GitHub dispatch 403 Forbidden: $body")
                        WorkflowDispatchResult.Failure(
                            "GitHub token lacks required 'actions:write' permission (HTTP 403).",
                            403,
                        )
                    }
                    404 -> {
                        val body = response.body?.string().orEmpty()
                        ReelixLog.w("GitHub dispatch 404 Not Found: $body")
                        WorkflowDispatchResult.Failure(
                            "Workflow '$cleanWorkflow' or repository '$cleanRepo' not found (HTTP 404).",
                            404,
                        )
                    }
                    422 -> {
                        val body = response.body?.string().orEmpty()
                        ReelixLog.w("GitHub dispatch 422 Unprocessable: $body")
                        WorkflowDispatchResult.Failure("Invalid branch or ref '$cleanRef' (HTTP 422).", 422)
                    }
                    else -> {
                        val body = response.body?.string().orEmpty()
                        val msg = parseErrorMessage(body) ?: "GitHub API returned HTTP ${response.code}"
                        ReelixLog.w("GitHub dispatch failed (${response.code}): $msg")
                        WorkflowDispatchResult.Failure(msg, response.code)
                    }
                }
            }
        } catch (e: IOException) {
            ReelixLog.w("Network error during GitHub workflow dispatch", e)
            WorkflowDispatchResult.Failure("Network error: ${e.message ?: e.javaClass.simpleName}")
        } catch (e: Exception) {
            ReelixLog.w("Unexpected error during GitHub workflow dispatch", e)
            WorkflowDispatchResult.Failure("Unexpected error: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * Retrieves metadata for the most recent workflow run for the specified workflow.
     */
    open suspend fun fetchLatestRun(
        repo: String,
        workflowId: String,
        token: String,
    ): WorkflowRunInfo? = withContext(Dispatchers.IO) {
        val cleanRepo = repo.trim().removePrefix("https://github.com/").trim('/')
        val cleanWorkflow = workflowId.trim().ifBlank { "process-queue.yml" }
        val rootUrl = baseUrl.trimEnd('/')
        val url = "$rootUrl/repos/$cleanRepo/actions/workflows/$cleanWorkflow/runs?per_page=1"

        val request = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", "Bearer ${token.trim()}")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string().orEmpty()
                val json = JSONObject(body)
                val runs = json.optJSONArray("workflow_runs") ?: return@withContext null
                if (runs.length() == 0) return@withContext null
                val r = runs.getJSONObject(0)
                WorkflowRunInfo(
                    id = r.getLong("id"),
                    htmlUrl = r.getString("html_url"),
                    status = r.optString("status", "queued"),
                    conclusion = if (r.isNull("conclusion")) null else r.optString("conclusion"),
                    createdAt = r.optString("created_at"),
                )
            }
        } catch (e: Exception) {
            ReelixLog.w("Failed to fetch latest workflow run info", e)
            null
        }
    }

    /**
     * Validates a GitHub Personal Access Token against the repository and workflow.
     *
     * Queries `GET /repos/{owner}/{repo}/actions/workflows/{workflow_id}` to verify:
     * 1. Token authentication succeeds (HTTP 200)
     * 2. Token has permissions to access this repository and its Actions workflows
     * 3. Fetches authenticated username if permitted by token scope
     */
    open suspend fun validateToken(
        token: String,
        repo: String = "VinitSurve/Reelix",
        workflowId: String = "process-queue.yml",
    ): TokenValidationResult = withContext(Dispatchers.IO) {
        val trimmed = token.trim()
        if (trimmed.isBlank()) {
            return@withContext TokenValidationResult.Failure("Token cannot be blank.", 400)
        }
        val cleanRepo = repo.trim().removePrefix("https://github.com/").trim('/')
        if (!cleanRepo.contains('/')) {
            return@withContext TokenValidationResult.Failure("Invalid repository format '$cleanRepo'. Expected 'owner/repo'.", 400)
        }
        val cleanWorkflow = workflowId.trim().ifBlank { "process-queue.yml" }
        val rootUrl = baseUrl.trimEnd('/')

        val url = "$rootUrl/repos/$cleanRepo/actions/workflows/$cleanWorkflow"
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", "Bearer $trimmed")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> {
                        val body = response.body?.string().orEmpty()
                        val workflowName = runCatching {
                            JSONObject(body).optString("name").takeIf { it.isNotBlank() }
                        }.getOrNull()
                        val username = fetchUserLogin(trimmed)
                        TokenValidationResult.Success(
                            username = username,
                            workflowName = workflowName,
                            repoAccessible = true,
                        )
                    }
                    401 -> TokenValidationResult.Failure(
                        "Invalid or expired GitHub Personal Access Token (HTTP 401). Check the token.",
                        401,
                    )
                    403 -> TokenValidationResult.Failure(
                        "Token lacks required 'Actions: Read and write' permission on $cleanRepo (HTTP 403).",
                        403,
                    )
                    404 -> TokenValidationResult.Failure(
                        "Repository '$cleanRepo' or workflow '$cleanWorkflow' not accessible with this token (HTTP 404).",
                        404,
                    )
                    else -> {
                        val body = response.body?.string().orEmpty()
                        val msg = parseErrorMessage(body) ?: "GitHub API returned HTTP ${response.code}"
                        TokenValidationResult.Failure(msg, response.code)
                    }
                }
            }
        } catch (e: IOException) {
            TokenValidationResult.Failure("Network error checking token: ${e.message ?: e.javaClass.simpleName}")
        } catch (e: Exception) {
            TokenValidationResult.Failure("Unexpected error checking token: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun fetchUserLogin(token: String): String? {
        return runCatching {
            val req = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/user")
                .get()
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    JSONObject(resp.body?.string().orEmpty()).optString("login").takeIf { it.isNotBlank() }
                } else null
            }
        }.getOrNull()
    }

    private fun parseErrorMessage(body: String): String? {
        if (body.isBlank()) return null
        return runCatching {
            val json = JSONObject(body)
            json.optString("message").takeIf { it.isNotBlank() }
        }.getOrNull()
    }
}
