package app.dustbird.mail.data.secure

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface CredentialStore {
    fun put(accountId: Long, secret: String)
    fun get(accountId: Long): String?
    fun delete(accountId: Long)
    fun deleteAll()
}

class KeystoreCredentialStore(context: Context) : CredentialStore {
    private val directory = File(context.noBackupFilesDir, "credentials").apply { mkdirs() }
    private val keyStore: KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    override fun put(accountId: Long, secret: String) {
        val alias = alias(accountId)
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, createKey(alias))
            updateAAD(aad(accountId))
        }
        val encrypted = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
        val temp = File(directory, "$accountId.tmp")
        temp.writeBytes(byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + encrypted)
        check(temp.renameTo(file(accountId))) { "Could not store credential" }
    }

    override fun get(accountId: Long): String? {
        val stored = file(accountId).takeIf { it.exists() }?.readBytes() ?: return null
        val key = try {
            keyStore.getKey(alias(accountId), null) as? SecretKey
        } catch (_: GeneralSecurityException) {
            null
        } ?: return null
        return try {
            val ivLength = stored[0].toInt()
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, stored, 1, ivLength))
                updateAAD(aad(accountId))
            }
            String(cipher.doFinal(stored, 1 + ivLength, stored.size - 1 - ivLength), Charsets.UTF_8)
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: IndexOutOfBoundsException) {
            null
        }
    }

    override fun delete(accountId: Long) {
        file(accountId).delete()
        File(directory, "$accountId.tmp").delete()
        runCatching { keyStore.deleteEntry(alias(accountId)) }
    }

    override fun deleteAll() {
        directory.listFiles()?.forEach { it.delete() }
        keyStore.aliases().toList().filter { it.startsWith(ALIAS_PREFIX) }.forEach { runCatching { keyStore.deleteEntry(it) } }
    }

    private fun file(accountId: Long) = File(directory, "$accountId.bin")

    private fun alias(accountId: Long) = "$ALIAS_PREFIX$accountId"

    private fun aad(accountId: Long) = "dustbird:account:$accountId".toByteArray(Charsets.UTF_8)

    private fun createKey(alias: String): SecretKey {
        fun build(strongBox: Boolean): SecretKey {
            val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setIsStrongBoxBacked(strongBox)
                .build()
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply { init(spec) }.generateKey()
        }
        return try {
            build(strongBox = true)
        } catch (_: StrongBoxUnavailableException) {
            build(strongBox = false)
        }
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS_PREFIX = "dustbird.account."
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}
