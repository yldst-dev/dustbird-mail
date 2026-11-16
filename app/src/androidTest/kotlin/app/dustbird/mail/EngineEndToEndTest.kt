package app.dustbird.mail

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.dustbird.mail.core.folder.FolderRoles
import app.dustbird.mail.core.folder.RoleResolution
import app.dustbird.mail.core.identity.RecipientMatch
import app.dustbird.mail.core.model.EmailAddress
import app.dustbird.mail.core.model.FolderRole
import app.dustbird.mail.core.model.MailAccount
import app.dustbird.mail.core.model.SendVerification
import app.dustbird.mail.core.model.ServerEndpoint
import app.dustbird.mail.core.model.StageState
import app.dustbird.mail.core.model.TransportSecurity
import app.dustbird.mail.core.send.SendState
import app.dustbird.mail.data.db.MailDatabase
import app.dustbird.mail.data.repo.AddAccountResult
import app.dustbird.mail.data.repo.BodyResult
import app.dustbird.mail.data.repo.DraftContent
import app.dustbird.mail.data.repo.MailboxScope
import app.dustbird.mail.data.repo.NewAccount
import app.dustbird.mail.sync.EnqueueResult
import app.dustbird.mail.sync.MailNotifier
import app.dustbird.mail.sync.NewMail
import app.dustbird.mail.sync.SyncOutcome
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Properties
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.Session
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private class RecordingNotifier : MailNotifier {
    val batches = mutableListOf<Pair<Long, List<NewMail>>>()
    val reauth = mutableListOf<Long>()
    override fun notifyNew(account: MailAccount, mails: List<NewMail>) { batches += account.id to mails }
    override fun reauthRequired(account: MailAccount) { reauth += account.id }
    override fun cancel(accountId: Long) = Unit
    override fun prune(existingAccountIds: Set<Long>) = Unit
}

@RunWith(AndroidJUnit4::class)
class EngineEndToEndTest {
    private val imap = ServerEndpoint("10.0.2.2", 3993, TransportSecurity.TLS)
    private val smtp = ServerEndpoint("10.0.2.2", 3465, TransportSecurity.TLS)
    private val testerAddress = "tester@example.test"
    private val friendAddress = "friend@example.test"
    private lateinit var container: AppContainer
    private lateinit var db: MailDatabase
    private val notifier = RecordingNotifier()
    private val createdAccounts = mutableListOf<Long>()

    @Before
    fun setUp() {
        assumeTrue("host test server is not running", reachable())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, MailDatabase::class.java).build()
        container = AppContainer(context, db = db, notifierFactory = { notifier })
        prepareMailbox(testerAddress, "tester-pw")
        prepareMailbox(friendAddress, "friend-pw")
    }

    @After
    fun tearDown() = runBlocking {
        if (::container.isInitialized) {
            createdAccounts.forEach { container.accounts.removeAccount(it, force = true) }
            db.close()
        }
    }

    private fun reachable() = runCatching { Socket().use { it.connect(InetSocketAddress("10.0.2.2", 3993), 2000) } }.isSuccess

    private fun prepareMailbox(user: String, password: String) {
        val store = Session.getInstance(Properties().apply { put("mail.imap.ssl.enable", "true") }).getStore("imap")
        store.connect("10.0.2.2", 3993, user, password)
        listOf("INBOX", "Sent Messages", "Drafts").forEach { name ->
            val folder = store.getFolder(name)
            if (!folder.exists()) folder.create(Folder.HOLDS_MESSAGES)
            folder.open(Folder.READ_WRITE)
            folder.messages.forEach { it.setFlag(Flags.Flag.DELETED, true) }
            folder.close(true)
        }
        store.close()
    }

    private suspend fun addAccount(address: String, password: String, label: String): Long {
        val result = container.accounts.addAccount(
            NewAccount("custom", label, label, address, address, "$label 표시 이름", imap, smtp),
            password,
        )
        return assertIs<AddAccountResult.Added>(result).accountId.also { createdAccounts += it }
    }

    private suspend fun prepareRoles(accountId: Long) {
        assertEquals(SyncOutcome.Synced, container.sync.syncAccount(accountId))
        val folders = db.folders().forAccount(accountId)
        val infos = folders.map { app.dustbird.mail.core.folder.FolderInfo(it.path, it.delimiter?.firstOrNull(), it.attributes.split(" ").filter(String::isNotEmpty).toSet()) }
        val resolved = FolderRoles.resolve(infos, emptyMap())
        assertIs<RoleResolution.Assigned>(resolved.getValue(FolderRole.INBOX))
        val sent = assertIs<RoleResolution.NeedsConfirmation>(resolved.getValue(FolderRole.SENT)).candidates.single()
        val drafts = assertIs<RoleResolution.NeedsConfirmation>(resolved.getValue(FolderRole.DRAFTS)).candidates.single()
        container.sync.confirmRole(accountId, FolderRole.SENT, sent)
        container.sync.confirmRole(accountId, FolderRole.DRAFTS, drafts)
        assertEquals(SyncOutcome.Synced, container.sync.syncAccount(accountId))
    }

    private suspend fun identityOf(accountId: Long) = container.accounts.identities.first().first { it.accountId == accountId }

    private suspend fun send(identityId: Long, to: List<EmailAddress>, subject: String, body: String, inReplyTo: String? = null, references: List<String> = emptyList()): Long {
        val draftId = container.drafts.save(DraftContent(null, identityId, to, emptyList(), emptyList(), subject, body, inReplyTo, references))
        val queued = assertIs<EnqueueResult.Queued>(container.send.enqueue(draftId))
        val accountId = container.accounts.identities.first().first { it.id == identityId }.accountId
        container.send.processAccount(accountId)
        return queued.outboxId
    }

    @Test
    fun accountsSendReceiveReplyAndSyncFlagsThroughRealServerCode() = runBlocking {
        val testerId = addAccount(testerAddress, "tester-pw", "tester")
        val friendId = addAccount(friendAddress, "friend-pw", "friend")
        prepareRoles(testerId)
        prepareRoles(friendId)
        notifier.batches.clear()

        val outboxId = send(identityOf(friendId).id, listOf(EmailAddress(testerAddress)), "한글 제목 안녕하세요", "본문입니다.\n둘째 줄")
        val sent = db.outbox().get(outboxId)!!
        assertEquals(SendState.SAVED, sent.state)
        assertEquals(SendVerification.VERIFIED, identityOf(friendId).verification)
        assertEquals(StageState.OK, container.accounts.accounts.first().first { it.id == friendId }.stages.send)

        assertEquals(SyncOutcome.Synced, container.sync.syncAccount(testerId))
        val received = container.mail.messages(MailboxScope.Unified, 50).first()
        assertEquals(1, received.size)
        val summary = received.single()
        assertEquals("한글 제목 안녕하세요", summary.subject)
        assertEquals(testerId, summary.accountId)
        assertEquals(RecipientMatch.Matched(listOf(identityOf(testerId).id)), summary.match)
        assertEquals(1, notifier.batches.single().second.size)
        assertEquals("tester", notifier.batches.single().second.single().identityLabel)

        container.sync.syncAccount(testerId)
        assertEquals(1, notifier.batches.size)

        val body = assertIs<BodyResult.Loaded>(container.mail.body(summary.id))
        assertTrue(body.text!!.contains("둘째 줄"))
        val detail = container.mail.detail(summary.id).first()!!

        val seed = assertNotNull(container.mail.replySeed(summary.id, replyAll = false, attribution = "친구가 썼습니다:"))
        assertEquals(identityOf(testerId).id, seed.identityId)
        assertEquals(listOf(friendAddress), seed.to.map { it.address })
        assertEquals("Re: 한글 제목 안녕하세요", seed.subject)
        assertEquals(detail.messageId, seed.inReplyTo)
        send(seed.identityId, seed.to, seed.subject, "답장입니다." + seed.body, seed.inReplyTo, seed.references)

        assertEquals(SyncOutcome.Synced, container.sync.syncAccount(friendId))
        val reply = container.mail.messages(MailboxScope.Account(friendId, FolderRole.INBOX), 10).first().single()
        assertEquals("Re: 한글 제목 안녕하세요", reply.subject)
        assertEquals(detail.messageId, container.mail.detail(reply.id).first()!!.inReplyTo)
        val friendSent = container.mail.messages(MailboxScope.Account(friendId, FolderRole.SENT), 10).first()
        assertEquals(1, friendSent.size)

        container.mail.setSeen(summary.id, true)
        container.sync.syncAccount(testerId)
        val folder = db.folders().byRole(testerId, FolderRole.INBOX)!!
        val message = db.messages().get(summary.id)!!
        val serverSeen = container.imapAccess.use(testerId) { session, _ ->
            session.open(folder.path, readOnly = true)
            session.fetchFlags(folder.path, listOf(message.uid)).getValue(message.uid).seen
        }
        assertTrue(serverSeen)
        assertTrue(db.messages().get(summary.id)!!.seen)
    }

    @Test
    fun oneAccountFailingAuthenticationDoesNotStopTheOthers() = runBlocking {
        val goodId = addAccount(testerAddress, "tester-pw", "tester")
        val badId = addAccount(friendAddress, "wrong-password", "friend")
        val results = container.sync.syncAll()
        assertEquals(SyncOutcome.Synced, results.getValue(goodId))
        assertEquals(SyncOutcome.NeedsReauth, results.getValue(badId))
        assertTrue(container.accounts.accounts.first().first { it.id == badId }.needsReauth)
        assertEquals(listOf(badId), notifier.reauth)
        container.accounts.reauthenticate(badId, "friend-pw", app.dustbird.mail.core.model.ConnectionStages())
        assertEquals(SyncOutcome.Synced, container.sync.syncAccount(badId))
    }

    @Test
    fun draftsGoToTheDraftsFolderOfTheSelectedIdentitysAccountAndMoveWithIt() = runBlocking {
        val testerId = addAccount(testerAddress, "tester-pw", "tester")
        val friendId = addAccount(friendAddress, "friend-pw", "friend")
        prepareRoles(testerId)
        prepareRoles(friendId)
        val draftId = container.drafts.save(DraftContent(null, identityOf(testerId).id, listOf(EmailAddress("someone@example.test")), emptyList(), emptyList(), "초안 제목", "초안 본문", null, emptyList()))
        assertTrue(container.drafts.syncToServer(draftId))
        container.sync.syncAccount(testerId)
        assertEquals(1, container.mail.messages(MailboxScope.Account(testerId, FolderRole.DRAFTS), 10).first().size)

        container.drafts.save(container.drafts.load(draftId)!!.copy(identityId = identityOf(friendId).id))
        assertTrue(container.drafts.syncToServer(draftId))
        container.sync.syncAccount(testerId)
        container.sync.syncAccount(friendId)
        assertEquals(0, container.mail.messages(MailboxScope.Account(testerId, FolderRole.DRAFTS), 10).first().size)
        assertEquals(1, container.mail.messages(MailboxScope.Account(friendId, FolderRole.DRAFTS), 10).first().size)
    }
}
