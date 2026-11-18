package app.dustbird.mail.agent

import android.util.Base64
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.OffsetDateTime
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

data class ChatGptCredential(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val accountId: String?,
    val email: String?,
) {
    fun toJson(): String = JSONObject()
        .put("access_token", accessToken)
        .put("refresh_token", refreshToken)
        .put("expires_at", expiresAt)
        .put("account_id", accountId ?: JSONObject.NULL)
        .put("email", email ?: JSONObject.NULL)
        .toString()

    override fun toString(): String = "ChatGptCredential(email=$email, tokens=***)"

    companion object {
        fun fromJson(text: String): ChatGptCredential? = try {
            val json = JSONObject(text)
            ChatGptCredential(
                accessToken = json.getString("access_token"),
                refreshToken = json.getString("refresh_token"),
                expiresAt = json.getLong("expires_at"),
                accountId = if (json.isNull("account_id")) null else json.getString("account_id"),
                email = if (json.isNull("email")) null else json.getString("email"),
            )
        } catch (_: JSONException) {
            null
        }
    }
}

data class AgentModel(val slug: String, val name: String, val description: String) {
    companion object {
        private const val MAX_MODELS = 100
        private val slugPattern = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}")
        private val spaces = Regex("\\s+")

        fun of(slug: String, name: String, description: String): AgentModel? {
            val id = slug.trim()
            if (!slugPattern.matches(id)) return null
            return AgentModel(id, clean(name).take(60).ifBlank { id }, clean(description).take(200))
        }

        fun fromArray(json: JSONArray?): List<AgentModel> {
            if (json == null) return emptyList()
            return (0 until json.length()).mapNotNull { json.optJSONObject(it) }
                .mapNotNull { of(text(it, "slug"), text(it, "display_name"), text(it, "description")) }
                .distinctBy { it.slug }
                .take(MAX_MODELS)
        }

        fun fromStored(text: String): List<AgentModel> = try {
            fromArray(JSONArray(text.ifBlank { "[]" }))
        } catch (_: JSONException) {
            emptyList()
        }

        fun stored(models: List<AgentModel>): String = JSONArray().also { array ->
            models.forEach { array.put(JSONObject().put("slug", it.slug).put("display_name", it.name).put("description", it.description)) }
        }.toString()

        internal fun text(json: JSONObject, key: String): String = if (json.isNull(key)) "" else json.optString(key)

        private fun clean(text: String): String = text.filterNot { it.isISOControl() }.replace(spaces, " ").trim()
    }
}

class DeviceCode(val deviceAuthId: String, val userCode: String, val intervalMillis: Long, val expiresAt: Long)

class DeviceGrant(val authorizationCode: String, val codeVerifier: String)

class AuthException(val code: String) : IOException("Sign-in failed: $code")

object CodexAuth {
    const val ISSUER = "https://auth.openai.com"
    const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
    const val BASE_URL = "https://chatgpt.com/backend-api/codex"
    const val ORIGINATOR = "dustbird"
    const val FALLBACK_VERSION = "0.170.0"
    const val LATEST_RELEASE = "https://api.github.com/repos/openai/codex/releases/latest"
    const val CODE_LIFETIME_MILLIS = 15L * 60 * 1000
    private const val AUTH_CLAIM = "https://api.openai.com/auth"
    private val releaseTag = Regex("rust-v(\\d{1,4}\\.\\d{1,5}\\.\\d{1,6})")
    private val versionPattern = Regex("\\d{1,4}\\.\\d{1,5}\\.\\d{1,6}")

    fun isVersion(text: String): Boolean = versionPattern.matches(text)

    fun latestVersion(userAgent: String, url: String = LATEST_RELEASE): String? {
        val (status, json) = call(url, userAgent, MAX_RELEASE_BODY) { it.requestMethod = "GET" }
        if (status !in 200..299 || json.optBoolean("prerelease") || json.optBoolean("draft")) return null
        return releaseTag.matchEntire(AgentModel.text(json, "tag_name").trim())?.groupValues?.get(1)
    }

    fun verificationUrl(issuer: String = ISSUER): String = "$issuer/codex/device"

    fun requestCode(now: Long, userAgent: String, issuer: String = ISSUER): DeviceCode {
        val (status, json) = post("$issuer/api/accounts/deviceauth/usercode", JSONObject().put("client_id", CLIENT_ID).toString(), JSON, userAgent)
        if (status !in 200..299) throw AuthException(errorCode(json, status))
        val deviceAuthId = json.optString("device_auth_id")
        val userCode = json.optString("user_code").ifBlank { json.optString("usercode") }
        if (deviceAuthId.isBlank() || userCode.isBlank()) throw AuthException("missing_device_code")
        val expiresAt = runCatching { OffsetDateTime.parse(json.optString("expires_at")).toInstant().toEpochMilli() }.getOrNull()
        return DeviceCode(
            deviceAuthId = deviceAuthId,
            userCode = userCode,
            intervalMillis = (json.optString("interval").trim().toLongOrNull() ?: 5).coerceIn(1, 60) * 1000,
            expiresAt = (expiresAt ?: Long.MAX_VALUE).coerceAtMost(now + CODE_LIFETIME_MILLIS),
        )
    }

    fun poll(code: DeviceCode, userAgent: String, issuer: String = ISSUER): DeviceGrant? {
        val body = JSONObject().put("device_auth_id", code.deviceAuthId).put("user_code", code.userCode).toString()
        val (status, json) = post("$issuer/api/accounts/deviceauth/token", body, JSON, userAgent)
        return when {
            status in 200..299 -> {
                val authorizationCode = json.optString("authorization_code")
                val codeVerifier = json.optString("code_verifier")
                if (authorizationCode.isBlank() || codeVerifier.isBlank()) throw AuthException("missing_exchange_code")
                DeviceGrant(authorizationCode, codeVerifier)
            }
            status == 403 || status == 404 -> errorCode(json, status).let { if ("denied" in it || "expired" in it) throw AuthException(it) else null }
            status == 429 || status >= 500 -> null
            else -> throw AuthException(errorCode(json, status))
        }
    }

    fun exchange(grant: DeviceGrant, now: Long, userAgent: String, issuer: String = ISSUER): ChatGptCredential {
        val form = form(
            "grant_type" to "authorization_code",
            "code" to grant.authorizationCode,
            "redirect_uri" to "$issuer/deviceauth/callback",
            "client_id" to CLIENT_ID,
            "code_verifier" to grant.codeVerifier,
        )
        val (status, json) = post("$issuer/oauth/token", form, FORM, userAgent)
        if (status !in 200..299) throw AuthException(errorCode(json, status))
        return credentialOf(json, now, previous = null)
    }

    fun refresh(credential: ChatGptCredential, now: Long, userAgent: String, issuer: String = ISSUER): ChatGptCredential {
        val form = form("grant_type" to "refresh_token", "refresh_token" to credential.refreshToken, "client_id" to CLIENT_ID)
        val (status, json) = post("$issuer/oauth/token", form, FORM, userAgent)
        if (status == 429 || status >= 500) throw IOException("The sign-in server is busy")
        if (status !in 200..299) throw AuthException(errorCode(json, status))
        return credentialOf(json, now, previous = credential)
    }

    fun models(credential: ChatGptCredential, userAgent: String, clientVersion: String, baseUrl: String = BASE_URL): List<AgentModel> {
        require(isVersion(clientVersion)) { "Not a Codex version" }
        val (status, json) = call("$baseUrl/models?client_version=$clientVersion", userAgent, MAX_MODELS_BODY) { connection ->
            connection.requestMethod = "GET"
            connection.setRequestProperty("Authorization", "Bearer ${credential.accessToken}")
            headers(credential, userAgent).forEach(connection::setRequestProperty)
        }
        if (status == 401 || status == 403) throw AuthException(errorCode(json, status))
        if (status !in 200..299) throw IOException("The model list could not be loaded: HTTP $status")
        return modelsOf(json)
    }

    internal fun modelsOf(json: JSONObject): List<AgentModel> {
        val list = json.optJSONArray("models") ?: return emptyList()
        val shown = (0 until list.length()).mapNotNull { list.optJSONObject(it) }
            .filter { AgentModel.text(it, "visibility") == "list" }
            .sortedBy { it.optInt("priority", Int.MAX_VALUE) }
        return AgentModel.fromArray(JSONArray(shown))
    }

    fun headers(credential: ChatGptCredential, userAgent: String): Map<String, String> = buildMap {
        put("originator", ORIGINATOR)
        put("User-Agent", userAgent)
        credential.accountId?.let { put("ChatGPT-Account-ID", it) }
    }

    internal fun credentialOf(json: JSONObject, now: Long, previous: ChatGptCredential?): ChatGptCredential {
        val accessToken = json.optString("access_token").ifBlank { throw AuthException("missing_access_token") }
        val access = claims(accessToken)
        val identity = claims(json.optString("id_token"))
        val expiresIn = json.optLong("expires_in", 0)
        val tokenExpiry = access?.optLong("exp", 0) ?: 0
        return ChatGptCredential(
            accessToken = accessToken,
            refreshToken = json.optString("refresh_token").ifBlank { previous?.refreshToken ?: throw AuthException("missing_refresh_token") },
            expiresAt = when {
                expiresIn > 0 -> now + expiresIn * 1000
                tokenExpiry > 0 -> tokenExpiry * 1000
                else -> now
            },
            accountId = listOfNotNull(access, identity).firstNotNullOfOrNull { it.optJSONObject(AUTH_CLAIM)?.optString("chatgpt_account_id")?.ifBlank { null } }
                ?: previous?.accountId,
            email = identity?.optString("email")?.ifBlank { null } ?: previous?.email,
        )
    }

    private fun claims(token: String): JSONObject? = try {
        JSONObject(String(Base64.decode(token.split('.')[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP), Charsets.UTF_8))
    } catch (_: Exception) {
        null
    }

    internal fun errorCode(json: JSONObject, status: Int): String {
        val error = json.opt("error")
        val code = if (error is JSONObject) error.optString("code") else error?.toString().orEmpty()
        return code.ifBlank { "http_$status" }
    }

    private fun post(url: String, body: String, contentType: String, userAgent: String): Pair<Int, JSONObject> =
        call(url, userAgent, MAX_BODY) { connection ->
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", contentType)
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }

    internal fun call(url: String, userAgent: String, limit: Int, send: (HttpURLConnection) -> Unit): Pair<Int, JSONObject> {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("originator", ORIGINATOR)
            connection.setRequestProperty("User-Agent", userAgent)
            send(connection)
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                val buffer = CharArray(8192)
                val collected = StringBuilder()
                while (collected.length < limit) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    collected.append(buffer, 0, count)
                }
                collected.toString()
            }.orEmpty()
            val json = try {
                JSONObject(text)
            } catch (_: JSONException) {
                JSONObject()
            }
            return status to json
        } finally {
            connection.disconnect()
        }
    }

    private fun form(vararg parameters: Pair<String, String>): String =
        parameters.joinToString("&") { (key, value) -> key + "=" + URLEncoder.encode(value, "UTF-8") }

    private const val JSON = "application/json"
    private const val FORM = "application/x-www-form-urlencoded"
    private const val MAX_BODY = 256 * 1024
    private const val MAX_MODELS_BODY = 4 * 1024 * 1024
    private const val MAX_RELEASE_BODY = 1024 * 1024
}
