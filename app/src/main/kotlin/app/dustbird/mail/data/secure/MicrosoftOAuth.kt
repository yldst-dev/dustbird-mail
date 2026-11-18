package app.dustbird.mail.data.secure

import app.dustbird.mail.core.model.Credentials
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

class OAuthToken(val clientId: String, val accessToken: String, val refreshToken: String, val expiresAt: Long) {
    fun encode(): String = STORED_PREFIX + listOf(clientId, expiresAt.toString(), accessToken, refreshToken).joinToString("\n")

    override fun toString(): String = "OAuthToken(***)"

    companion object {
        const val STORED_PREFIX = "oauth2-stored:"

        fun decode(stored: String): OAuthToken? {
            if (!stored.startsWith(STORED_PREFIX)) return null
            val parts = stored.removePrefix(STORED_PREFIX).split("\n")
            val expiresAt = parts.getOrNull(1)?.toLongOrNull()
            if (parts.size != 4 || expiresAt == null || parts.any { it.isEmpty() }) return null
            return OAuthToken(parts[0], parts[2], parts[3], expiresAt)
        }

        fun loginSecret(stored: String): String = decode(stored)?.let { Credentials.BEARER_PREFIX + it.accessToken } ?: stored
    }
}

class OAuthException(val code: String) : IOException("Sign-in failed: $code")

class DeviceLogin(val deviceCode: String, val userCode: String, val url: String, val intervalMillis: Long, val expiresAt: Long)

object MicrosoftOAuth {
    const val AUTHORITY = "https://login.microsoftonline.com/common/oauth2/v2.0"
    const val THUNDERBIRD_CLIENT_ID = "9e5f94bc-e8a4-4e73-b8be-63364c29d753"
    const val SCOPE = "https://outlook.office.com/IMAP.AccessAsUser.All https://outlook.office.com/SMTP.Send offline_access"
    private val clientIdShape = Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")
    private const val MAX_BODY = 256 * 1024

    fun isClientId(value: String): Boolean = clientIdShape.matches(value.trim())

    fun requestCode(clientId: String, now: Long, authority: String = AUTHORITY): DeviceLogin {
        val (status, json) = post("$authority/devicecode", form("client_id" to clientId, "scope" to SCOPE))
        if (status !in 200..299) throw OAuthException(json.optString("error").ifBlank { "http_$status" })
        val deviceCode = json.optString("device_code")
        val userCode = json.optString("user_code")
        if (deviceCode.isBlank() || userCode.isBlank()) throw OAuthException("missing_device_code")
        return DeviceLogin(
            deviceCode = deviceCode,
            userCode = userCode,
            url = json.optString("verification_uri").takeIf { it.startsWith("https://") } ?: "https://microsoft.com/devicelogin",
            intervalMillis = json.optLong("interval", 5).coerceIn(1, 60) * 1000,
            expiresAt = now + json.optLong("expires_in", 900).coerceIn(60, 1800) * 1000,
        )
    }

    fun poll(clientId: String, login: DeviceLogin, now: Long, authority: String = AUTHORITY): OAuthToken? {
        val body = form("grant_type" to "urn:ietf:params:oauth:grant-type:device_code", "client_id" to clientId, "device_code" to login.deviceCode)
        val (status, json) = post("$authority/token", body)
        if (status in 200..299) return tokenOf(clientId, json, now, previous = null)
        return when (val error = json.optString("error")) {
            "authorization_pending", "slow_down" -> null
            "" -> if (status == 429 || status >= 500) null else throw OAuthException("http_$status")
            else -> throw OAuthException(error)
        }
    }

    fun refresh(token: OAuthToken, now: Long, authority: String = AUTHORITY): OAuthToken {
        val body = form("grant_type" to "refresh_token", "client_id" to token.clientId, "refresh_token" to token.refreshToken, "scope" to SCOPE)
        val (status, json) = post("$authority/token", body)
        if (status == 429 || status >= 500) throw IOException("The sign-in server is busy")
        if (status !in 200..299) throw OAuthException(json.optString("error").ifBlank { "http_$status" })
        return tokenOf(token.clientId, json, now, previous = token)
    }

    private fun tokenOf(clientId: String, json: JSONObject, now: Long, previous: OAuthToken?): OAuthToken = OAuthToken(
        clientId = clientId,
        accessToken = json.optString("access_token").ifBlank { throw OAuthException("missing_access_token") },
        refreshToken = json.optString("refresh_token").ifBlank { previous?.refreshToken ?: throw OAuthException("missing_refresh_token") },
        expiresAt = now + json.optLong("expires_in", 3600).coerceAtLeast(60) * 1000,
    )

    private fun post(url: String, body: String): Pair<Int, JSONObject> {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.setRequestProperty("Accept", "application/json")
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
}

class TokenCredentialStore(
    private val delegate: CredentialStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val refresh: (OAuthToken, Long) -> OAuthToken = { token, now -> MicrosoftOAuth.refresh(token, now) },
) : CredentialStore by delegate {
    override fun get(accountId: Long): String? {
        val stored = delegate.get(accountId) ?: return null
        val token = OAuthToken.decode(stored) ?: return stored
        val fresh = synchronized(this) {
            val current = delegate.get(accountId)?.let(OAuthToken::decode) ?: token
            val now = clock()
            if (current.expiresAt - now > REFRESH_MARGIN_MILLIS) {
                current
            } else {
                try {
                    refresh(current, now).also { delegate.put(accountId, it.encode()) }
                } catch (_: OAuthException) {
                    return null
                }
            }
        }
        return Credentials.BEARER_PREFIX + fresh.accessToken
    }

    private companion object {
        const val REFRESH_MARGIN_MILLIS = 120_000L
    }
}

enum class SignInFailure { CLIENT, EXPIRED, DECLINED, NETWORK }

sealed interface SignInState {
    data object Idle : SignInState
    data object Starting : SignInState
    data class Code(val userCode: String, val url: String) : SignInState
    data object Done : SignInState
    data class Failed(val failure: SignInFailure) : SignInState
}

class MicrosoftSignIn(
    private val scope: CoroutineScope,
    private val authority: String = MicrosoftOAuth.AUTHORITY,
    private val onToken: suspend (OAuthToken) -> Unit,
) {
    private val _state = MutableStateFlow<SignInState>(SignInState.Idle)
    val state: StateFlow<SignInState> = _state
    private var job: Job? = null

    fun start(clientId: String) {
        val id = clientId.trim()
        if (job?.isActive == true) return
        if (!MicrosoftOAuth.isClientId(id)) {
            _state.value = SignInState.Failed(SignInFailure.CLIENT)
            return
        }
        _state.value = SignInState.Starting
        job = scope.launch {
            _state.value = try {
                val login = withContext(Dispatchers.IO) { MicrosoftOAuth.requestCode(id, System.currentTimeMillis(), authority) }
                _state.value = SignInState.Code(login.userCode, login.url)
                var token: OAuthToken? = null
                while (token == null) {
                    if (System.currentTimeMillis() >= login.expiresAt) throw OAuthException("expired_token")
                    delay(login.intervalMillis)
                    token = withContext(Dispatchers.IO) { MicrosoftOAuth.poll(id, login, System.currentTimeMillis(), authority) }
                }
                onToken(token)
                SignInState.Done
            } catch (error: CancellationException) {
                throw error
            } catch (error: OAuthException) {
                SignInState.Failed(
                    when (error.code) {
                        "expired_token" -> SignInFailure.EXPIRED
                        "authorization_declined", "access_denied" -> SignInFailure.DECLINED
                        else -> SignInFailure.CLIENT
                    },
                )
            } catch (_: IOException) {
                SignInState.Failed(SignInFailure.NETWORK)
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _state.value = SignInState.Idle
    }
}
