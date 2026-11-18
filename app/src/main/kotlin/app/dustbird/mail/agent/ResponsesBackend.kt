package app.dustbird.mail.agent

import app.dustbird.mail.core.agent.AgentBackend
import app.dustbird.mail.core.agent.AgentBackendException
import app.dustbird.mail.core.agent.AgentItem
import app.dustbird.mail.core.agent.AgentRequest
import app.dustbird.mail.core.agent.BackendFailure
import app.dustbird.mail.core.agent.ToolInvocation
import app.dustbird.mail.core.agent.ToolSpec
import java.io.BufferedReader
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

data class BackendConfig(
    val baseUrl: String,
    val token: String,
    val model: String,
    val headers: Map<String, String> = emptyMap(),
    val encryptedReasoning: Boolean = false,
) {
    override fun toString(): String = "BackendConfig(baseUrl=$baseUrl, token=***, model=$model)"
}

class ResponsesBackend(private val config: suspend () -> BackendConfig?) : AgentBackend {
    override suspend fun respond(request: AgentRequest, onTextDelta: (String) -> Unit): List<AgentItem.Model> {
        val resolved = config() ?: throw AgentBackendException(BackendFailure.NOT_CONFIGURED, "The agent connection is not set up")
        val job = coroutineContext[Job]
        return withContext(Dispatchers.IO) {
            val connection = try {
                URL(resolved.baseUrl.trimEnd('/') + "/responses").openConnection() as HttpURLConnection
            } catch (error: Exception) {
                throw AgentBackendException(BackendFailure.NOT_CONFIGURED, "The server address is not valid")
            }
            val cancel = job?.invokeOnCompletion { connection.disconnect() }
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 20_000
                connection.readTimeout = 180_000
                connection.doOutput = true
                connection.setRequestProperty("Authorization", "Bearer ${resolved.token}")
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Accept", "text/event-stream")
                resolved.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
                connection.outputStream.use { it.write(ResponsesWire.requestBody(request, resolved).toByteArray(Charsets.UTF_8)) }
                val status = connection.responseCode
                if (status !in 200..299) {
                    val body = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                    throw ResponsesWire.httpFailure(status, body)
                }
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    if (ResponsesWire.startsWithJson(reader)) ResponsesWire.readJson(reader.readText()) else ResponsesWire.readStream(reader, onTextDelta)
                }
            } catch (error: AgentBackendException) {
                throw error
            } catch (error: IOException) {
                throw AgentBackendException(BackendFailure.NETWORK, error.message ?: "Network error")
            } catch (error: JSONException) {
                throw AgentBackendException(BackendFailure.SERVER, "The server answer could not be read (${connection.contentType}): ${error.message.orEmpty().take(160)}")
            } finally {
                cancel?.dispose()
                connection.disconnect()
            }
        }
    }
}

object ResponsesWire {
    const val NAMESPACE = "mail"
    private const val PEEK_LIMIT = 4096

    fun requestBody(request: AgentRequest, config: BackendConfig): String = JSONObject().apply {
        put("model", config.model)
        put("instructions", request.instructions)
        put("store", false)
        put("stream", true)
        put("input", JSONArray().also { input -> request.items.forEach { item -> inputItem(item)?.let(input::put) } })
        if (request.tools.isNotEmpty()) put("tools", tools(request.tools))
        if (config.encryptedReasoning) put("include", JSONArray().put("reasoning.encrypted_content"))
    }.toString()

    private fun inputItem(item: AgentItem): JSONObject? = when (item) {
        is AgentItem.User -> JSONObject().put("role", "user").put("content", item.text)
        is AgentItem.ToolOutput -> JSONObject().put("type", "function_call_output").put("call_id", item.callId).put("output", item.output)
        is AgentItem.Model -> try {
            JSONObject(item.raw).apply {
                remove("id")
                remove("status")
            }.takeUnless { it.optString("type") == "reasoning" && it.optString("encrypted_content").isBlank() }
        } catch (_: JSONException) {
            null
        }
    }

    private fun tools(specs: List<ToolSpec>): JSONArray = JSONArray().also { functions ->
        specs.forEach { spec ->
            functions.put(
                JSONObject()
                    .put("type", "function")
                    .put("name", spec.name)
                    .put("description", spec.description)
                    .put("parameters", JSONObject(spec.parameters))
                    .put("strict", false),
            )
        }
    }

    fun startsWithJson(reader: BufferedReader): Boolean {
        reader.mark(PEEK_LIMIT)
        try {
            repeat(PEEK_LIMIT - 1) {
                val next = reader.read()
                if (next < 0) return false
                if (!next.toChar().isWhitespace()) return next.toChar() == '{'
            }
            return false
        } finally {
            reader.reset()
        }
    }

    fun readStream(reader: BufferedReader, onTextDelta: (String) -> Unit): List<AgentItem.Model> {
        val items = ArrayList<AgentItem.Model>()
        var completed = false
        var event = ""
        val data = StringBuilder()
        fun dispatch() {
            val payload = data.toString()
            data.setLength(0)
            val name = event
            event = ""
            if (payload.isBlank()) return
            if (payload.trim() == "[DONE]") {
                completed = completed || items.isNotEmpty()
                return
            }
            val json = try {
                JSONObject(payload)
            } catch (error: JSONException) {
                throw JSONException("event '$name' was not JSON: " + payload.take(60).replace('\n', ' '))
            }
            when (json.optString("type", name).ifEmpty { name }) {
                "response.output_text.delta" -> onTextDelta(json.optString("delta"))
                "response.output_item.done" -> json.optJSONObject("item")?.let { items += itemOf(it) }
                "response.completed" -> {
                    completed = true
                    if (items.isEmpty()) items += outputOf(json.optJSONObject("response"))
                }
                "response.failed" -> throw failureOf(json.optJSONObject("response")?.optJSONObject("error"))
                "response.incomplete" -> throw AgentBackendException(BackendFailure.INCOMPLETE, "The answer was cut off")
                "error" -> throw failureOf(json.optJSONObject("error") ?: json)
            }
        }
        while (true) {
            val line = reader.readLine() ?: break
            when {
                line.isEmpty() -> dispatch()
                line.startsWith("event:") -> event = line.substring(6).trim()
                line.startsWith("data:") -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.substring(5).trimStart())
                }
            }
        }
        dispatch()
        if (!completed) throw AgentBackendException(BackendFailure.INCOMPLETE, "The connection closed before the answer finished")
        return items
    }

    fun readJson(body: String): List<AgentItem.Model> {
        val json = JSONObject(body)
        json.optJSONObject("error")?.let { throw failureOf(it) }
        return outputOf(json)
    }

    private fun outputOf(response: JSONObject?): List<AgentItem.Model> {
        val output = response?.optJSONArray("output") ?: return emptyList()
        return (0 until output.length()).mapNotNull { index -> output.optJSONObject(index)?.let(::itemOf) }
    }

    fun itemOf(json: JSONObject): AgentItem.Model = when (json.optString("type")) {
        "message" -> {
            val content = json.optJSONArray("content")
            val text = buildString {
                if (content != null) {
                    for (index in 0 until content.length()) {
                        val part = content.optJSONObject(index) ?: continue
                        if (part.optString("type") == "output_text") append(part.optString("text"))
                    }
                }
            }
            AgentItem.Model(json.toString(), text = text.ifBlank { null })
        }
        "function_call" -> AgentItem.Model(
            raw = json.toString(),
            call = ToolInvocation(
                callId = json.optString("call_id"),
                name = json.optString("name").substringAfterLast('.').removePrefix("${NAMESPACE}__"),
                arguments = json.optString("arguments", "{}").ifBlank { "{}" },
            ),
        )
        else -> AgentItem.Model(json.toString())
    }

    fun httpFailure(status: Int, body: String): AgentBackendException {
        val error = try {
            JSONObject(body).optJSONObject("error")
        } catch (_: JSONException) {
            null
        }
        val code = error?.optString("code").orEmpty()
        val detail = error?.optString("message").orEmpty().ifBlank { "HTTP $status" }
        val failure = when {
            "usage_limit" in code || "usage_unavailable" in code || status == 429 -> BackendFailure.USAGE_LIMIT
            status == 401 || status == 403 -> BackendFailure.AUTH
            status in 400..499 -> BackendFailure.REJECTED
            else -> BackendFailure.SERVER
        }
        return AgentBackendException(failure, detail.take(300))
    }

    private fun failureOf(error: JSONObject?): AgentBackendException {
        val code = error?.optString("code").orEmpty()
        val detail = error?.optString("message").orEmpty().ifBlank { code.ifBlank { "The request failed" } }
        val failure = when {
            "usage_limit" in code || "usage_unavailable" in code || "rate_limit" in code -> BackendFailure.USAGE_LIMIT
            "auth" in code || "token" in code -> BackendFailure.AUTH
            "unsupported" in code || "invalid" in code -> BackendFailure.REJECTED
            else -> BackendFailure.SERVER
        }
        return AgentBackendException(failure, detail.take(300))
    }
}
