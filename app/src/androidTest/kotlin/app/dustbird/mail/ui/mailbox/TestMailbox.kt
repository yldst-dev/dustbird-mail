package app.dustbird.mail.ui.mailbox

import android.content.Context
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import app.dustbird.mail.AppContainer
import app.dustbird.mail.core.model.AuthMethod
import app.dustbird.mail.core.model.EmailAddress
import app.dustbird.mail.core.model.FolderRole
import app.dustbird.mail.core.model.SendVerification
import app.dustbird.mail.core.model.SyncMode
import app.dustbird.mail.core.model.TransportSecurity
import app.dustbird.mail.core.net.ImapConnector
import app.dustbird.mail.core.send.SendState
import app.dustbird.mail.data.Codec
import app.dustbird.mail.data.db.AccountEntity
import app.dustbird.mail.data.db.FolderEntity
import app.dustbird.mail.data.db.IdentityEntity
import app.dustbird.mail.data.db.MailDatabase
import app.dustbird.mail.data.db.MessageEntity
import app.dustbird.mail.data.db.OutboxEntity
import app.dustbird.mail.data.secure.CredentialStore
import java.io.IOException

class MemoryCredentials : CredentialStore {
    private val secrets = mutableMapOf<Long, String>()

    override fun put(accountId: Long, secret: String) {
        secrets[accountId] = secret
    }

    override fun get(accountId: Long): String? = secrets[accountId]

    override fun delete(accountId: Long) {
        secrets.remove(accountId)
    }

    override fun deleteAll() = secrets.clear()
}

class TestMailbox {
    val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    val db: MailDatabase = Room.inMemoryDatabaseBuilder(context, MailDatabase::class.java).build()
    val credentials = MemoryCredentials()
    val container = AppContainer(
        context,
        connector = ImapConnector { _, _ -> throw IOException("test network is unavailable") },
        credentials = credentials,
        db = db,
    )

    fun text(id: Int): String = context.getString(id)

    suspend fun account(key: String, label: String, sortOrder: Int = 0, withCredential: Boolean = false): Long {
        val id = db.accounts().insert(
            AccountEntity(
                providerId = "custom", displayName = label, label = label, username = "$key@example.test",
                imapHost = "imap.$key.test", imapPort = 993, imapSecurity = TransportSecurity.TLS,
                smtpHost = "smtp.$key.test", smtpPort = 587, smtpSecurity = TransportSecurity.STARTTLS,
                authMethod = AuthMethod.APP_PASSWORD, sortOrder = sortOrder, syncMode = SyncMode.MANUAL,
                notificationsEnabled = false, needsReauth = false, mailboxKey = key,
            ),
        )
        if (withCredential) credentials.put(id, "test-secret")
        return id
    }

    suspend fun identity(accountId: Long, address: String, label: String): Long = db.identities().insert(
        IdentityEntity(
            accountId = accountId, address = address, addressKey = address.lowercase(), displayName = "", signature = "",
            label = label, labelKey = label.lowercase(), sortOrder = 0, isAccountDefault = true, verification = SendVerification.UNVERIFIED,
        ),
    )

    suspend fun inbox(accountId: Long): Long = db.folders().insert(
        FolderEntity(
            accountId = accountId, path = "INBOX", delimiter = "/", attributes = "", role = FolderRole.INBOX, roleConfirmed = false,
            uidValidity = 1, highestUid = 0, baselineComplete = false, syncedAt = 0,
        ),
    )

    suspend fun message(
        accountId: Long,
        folderId: Long,
        uid: Long,
        subject: String,
        fromName: String? = "Sender $uid",
        to: String? = null,
        seen: Boolean = false,
        hasAttachment: Boolean = false,
    ): Long = db.messages().upsert(
        MessageEntity(
            accountId = accountId, folderId = folderId, uid = uid, uidValidity = 1, messageId = "<m$accountId-$uid@example.test>",
            inReplyTo = null, references = "", fromName = fromName, fromAddress = "sender$uid@example.test", replyTo = "",
            toAddresses = to?.let { Codec.addresses(listOf(EmailAddress(it))) }.orEmpty(), ccAddresses = "", deliveryValues = "",
            subject = subject, sortDate = System.currentTimeMillis() - uid * 60_000, preview = "Preview of $subject", size = 1,
            hasAttachment = hasAttachment, seen = seen, answered = false, flagged = false, allowRemoteImages = false,
        ),
    )

    suspend fun outbox(accountId: Long, identityId: Long, state: SendState, failure: String? = null, subject: String = "Outgoing subject"): Long =
        db.outbox().insert(
            OutboxEntity(
                accountId = accountId, identityId = identityId, state = state, attempts = 1, failure = failure,
                messageId = "<o$accountId-${state.name}@example.test>", subject = subject, recipientSummary = "friend@example.test",
                raw = byteArrayOf(), rawWithBcc = byteArrayOf(), envelopeFrom = "me@example.test", recipients = "friend@example.test",
                createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis(),
            ),
        )

    fun close() = db.close()
}
