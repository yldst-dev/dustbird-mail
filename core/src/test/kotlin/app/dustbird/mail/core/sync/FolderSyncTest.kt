package app.dustbird.mail.core.sync

import app.dustbird.mail.core.model.Credentials
import app.dustbird.mail.core.model.ServerEndpoint
import app.dustbird.mail.core.model.TransportSecurity
import app.dustbird.mail.core.net.FetchedMessage
import app.dustbird.mail.core.net.JakartaImapConnector
import app.dustbird.mail.core.net.JakartaSmtpSender
import app.dustbird.mail.core.net.SmtpOutcome
import app.dustbird.mail.core.testing.TestMail
import app.dustbird.mail.core.testing.TestMailServer
import java.util.Properties
import java.util.TreeMap
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.Session
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class InMemoryFolderStore : FolderStore {
    var cursor: FolderCursor? = null
    val rows = TreeMap<Long, LocalFlags>()
    val subjects = HashMap<Long, String>()
    val pending = LinkedHashMap<Long, Boolean>()
    var resets = 0

    override fun cursor() = cursor
    override fun reset(uidValidity: Long) {
        rows.clear(); subjects.clear(); pending.clear(); resets++
        cursor = FolderCursor(uidValidity, 0, false)
    }
    override fun cachedFlags(limit: Int): Map<Long, LocalFlags> = rows.descendingMap().entries.take(limit).associate { it.key to it.value }
    override fun oldestUid(): Long = if (rows.isEmpty()) 0 else rows.firstKey()
    override fun pendingSeen(): Map<Long, Boolean> = LinkedHashMap(pending)
    override fun clearPending(uid: Long) { pending.remove(uid) }
    override fun insert(messages: List<FetchedMessage>) = messages.forEach {
        rows[it.uid] = LocalFlags(it.flags.seen, it.flags.answered, it.flags.flagged)
        subjects[it.uid] = it.headers.subject
    }
    override fun updateFlags(uid: Long, flags: LocalFlags) { rows[uid] = flags }
    override fun remove(uids: List<Long>) = uids.forEach { rows.remove(it); subjects.remove(it) }
    override fun saveCursor(cursor: FolderCursor) { this.cursor = cursor }
}

class FolderSyncTest {
    private lateinit var server: TestMailServer
    private val alice = Credentials("alice@example.test", "alice-pw")
    private val bob = Credentials("bob@example.test", "bob-pw")
    private val store = InMemoryFolderStore()

    @BeforeTest
    fun start() {
        server = TestMailServer.start(mapOf(alice.username to alice.secret, bob.username to bob.secret))
    }

    @AfterTest
    fun stop() = server.close()

    private val imap get() = ServerEndpoint(server.host, server.imapPort, TransportSecurity.TLS)
    private val smtp get() = ServerEndpoint(server.host, server.smtpPort, TransportSecurity.TLS)

    private fun deliver(count: Int, from: Int = 1) = (from until from + count).forEach {
        assertIs<SmtpOutcome.Accepted>(JakartaSmtpSender.send(smtp, bob, TestMail.compose("<m$it@example.test>", subject = "메일 $it")))
    }

    private fun runSync(pageSize: Int = 50): FolderSyncResult = JakartaImapConnector.connect(imap, alice).use {
        FolderSync(pageSize).sync(it, "INBOX", store, listOf("Delivered-To"))
    }

    private fun serverExpunge(vararg sequence: Int) {
        val session = Session.getInstance(Properties().apply { put("mail.imap.ssl.enable", "true") })
        val mail = session.getStore("imap")
        mail.connect(server.host, server.imapPort, alice.username, alice.secret)
        val inbox = mail.getFolder("INBOX")
        inbox.open(Folder.READ_WRITE)
        sequence.forEach { inbox.getMessage(it).setFlag(Flags.Flag.DELETED, true) }
        inbox.close(true)
        mail.close()
    }

    @Test
    fun firstSyncStoresOnlyTheNewestPageAndNotifiesNothing() {
        deliver(5)
        val result = runSync(pageSize = 3)
        assertEquals(3, result.inserted.size)
        assertTrue(result.notifiable.isEmpty())
        assertFalse(result.reset)
        assertEquals(listOf("메일 3", "메일 4", "메일 5"), store.rows.keys.map { store.subjects.getValue(it) })
        val cursor = store.cursor!!
        assertTrue(cursor.baselineComplete)
        assertEquals(store.rows.lastKey(), cursor.highestUid)
    }

    @Test
    fun laterSyncFetchesOnlyNewMessagesAndMarksThemNotifiable() {
        deliver(2)
        runSync()
        deliver(2, from = 3)
        val second = runSync()
        assertEquals(listOf("메일 3", "메일 4"), second.inserted.map { it.headers.subject })
        assertEquals(second.inserted, second.notifiable)
        assertEquals(4, store.rows.size)
        val third = runSync()
        assertTrue(third.inserted.isEmpty())
    }

    @Test
    fun olderPagesStillLoadAfterEveryCachedMessageWasMovedAway() {
        deliver(5)
        runSync(pageSize = 2)
        serverExpunge(4, 5)
        store.remove(store.rows.keys.toList())
        val loader = FolderSync(pageSize = 2)
        assertEquals(2, JakartaImapConnector.connect(imap, alice).use { loader.loadOlder(it, "INBOX", store, emptyList()) })
        assertEquals(listOf("메일 2", "메일 3"), store.subjects.toSortedMap().values.toList())
        assertEquals(0, JakartaImapConnector.connect(imap, alice).use { FolderSync().loadOlder(it, "INBOX", InMemoryFolderStore(), emptyList()) })
    }

    @Test
    fun olderPagesAreLoadedUntilTheStartOfTheFolder() {
        deliver(5)
        runSync(pageSize = 2)
        val loader = FolderSync(pageSize = 2)
        val loaded = JakartaImapConnector.connect(imap, alice).use { loader.loadOlder(it, "INBOX", store, emptyList()) }
        assertEquals(2, loaded)
        val rest = JakartaImapConnector.connect(imap, alice).use { loader.loadOlder(it, "INBOX", store, emptyList()) }
        assertEquals(1, rest)
        assertEquals(0, JakartaImapConnector.connect(imap, alice).use { loader.loadOlder(it, "INBOX", store, emptyList()) })
        assertEquals(5, store.rows.size)
    }

    @Test
    fun serverSideFlagChangesAreMirroredLocally() {
        deliver(2)
        runSync()
        val first = store.rows.firstKey()
        JakartaImapConnector.connect(imap, alice).use { it.open("INBOX"); it.setSeen("INBOX", first, true) }
        runSync()
        assertTrue(store.rows.getValue(first).seen)
    }

    @Test
    fun localReadChangesArePushedAndSurviveTheNextReconcile() {
        deliver(2)
        runSync()
        val first = store.rows.firstKey()
        store.rows[first] = LocalFlags(seen = true, answered = false, flagged = false)
        store.pending[first] = true
        runSync()
        assertTrue(store.pending.isEmpty())
        val serverSeen = JakartaImapConnector.connect(imap, alice).use { it.open("INBOX", true); it.fetchFlags("INBOX", listOf(first)).getValue(first).seen }
        assertTrue(serverSeen)
        assertTrue(store.rows.getValue(first).seen)
    }

    @Test
    fun messagesRemovedOnTheServerDisappearLocally() {
        deliver(3)
        runSync()
        serverExpunge(1, 3)
        val result = runSync()
        assertEquals(2, result.removed)
        assertEquals(listOf("메일 2"), store.rows.keys.map { store.subjects.getValue(it) })
    }

    @Test
    fun changedUidValidityDiscardsTheCacheAndRefetches() {
        deliver(2)
        runSync()
        val real = store.cursor!!
        store.cursor = real.copy(uidValidity = real.uidValidity + 7)
        store.pending[store.rows.firstKey()] = true
        val result = runSync()
        assertTrue(result.reset)
        assertEquals(1, store.resets - 1)
        assertEquals(2, store.rows.size)
        assertTrue(result.notifiable.isEmpty())
        assertTrue(store.pending.isEmpty())
        assertEquals(real.uidValidity, store.cursor!!.uidValidity)
    }

    @Test
    fun emptyFolderSyncsCleanly() {
        val result = runSync()
        assertTrue(result.inserted.isEmpty())
        assertTrue(store.cursor!!.baselineComplete)
    }
}
