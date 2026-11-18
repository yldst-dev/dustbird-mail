package app.dustbird.mail.agent

import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

object ModelLists {
    private const val MAX_BODY = 4 * 1024 * 1024
    private val textFamilies = listOf("gpt-", "chatgpt-", "codex-", "o1", "o3", "o4")
    private val otherUses = listOf("embedding", "tts", "transcribe", "audio", "realtime", "image", "search", "moderation", "whisper", "dall-e", "instruct", "diarize")

    fun compatible(baseUrl: String, token: String, userAgent: String, official: Boolean): List<AgentModel> {
        val (status, json) = CodexAuth.call(baseUrl.trimEnd('/') + "/models", userAgent, MAX_BODY) { connection ->
            connection.requestMethod = "GET"
            connection.setRequestProperty("Authorization", "Bearer $token")
        }
        if (status == 401 || status == 403) throw AuthException(CodexAuth.errorCode(json, status))
        if (status !in 200..299) throw IOException("The model list could not be loaded: HTTP $status")
        return compatibleOf(json, official)
    }

    internal fun compatibleOf(json: JSONObject, official: Boolean): List<AgentModel> {
        if (json.optJSONArray("models") != null) return CodexAuth.modelsOf(json)
        val data = json.optJSONArray("data") ?: return emptyList()
        val entries = (0 until data.length()).mapNotNull { data.optJSONObject(it) }
            .filter { !official || isTextModel(AgentModel.text(it, "id")) }
            .sortedByDescending { it.optLong("created", 0) }
            .map { JSONObject().put("slug", AgentModel.text(it, "id")).put("display_name", AgentModel.text(it, "id")) }
        return AgentModel.fromArray(JSONArray(entries))
    }

    internal fun isTextModel(id: String): Boolean {
        val name = id.lowercase()
        return textFamilies.any(name::startsWith) && otherUses.none(name::contains)
    }
}
