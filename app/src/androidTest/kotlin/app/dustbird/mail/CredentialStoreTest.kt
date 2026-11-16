package app.dustbird.mail

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.dustbird.mail.data.secure.KeystoreCredentialStore
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class CredentialStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = KeystoreCredentialStore(context)
    private val ids = listOf(9101L, 9102L)

    @Before
    @After
    fun clean() = ids.forEach(store::delete)

    private fun file(id: Long) = File(File(context.noBackupFilesDir, "credentials"), "$id.bin")

    @Test
    fun secretsRoundTripPerAccountAndStayIsolated() {
        store.put(9101, "first-secret")
        store.put(9102, "둘째 암호 ✓")
        assertEquals("first-secret", store.get(9101))
        assertEquals("둘째 암호 ✓", store.get(9102))
    }

    @Test
    fun storedFilesNeverContainThePlaintext() {
        store.put(9101, "plain-text-marker-123")
        assertFalse(String(file(9101).readBytes(), Charsets.ISO_8859_1).contains("plain-text-marker-123"))
    }

    @Test
    fun overwritingReplacesTheSecret() {
        store.put(9101, "old")
        store.put(9101, "new")
        assertEquals("new", store.get(9101))
    }

    @Test
    fun deleteRemovesOnlyThatAccount() {
        store.put(9101, "a")
        store.put(9102, "b")
        store.delete(9101)
        assertNull(store.get(9101))
        assertFalse(file(9101).exists())
        assertEquals("b", store.get(9102))
    }

    @Test
    fun aBlobCopiedToAnotherAccountCannotBeDecrypted() {
        store.put(9101, "a")
        store.put(9102, "b")
        file(9102).writeBytes(file(9101).readBytes())
        assertNull(store.get(9102))
    }

    @Test
    fun corruptedOrTruncatedFilesReadAsMissing() {
        store.put(9101, "a")
        val bytes = file(9101).readBytes()
        file(9101).writeBytes(bytes.copyOf(bytes.size - 3))
        assertNull(store.get(9101))
        file(9101).writeBytes(byteArrayOf())
        assertNull(store.get(9101))
    }

    @Test
    fun aMissingKeyMeansReauthenticationNotACrash() {
        store.put(9101, "a")
        val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keyStore.deleteEntry("dustbird.account.9101")
        assertNull(store.get(9101))
        assertTrue(file(9101).exists())
    }

    @Test
    fun eachSaveUsesAFreshIv() {
        store.put(9101, "same")
        val first = file(9101).readBytes()
        store.put(9101, "same")
        assertNotEquals(first.toList(), file(9101).readBytes().toList())
    }

    @Test
    fun deleteAllClearsEverything() {
        store.put(9101, "a")
        store.put(9102, "b")
        store.deleteAll()
        assertNull(store.get(9101))
        assertNull(store.get(9102))
    }
}
