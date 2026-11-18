package app.dustbird.mail.agent

import android.util.Base64
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.OffsetDateTime
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

class DeviceCode(val deviceAuthId: String, val userCode: String, val intervalMillis: Long, val expiresAt: Long)

class DeviceGrant(val authorizationCode: String, val codeVerifier: String)

class AuthException(val code: String) : IOException("Sign-in failed: $code")

object CodexAuth {
    const val ISSUER = "https://auth.openai.com"
    const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
    const val BASE_URL = "https://chatgpt.com/backend-api/codex"
    const val ORIGINATOR = "dustbird"
    const val CODE_LIFETIME_MILLIS = 15L * 60 * 1000
    private const val AUTH_CLAIM = "https://api.openai.com/auth"

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

    private fun errorCode(json: JSONObject, status: Int): String {
        val error = json.opt("error")
        val code = if (error is JSONObject) error.optString("code") else error?.toString().orEmpty()
        return code.ifBlank { "http_$status" }
    }

    private fun post(url: String, body: String, contentType: String, userAgent: String): Pair<Int, JSONObject> {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", contentType)
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("originator", ORIGINATOR)
            connection.setRequestProperty("User-Agent", userAgent)
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                val buffer = CharArray(8192)
                val collected = StringBuilder()
                while (collected.length < MAX_BODY) {
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
}
