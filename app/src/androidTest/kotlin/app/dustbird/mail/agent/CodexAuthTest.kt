package app.dustbird.mail.agent

import android.util.Base64
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.dustbird.mail.AppContainer
import app.dustbird.mail.core.net.ImapConnector
import app.dustbird.mail.data.AgentMode
import app.dustbird.mail.data.db.MailDatabase
import app.dustbird.mail.ui.mailbox.MemoryCredentials
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CodexAuthTest {
    private val now = 1_800_000_000_000L
    private val agentName = "Dustbird/test"

    private fun jwt(payload: JSONObject): String {
        fun part(text: String) = Base64.encodeToString(text.toByteArray(), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        return part("{\"alg\":\"RS256\"}") + "." + part(payload.toString()) + ".signature"
    }

    private fun accessToken(account: String = "acct-1", expires: Long = now / 1000 + 600) =
        jwt(JSONObject().put("exp", expires).put("https://api.openai.com/auth", JSONObject().put("chatgpt_account_id", account)))

    private fun tokens(access: String = accessToken()) = JSONObject()
        .put("access_token", access)
        .put("refresh_token", "refresh-1")
        .put("id_token", jwt(JSONObject().put("email", "user@example.com")))

    private val pending = "{\"error\":{\"message\":\"Device authorization is pending.\",\"code\":\"deviceauth_authorization_pending\"}}"

    private inline fun rejected(expected: String, block: () -> Unit) {
        try {
            block()
            fail("Expected a rejection")
        } catch (error: AuthException) {
            assertEquals(expected, error.code)
        }
    }

    @Test
    fun deviceCodeRequestSendsTheClientAndReadsCodeIntervalAndExpiry() {
        MockHttp(contentType = "application/json") { _, _ ->
            200 to "{\"device_auth_id\":\"dev-1\",\"user_code\":\"ABCD-12345\",\"interval\":\"7\",\"expires_at\":\"2027-01-15T08:05:00.123456+00:00\"}"
        }.use { server ->
            val code = CodexAuth.requestCode(now, agentName, server.baseUrl)
            assertEquals("dev-1", code.deviceAuthId)
            assertEquals("ABCD-12345", code.userCode)
            assertEquals(7_000, code.intervalMillis)
            assertEquals(now + 5 * 60_000 + 123, code.expiresAt)
            assertTrue(server.headers.single().startsWith("POST /api/accounts/deviceauth/usercode "))
            assertTrue(server.headers.single().contains("originator: dustbird"))
            assertTrue(server.headers.single().contains("User-Agent: Dustbird/test"))
            assertEquals(CodexAuth.CLIENT_ID, JSONObject(server.requests.single()).getString("client_id"))
        }
        MockHttp(contentType = "application/json") { _, _ -> 200 to "{\"device_auth_id\":\"dev-1\",\"usercode\":\"WXYZ\",\"interval\":5}" }.use { server ->
            val code = CodexAuth.requestCode(now, agentName, server.baseUrl)
            assertEquals("WXYZ", code.userCode)
            assertEquals(5_000, code.intervalMillis)
            assertEquals(now + CodexAuth.CODE_LIFETIME_MILLIS, code.expiresAt)
        }
        MockHttp(contentType = "application/json") { _, _ -> 400 to "{\"error\":{\"code\":\"invalid_client\"}}" }.use { server ->
            rejected("invalid_client") { CodexAuth.requestCode(now, agentName, server.baseUrl) }
        }
        MockHttp(contentType = "application/json") { _, _ -> 200 to "{\"interval\":\"5\"}" }.use { server ->
            rejected("missing_device_code") { CodexAuth.requestCode(now, agentName, server.baseUrl) }
        }
    }

    @Test
    fun pollKeepsWaitingWhilePendingAndStopsOnAGrantOrARefusal() {
        val code = DeviceCode("dev-1", "ABCD-12345", 1_000, now + 60_000)
        val answers = listOf(
            403 to pending,
            404 to "{}",
            503 to "busy",
            200 to "{\"authorization_code\":\"code-1\",\"code_verifier\":\"verifier-1\",\"code_challenge\":\"c\"}",
            403 to "{\"error\":{\"code\":\"deviceauth_access_denied\"}}",
            400 to "{\"error\":{\"code\":\"invalid_request\"}}",
            200 to "{\"authorization_code\":\"code-1\"}",
        )
        val turn = AtomicInteger()
        MockHttp(contentType = "application/json") { _, _ -> answers[turn.getAndIncrement()] }.use { server ->
            assertNull(CodexAuth.poll(code, agentName, server.baseUrl))
            assertNull(CodexAuth.poll(code, agentName, server.baseUrl))
            assertNull(CodexAuth.poll(code, agentName, server.baseUrl))
            val grant = CodexAuth.poll(code, agentName, server.baseUrl)
            assertEquals("code-1", grant?.authorizationCode)
            assertEquals("verifier-1", grant?.codeVerifier)
            rejected("deviceauth_access_denied") { CodexAuth.poll(code, agentName, server.baseUrl) }
            rejected("invalid_request") { CodexAuth.poll(code, agentName, server.baseUrl) }
            rejected("missing_exchange_code") { CodexAuth.poll(code, agentName, server.baseUrl) }
            assertTrue(server.headers.first().startsWith("POST /api/accounts/deviceauth/token "))
            val sent = JSONObject(server.requests.first())
            assertEquals("dev-1", sent.getString("device_auth_id"))
            assertEquals("ABCD-12345", sent.getString("user_code"))
        }
    }

    @Test
    fun exchangeAndRefreshPostTheExpectedFormsAndBuildTheCredential() {
        MockHttp(contentType = "application/json") { _, body ->
            if ("grant_type=authorization_code" in body) 200 to tokens().put("expires_in", 3600).toString()
            else 200 to JSONObject().put("access_token", accessToken(expires = now / 1000 + 120)).toString()
        }.use { server ->
            val credential = CodexAuth.exchange(DeviceGrant("code-1", "verifier-1"), now, agentName, server.baseUrl)
            assertTrue(server.headers[0].startsWith("POST /oauth/token "))
            val callback = java.net.URLEncoder.encode(server.baseUrl + "/deviceauth/callback", "UTF-8")
            listOf("code=code-1", "code_verifier=verifier-1", "client_id=${CodexAuth.CLIENT_ID}", "redirect_uri=$callback").forEach { assertTrue(it, it in server.requests[0]) }
            assertFalse("client_secret" in server.requests[0])
            assertEquals("acct-1", credential.accountId)
            assertEquals("user@example.com", credential.email)
            assertEquals(now + 3_600_000, credential.expiresAt)
            assertEquals(mapOf("originator" to "dustbird", "User-Agent" to agentName, "ChatGPT-Account-ID" to "acct-1"), CodexAuth.headers(credential, agentName))

            val refreshed = CodexAuth.refresh(credential, now, agentName, server.baseUrl)
            assertTrue("grant_type=refresh_token" in server.requests[1] && "refresh_token=refresh-1" in server.requests[1] && "client_id=${CodexAuth.CLIENT_ID}" in server.requests[1])
            assertEquals("refresh-1", refreshed.refreshToken)
            assertEquals(now + 120_000, refreshed.expiresAt)
            assertEquals("user@example.com", refreshed.email)
            assertEquals("acct-1", refreshed.accountId)
        }
        val known = CodexAuth.credentialOf(tokens(), now, previous = null)
        MockHttp(contentType = "application/json") { _, _ -> 400 to "{\"error\":\"invalid_grant\"}" }.use { server ->
            rejected("invalid_grant") { CodexAuth.refresh(known, now, agentName, server.baseUrl) }
        }
        MockHttp(contentType = "application/json") { _, _ -> 503 to "busy" }.use { server ->
            try {
                CodexAuth.refresh(known, now, agentName, server.baseUrl)
                fail("Expected a temporary failure")
            } catch (error: IOException) {
                assertFalse(error is AuthException)
            }
        }
        rejected("missing_access_token") { CodexAuth.credentialOf(JSONObject(), now, previous = null) }
        rejected("missing_refresh_token") { CodexAuth.credentialOf(JSONObject().put("access_token", "opaque"), now, previous = null) }
    }

    @Test
    fun credentialRoundTripsAndNeverPrintsTokens() {
        val credential = CodexAuth.credentialOf(tokens(), now, previous = null)
        assertEquals(now + 600_000, credential.expiresAt)
        assertEquals(credential, ChatGptCredential.fromJson(credential.toJson()))
        assertFalse(credential.toString().contains("refresh-1"))
        assertFalse(credential.toString().contains(credential.accessToken))
        assertNull(ChatGptCredential.fromJson("{\"client_id\":\"old-format\"}"))
        val opaque = CodexAuth.credentialOf(JSONObject().put("access_token", "opaque").put("refresh_token", "r"), now, previous = null)
        assertNull(opaque.accountId)
        assertEquals(now, opaque.expiresAt)
        assertEquals(mapOf("originator" to "dustbird", "User-Agent" to agentName), CodexAuth.headers(opaque, agentName))
    }

    @Test
    fun signInShowsTheCodeWaitsForApprovalAndThenUsesTheCodexEndpoint() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, MailDatabase::class.java).build()
        val secrets = MemoryCredentials()
        val container = AppContainer(context, connector = ImapConnector { _, _ -> throw IOException("no network in tests") }, credentials = secrets, db = db)
        val polls = AtomicInteger()
        MockHttp(contentType = "application/json") { path, _ ->
            when (path) {
                "/api/accounts/deviceauth/usercode" -> 200 to "{\"device_auth_id\":\"dev-1\",\"user_code\":\"ABCD-12345\",\"interval\":\"1\"}"
                "/api/accounts/deviceauth/token" ->
                    if (polls.getAndIncrement() == 0) 403 to pending else 200 to "{\"authorization_code\":\"code-1\",\"code_verifier\":\"verifier-1\"}"
                else -> 200 to tokens(accessToken(expires = System.currentTimeMillis() / 1000 + 3600)).toString()
            }
        }.use { server ->
            try {
                container.settings.clearAll()
                val service = AgentService(db, container.settings, secrets, container.accounts, container.toolbox, container.scope, userAgent = agentName, authIssuer = server.baseUrl)
                service.update { it.copy(consent = true) }
                assertNull(service.config())
                service.startLogin()
                val shown = withTimeout(10_000) { service.login.first { it is LoginState.Code } } as LoginState.Code
                assertEquals("ABCD-12345", shown.userCode)
                assertEquals(server.baseUrl + "/codex/device", shown.url)
                withTimeout(15_000) { service.login.first { it is LoginState.Done } }
                assertEquals(2, polls.get())
                assertEquals("user@example.com", service.chatGptAccount())
                assertEquals(AgentMode.CHATGPT, service.current.first().mode)
                val config = assertNotNullValue(service.config())
                assertEquals(CodexAuth.BASE_URL, config.baseUrl)
                assertEquals("acct-1", config.headers["ChatGPT-Account-ID"])
                assertEquals("dustbird", config.headers["originator"])
                assertTrue(config.encryptedReasoning)
                assertFalse(config.toString().contains(config.token))
                service.signOut()
                assertNull(service.chatGptAccount())
                assertEquals(AgentMode.OFF, service.current.first().mode)
            } finally {
                container.scope.cancel()
                db.close()
            }
        }
    }

    private fun <T : Any> assertNotNullValue(value: T?): T {
        assertNotNull(value)
        return value!!
    }
}
