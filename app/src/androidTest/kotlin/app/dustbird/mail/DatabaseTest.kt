package app.dustbird.mail

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.dustbird.mail.core.model.AuthMethod
import app.dustbird.mail.core.model.FolderRole
import app.dustbird.mail.core.model.SendVerification
import app.dustbird.mail.core.model.SyncMode
import app.dustbird.mail.core.model.TransportSecurity
import app.dustbird.mail.core.send.SendState
import app.dustbird.mail.data.db.AccountEntity
import app.dustbird.mail.data.db.DraftEntity
import app.dustbird.mail.data.db.FolderEntity
import app.dustbird.mail.data.db.IdentityEntity
import app.dustbird.mail.data.db.MailDatabase
import app.dustbird.mail.data.db.MessageEntity
import app.dustbird.mail.data.db.OutboxEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(AndroidJUnit4::class)
class DatabaseTest {
    private lateinit var db: MailDatabase

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, MailDatabase::class.java).build()
    }

    @After
    fun close() = db.close()

    private fun account(key: String) = AccountEntity(
        providerId = "custom", displayName = key, label = key, username = "$key@example.test", imapHost = "imap.$key.test", imapPort = 993,
        imapSecurity = TransportSecurity.TLS, smtpHost = "smtp.$key.test", smtpPort = 587, smtpSecurity = TransportSecurity.STARTTLS,
        authMethod = AuthMethod.APP_PASSWORD, sortOrder = 0, syncMode = SyncMode.MANUAL, notificationsEnabled = true, needsReauth = false,
        mailboxKey = key,
    )

    private fun identity(accountId: Long, address: String, label: String) = IdentityEntity(
        accountId = accountId, address = address, addressKey = address.lowercase(), displayName = "", signature = "", label = label,
        labelKey = label.lowercase(), sortOrder = 0, isAccountDefault = true, verification = SendVerification.UNVERIFIED,
    )

    private fun folder(accountId: Long) = FolderEntity(
        accountId = accountId, path = "INBOX", delimiter = "/", attributes = "", role = FolderRole.INBOX, roleConfirmed = false,
        uidValidity = 1, highestUid = 0, baselineComplete = false, syncedAt = 0,
    )

    private fun message(accountId: Long, folderId: Long, uid: Long) = MessageEntity(
        accountId = accountId, folderId = folderId, uid = uid, uidValidity = 1, messageId = "<m$uid@x>", inReplyTo = null, references = "",
        fromName = null, fromAddress = "a@x", replyTo = "", toAddresses = "", ccAddresses = "", deliveryValues = "", subject = "s",
        sortDate = uid, preview = "", size = 1, hasAttachment = false, seen = false, answered = false, flagged = false, allowRemoteImages = false,
    )

    private suspend fun seed(key: String): Long {
        val accountId = db.accounts().insert(account(key))
        db.identities().insert(identity(accountId, "$key@example.test", key))
        val folderId = db.folders().insert(folder(accountId))
        db.messages().upsert(message(accountId, folderId, 1))
        db.drafts().insert(DraftEntity(accountId = accountId, identityId = 1, toAddresses = "", ccAddresses = "", bccAddresses = "", subject = "", body = "", inReplyTo = null, references = "", messageId = "<d@x>", serverCopyAccountId = null, updatedAt = 0))
        db.outbox().insert(OutboxEntity(accountId = accountId, identityId = 1, state = SendState.QUEUED, attempts = 0, failure = null, messageId = "<o$key@x>", subject = "", recipientSummary = "", raw = byteArrayOf(), rawWithBcc = byteArrayOf(), envelopeFrom = "", recipients = "", createdAt = 0, updatedAt = 0))
        return accountId
    }

    @Test
    fun deletingAnAccountRemovesOnlyItsOwnData() = runBlocking<Unit> {
        val first = seed("one")
        val second = seed("two")
        db.accounts().delete(first)
        assertNull(db.accounts().get(first))
        assertEquals(0, db.identities().forAccount(first).size)
        assertEquals(0, db.folders().forAccount(first).size)
        assertEquals(0, db.outbox().byAccountAndStates(first, SendState.entries).size)
        assertEquals(0, db.drafts().countForAccount(first))
        assertNotNull(db.accounts().get(second))
        assertEquals(1, db.identities().forAccount(second).size)
        assertEquals(1, db.folders().forAccount(second).size)
        assertEquals(1, db.outbox().byAccountAndStates(second, SendState.entries).size)
        assertEquals(1, db.drafts().countForAccount(second))
        assertEquals(1, db.messages().observeUnified(FolderRole.INBOX, 10, false, false).first().size)
    }

    @Test
    fun duplicateMailboxAddressAndLabelAreRejectedByTheSchema() = runBlocking<Unit> {
        val first = db.accounts().insert(account("dup"))
        assertFailsWith<SQLiteConstraintException> { db.accounts().insert(account("dup")) }
        db.identities().insert(identity(first, "same@example.test", "me"))
        val other = db.accounts().insert(account("other"))
        assertFailsWith<SQLiteConstraintException> { db.identities().insert(identity(other, "SAME@example.test", "x")) }
        assertFailsWith<SQLiteConstraintException> { db.identities().insert(identity(other, "different@example.test", "ME")) }
    }

    @Test
    fun sameMessageInTwoAccountsStaysAsTwoCopiesButUnifiedViewDoesNotMergeThem() = runBlocking<Unit> {
        val a = db.accounts().insert(account("a"))
        val b = db.accounts().insert(account("b"))
        val folderA = db.folders().insert(folder(a))
        val folderB = db.folders().insert(folder(b))
        db.messages().upsert(message(a, folderA, 1).copy(messageId = "<same@x>"))
        db.messages().upsert(message(b, folderB, 1).copy(messageId = "<same@x>"))
        assertEquals(2, db.messages().observeUnified(FolderRole.INBOX, 10, false, false).first().size)
    }

    @Test
    fun theSameMessageIdInOneAccountAppearsOnceInTheUnifiedView() = runBlocking<Unit> {
        val a = db.accounts().insert(account("a"))
        val inbox = db.folders().insert(folder(a))
        val spam = db.folders().insert(folder(a).copy(path = "Other", role = FolderRole.INBOX))
        db.messages().upsert(message(a, inbox, 1).copy(messageId = "<same@x>"))
        db.messages().upsert(message(a, spam, 7).copy(messageId = "<same@x>"))
        assertEquals(1, db.messages().observeUnified(FolderRole.INBOX, 10, false, false).first().size)
    }
}
