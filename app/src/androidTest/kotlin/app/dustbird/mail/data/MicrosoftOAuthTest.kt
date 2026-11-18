package app.dustbird.mail.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.dustbird.mail.agent.MockHttp
import app.dustbird.mail.data.secure.MicrosoftOAuth
import app.dustbird.mail.data.secure.MicrosoftSignIn
import app.dustbird.mail.data.secure.OAuthException
import app.dustbird.mail.data.secure.OAuthToken
import app.dustbird.mail.data.secure.SignInFailure
import app.dustbird.mail.data.secure.SignInState
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MicrosoftOAuthTest {
    private val now = 1_800_000_000_000L
    private val client = "11111111-2222-3333-4444-555555555555"
    private val code = "{\"device_code\":\"dev-1\",\"user_code\":\"ABCD1234\",\"verification_uri\":\"https://microsoft.com/devicelogin\",\"expires_in\":900,\"interval\":1}"
    private val tokens = "{\"access_token\":\"access-1\",\"refresh_token\":\"refresh-1\",\"expires_in\":3600}"

    private fun fields(body: String) = body.split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }

    @Test
    fun deviceCodeFlowAsksForMailScopesAndTurnsTheGrantIntoAToken() {
        val polls = AtomicInteger()
        MockHttp(contentType = "application/json") { path, _ ->
            when {
                path.endsWith("/devicecode") -> 200 to code
                polls.incrementAndGet() == 1 -> 400 to "{\"error\":\"authorization_pending\"}"
                else -> 200 to tokens
            }
        }.use { server ->
            val login = MicrosoftOAuth.requestCode(client, now, server.baseUrl)
            assertEquals(listOf("ABCD1234", "https://microsoft.com/devicelogin"), listOf(login.userCode, login.url))
            assertEquals(1000L, login.intervalMillis)
            val asked = fields(server.requests.first())
            assertEquals(client, asked["client_id"])
            assertTrue(asked.getValue("scope").contains("https://outlook.office.com/IMAP.AccessAsUser.All"))
            assertTrue(asked.getValue("scope").contains("https://outlook.office.com/SMTP.Send"))
            assertTrue(asked.getValue("scope").contains("offline_access"))

            assertNull(MicrosoftOAuth.poll(client, login, now, server.baseUrl))
            val token = MicrosoftOAuth.poll(client, login, now, server.baseUrl)!!
            assertEquals(listOf("access-1", "refresh-1"), listOf(token.accessToken, token.refreshToken))
            assertEquals(now + 3_600_000, token.expiresAt)
            assertEquals("urn:ietf:params:oauth:grant-type:device_code", fields(server.requests.last())["grant_type"])
        }
    }

    @Test
    fun refreshKeepsTheOldRefreshTokenWhenNoneComesBackAndReportsRevocation() {
        MockHttp(contentType = "application/json") { _, body ->
            if ("refresh-dead" in body) 400 to "{\"error\":\"invalid_grant\"}" else 200 to "{\"access_token\":\"access-2\",\"expires_in\":1200}"
        }.use { server ->
            val fresh = MicrosoftOAuth.refresh(OAuthToken(client, "access-1", "refresh-1", now), now, server.baseUrl)
            assertEquals(listOf("access-2", "refresh-1"), listOf(fresh.accessToken, fresh.refreshToken))
            assertEquals(now + 1_200_000, fresh.expiresAt)
            try {
                MicrosoftOAuth.refresh(OAuthToken(client, "access-1", "refresh-dead", now), now, server.baseUrl)
                fail("Expected a rejection")
            } catch (error: OAuthException) {
                assertEquals("invalid_grant", error.code)
            }
        }
    }

    @Test
    fun signInWalksFromCodeToDoneAndRejectsAMalformedClientIdWithoutCallingOut() {
        val scope = CoroutineScope(Dispatchers.Default)
        val received = ArrayList<OAuthToken>()
        MockHttp(contentType = "application/json") { path, _ -> if (path.endsWith("/devicecode")) 200 to code else 200 to tokens }.use { server ->
            val signIn = MicrosoftSignIn(scope, server.baseUrl) { received += it }
            signIn.start("not-a-client-id")
            assertEquals(SignInState.Failed(SignInFailure.CLIENT), signIn.state.value)
            assertTrue(server.requests.isEmpty())

            signIn.start(" $client ")
            runBlocking { withTimeout(10_000) { signIn.state.first { it == SignInState.Done } } }
            assertEquals("access-1", received.single().accessToken)
            assertEquals(client, received.single().clientId)
        }
        scope.cancel()
    }
}
