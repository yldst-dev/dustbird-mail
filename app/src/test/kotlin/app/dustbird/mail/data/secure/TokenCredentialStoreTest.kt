package app.dustbird.mail.data.secure

import app.dustbird.mail.core.model.Credentials
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class TokenCredentialStoreTest {
    private class MemoryStore : CredentialStore {
        val values = HashMap<Long, String>()
        override fun put(accountId: Long, secret: String) { values[accountId] = secret }
        override fun get(accountId: Long): String? = values[accountId]
        override fun delete(accountId: Long) { values.remove(accountId) }
        override fun deleteAll() = values.clear()
    }

    private val now = 1_800_000_000_000L
    private val client = "11111111-2222-3333-4444-555555555555"

    @Test
    fun passwordsPassThroughUntouched() {
        val memory = MemoryStore().apply { put(1, "app-password") }
        assertEquals("app-password", TokenCredentialStore(memory, { now }) { _, _ -> error("no refresh expected") }.get(1))
        assertNull(TokenCredentialStore(memory, { now }).get(2))
    }

    @Test
    fun aValidTokenIsHandedOutAsABearerSecretWithoutRefreshing() {
        val memory = MemoryStore().apply { put(1, OAuthToken(client, "access-1", "refresh-1", now + 600_000).encode()) }
        val secret = TokenCredentialStore(memory, { now }) { _, _ -> error("no refresh expected") }.get(1)
        assertEquals(Credentials.BEARER_PREFIX + "access-1", secret)
        assertEquals("access-1", Credentials("user@example.test", secret!!).password)
    }

    @Test
    fun anExpiringTokenIsRefreshedAndTheNewOneIsStored() {
        val memory = MemoryStore().apply { put(1, OAuthToken(client, "access-1", "refresh-1", now + 30_000).encode()) }
        val store = TokenCredentialStore(memory, { now }) { old, at -> OAuthToken(old.clientId, "access-2", "refresh-2", at + 3_600_000) }
        assertEquals(Credentials.BEARER_PREFIX + "access-2", store.get(1))
        val saved = OAuthToken.decode(memory.values.getValue(1))!!
        assertEquals(listOf(client, "access-2", "refresh-2"), listOf(saved.clientId, saved.accessToken, saved.refreshToken))
        assertEquals(now + 3_600_000, saved.expiresAt)
    }

    @Test
    fun aRevokedSignInLooksLikeAMissingCredentialAndANetworkErrorIsNotSwallowed() {
        val memory = MemoryStore().apply { put(1, OAuthToken(client, "access-1", "refresh-1", now - 1).encode()) }
        assertNull(TokenCredentialStore(memory, { now }) { _, _ -> throw OAuthException("invalid_grant") }.get(1))
        assertThrows(IOException::class.java) { TokenCredentialStore(memory, { now }) { _, _ -> throw IOException("offline") }.get(1) }
        assertEquals("access-1", OAuthToken.decode(memory.values.getValue(1))!!.accessToken)
    }

    @Test
    fun storedFormRoundTripsAndRejectsDamagedValues() {
        val token = OAuthToken(client, "a.b-c_d", "r/e+f=", 42)
        val back = OAuthToken.decode(token.encode())!!
        assertEquals(listOf(client, "a.b-c_d", "r/e+f=", "42"), listOf(back.clientId, back.accessToken, back.refreshToken, back.expiresAt.toString()))
        assertNull(OAuthToken.decode("plain password"))
        assertNull(OAuthToken.decode(OAuthToken.STORED_PREFIX + "only\ntwo"))
        assertEquals("plain password", OAuthToken.loginSecret("plain password"))
        assertEquals(Credentials.BEARER_PREFIX + "a.b-c_d", OAuthToken.loginSecret(token.encode()))
    }
}
