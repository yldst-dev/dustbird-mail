package app.dustbird.mail

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.dustbird.mail.core.folder.FolderInfo
import app.dustbird.mail.core.folder.FolderRoles
import app.dustbird.mail.core.folder.RoleResolution
import app.dustbird.mail.core.model.EmailAddress
import app.dustbird.mail.core.model.FolderRole
import app.dustbird.mail.core.model.ServerEndpoint
import app.dustbird.mail.core.model.TransportSecurity
import app.dustbird.mail.core.send.SendState
import app.dustbird.mail.data.db.MailDatabase
import app.dustbird.mail.data.repo.AddAccountResult
import app.dustbird.mail.data.repo.AttachmentResult
import app.dustbird.mail.data.repo.DraftContent
import app.dustbird.mail.data.repo.MailboxScope
import app.dustbird.mail.data.repo.MessageFilter
import app.dustbird.mail.data.repo.MoveTarget
import app.dustbird.mail.data.repo.NewAccount
import app.dustbird.mail.sync.EnqueueResult
import app.dustbird.mail.sync.NoopNotifier
import app.dustbird.mail.sync.SyncOutcome
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Properties
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.Session
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MailActionsEndToEndTest {
    private val imap = ServerEndpoint("10.0.2.2", 3993, TransportSecurity.TLS)
    private val smtp = ServerEndpoint("10.0.2.2", 3465, TransportSecurity.TLS)
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var container: AppContainer
    private lateinit var db: MailDatabase
    private val created = mutableListOf<Long>()
    private var tester = 0L
    private var friend = 0L

    @Before
    fun setUp() = runBlocking<Unit> {
        assumeTrue("host test server is not running", runCatching { Socket().use { it.connect(InetSocketAddress("10.0.2.2", 3993), 2000) } }.isSuccess)
        db = Room.inMemoryDatabaseBuilder(context, MailDatabase::class.java).build()
        container = AppContainer(context, db = db, notifierFactory = { NoopNotifier })
        prepareMailbox("tester@example.test", "tester-pw")
        prepareMailbox("friend@example.test", "friend-pw")
        tester = addAccount("tester@example.test", "tester-pw", "tester")
        friend = addAccount("friend@example.test", "friend-pw", "friend")
    }

    @After
    fun tearDown() = runBlocking<Unit> {
        if (::container.isInitialized) {
            created.forEach { container.accounts.removeAccount(it, force = true) }
            db.close()
        }
    }

    private fun prepareMailbox(user: String, password: String) {
        val store = Session.getInstance(Properties().apply { put("mail.imap.ssl.enable", "true") }).getStore("imap")
        store.connect("10.0.2.2", 3993, user, password)
        listOf("INBOX", "Sent Messages", "Drafts", "Trash", "Archive").forEach { name ->
            val folder = store.getFolder(name)
            if (!folder.exists()) folder.create(Folder.HOLDS_MESSAGES)
            folder.open(Folder.READ_WRITE)
            folder.messages.forEach { it.setFlag(Flags.Flag.DELETED, true) }
            folder.close(true)
        }
        store.close()
    }

    private suspend fun addAccount(address: String, password: String, label: String): Long {
        val id = assertIs<AddAccountResult.Added>(
            container.accounts.addAccount(NewAccount("custom", label, label, address, address, label, imap, smtp), password),
        ).accountId
        created += id
        assertEquals(SyncOutcome.Synced, container.sync.syncAccount(id))
        val infos = db.folders().forAccount(id).map { FolderInfo(it.path, it.delimiter?.firstOrNull(), it.attributes.split(" ").filter(String::isNotEmpty).toSet()) }
        FolderRoles.resolve(infos, emptyMap()).forEach { (role, resolution) ->
            if (resolution is RoleResolution.NeedsConfirmation) container.sync.confirmRole(id, role, resolution.candidates.first())
        }
        assertEquals(SyncOutcome.Synced, container.sync.syncAccount(id))
        return id
    }

    private suspend fun identity(accountId: Long) = container.accounts.identities.first().first { it.accountId == accountId }

    private suspend fun deliver(subject: String, body: String = "본문", attachment: File? = null, inReplyTo: String? = null, from: Long = friend, to: String = "tester@example.test") {
        val draftId = container.drafts.save(DraftContent(null, identity(from).id, listOf(EmailAddress(to)), emptyList(), emptyList(), subject, body, inReplyTo, listOfNotNull(inReplyTo)))
        if (attachment != null) assertTrue(container.drafts.addAttachment(draftId, attachment, "첨부 자료.bin", "application/octet-stream"))
        val queued = assertIs<EnqueueResult.Queued>(container.send.enqueue(draftId))
        container.send.processAccount(from)
        assertEquals(SendState.SAVED, db.outbox().get(queued.outboxId)!!.state)
    }

    private suspend fun inbox() = container.mail.messages(MailboxScope.Account(tester, FolderRole.INBOX), 50).first()

    private suspend fun folder(role: FolderRole) = container.mail.messages(MailboxScope.Account(tester, role), 50).first()

    @Test
    fun starArchiveDeleteUndoSearchAndAnswerThroughRealServerCode() = runBlocking<Unit> {
        deliver("분기 보고서 검토")
        deliver("점심 약속")
        deliver("회신 부탁드립니다")
        assertEquals(SyncOutcome.Synced, container.sync.syncAccount(tester))
        assertEquals(3, inbox().size)
        val report = inbox().first { it.subject == "분기 보고서 검토" }
        val lunch = inbox().first { it.subject == "점심 약속" }
        val ask = inbox().first { it.subject == "회신 부탁드립니다" }

        assertTrue(container.mail.setFlagged(listOf(report.id), true))
        assertEquals(SyncOutcome.Synced, container.sync.syncAccount(tester))
        assertTrue(inbox().first { it.id == report.id }.flagged)
        assertEquals(listOf(report.id), container.mail.messages(MailboxScope.Account(tester, FolderRole.INBOX), 50, MessageFilter.FLAGGED).first().map { it.id })

        assertEquals(1, container.actions.run(listOf(report.id), MoveTarget.Role(FolderRole.ARCHIVE)))
        assertEquals(SyncOutcome.Synced, container.sync.syncAccount(tester))
        assertEquals(setOf("점심 약속", "회신 부탁드립니다"), inbox().map { it.subject }.toSet())
        val archived = folder(FolderRole.ARCHIVE).single()
        assertEquals("분기 보고서 검토", archived.subject)
        assertTrue(archived.flagged)

        val ticket = assertNotNull(container.actions.start(listOf(lunch.id), MoveTarget.Role(FolderRole.TRASH)))
        assertEquals(1, ticket.count)
        assertEquals(listOf("회신 부탁드립니다"), inbox().map { it.subject })
        container.actions.undo(ticket)
        withTimeout(5_000) { while (inbox().size != 2) delay(50) }
        assertEquals(SyncOutcome.Synced, container.sync.syncAccount(tester))
        assertEquals(2, inbox().size)
        assertTrue(folder(FolderRole.TRASH).isEmpty())

        assertFalse(container.actions.deletesPermanently(listOf(lunch.id)))
        assertTrue(container.actions.movesToFolder(listOf(lunch.id), MoveTarget.Role(FolderRole.TRASH)))
        assertTrue(container.actions.movesToFolder(listOf(lunch.id), MoveTarget.Role(FolderRole.ARCHIVE)))
        assertEquals(1, container.actions.run(listOf(lunch.id), MoveTarget.Role(FolderRole.TRASH)))
        assertEquals(SyncOutcome.Synced, container.sync.syncAccount(tester))
        val trashed = folder(FolderRole.TRASH).single()
        assertEquals("점심 약속", trashed.subject)
        assertTrue(container.actions.deletesPermanently(listOf(trashed.id)))
        assertFalse(container.actions.movesToFolder(listOf(trashed.id), MoveTarget.Role(FolderRole.TRASH)))
        assertEquals(1, container.actions.run(listOf(trashed.id), MoveTarget.Role(FolderRole.TRASH)))
        assertEquals(SyncOutcome.Synced, container.sync.syncAccount(tester))
        assertTrue(folder(FolderRole.TRASH).isEmpty())

        assertEquals(listOf("분기 보고서 검토"), container.mail.searchRows("보고서", tester, 10).map { it.subject })
        assertEquals(0, container.mail.searchServer("보고서", listOf(tester)).failedAccounts)

        val original = db.messages().get(ask.id)!!
        deliver("Re: 회신 부탁드립니다", inReplyTo = original.messageId, from = tester, to = "friend@example.test")
        assertTrue(db.messages().get(ask.id)!!.answered)
        assertEquals(SyncOutcome.Synced, container.sync.syncAccount(tester))
        assertTrue(inbox().first { it.id == ask.id }.answered)
    }

    @Test
    fun attachmentsAreSentReceivedAndDownloadedIntact() = runBlocking<Unit> {
        val bytes = ByteArray(300_000) { (it % 251).toByte() }
        val source = File(context.cacheDir, "e2e-attachment.bin").apply { writeBytes(bytes) }
        deliver("첨부 보냅니다", attachment = source)
        assertEquals(SyncOutcome.Synced, container.sync.syncAccount(tester))
        val message = inbox().single()
        assertTrue(message.hasAttachment)
        val result = assertIs<AttachmentResult.Ready>(container.mail.attachment(message.id, 0))
        assertEquals("첨부 자료.bin", result.info.fileName)
        assertContentEquals(bytes, result.file.readBytes())
        assertTrue(result.file.absolutePath.startsWith(container.files.downloads.absolutePath))
        assertIs<AttachmentResult.Failed>(container.mail.attachment(message.id, 5))
        val draftId = container.drafts.save(DraftContent(null, identity(tester).id, emptyList(), emptyList(), emptyList(), "", "", null, emptyList()))
        assertFalse(container.drafts.addAttachment(draftId, container.files.shareUri(result.file)))
        source.delete()
    }
}
