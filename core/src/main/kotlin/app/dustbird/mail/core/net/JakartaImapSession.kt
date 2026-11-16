package app.dustbird.mail.core.net

import app.dustbird.mail.core.folder.FolderInfo
import app.dustbird.mail.core.mime.MessageParser
import app.dustbird.mail.core.mime.ParsedContent
import app.dustbird.mail.core.model.Credentials
import app.dustbird.mail.core.model.ServerEndpoint
import com.sun.mail.iap.Response
import com.sun.mail.imap.IMAPFolder
import com.sun.mail.imap.IMAPMessage
import com.sun.mail.imap.IMAPStore
import com.sun.mail.imap.protocol.BODY
import com.sun.mail.imap.protocol.FLAGS
import com.sun.mail.imap.protocol.FetchResponse
import com.sun.mail.imap.protocol.IMAPProtocol
import com.sun.mail.imap.protocol.IMAPResponse
import com.sun.mail.imap.protocol.INTERNALDATE
import com.sun.mail.imap.protocol.ListInfo
import com.sun.mail.imap.protocol.RFC822SIZE
import com.sun.mail.imap.protocol.UID
import java.time.Instant
import java.util.Properties
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.Session
import javax.mail.internet.MimeMessage
import javax.mail.search.HeaderTerm

object JakartaImapConnector : ImapConnector {
    override fun connect(endpoint: ServerEndpoint, credentials: Credentials): ImapSession {
        val session = Session.getInstance(SessionConfig.imap(endpoint))
        val store = session.getStore("imap") as IMAPStore
        store.connect(endpoint.host, endpoint.port, credentials.username, credentials.secret)
        return JakartaImapSession(store)
    }
}

class JakartaImapSession(private val store: IMAPStore) : ImapSession {
    private val folders = HashMap<String, IMAPFolder>()

    override fun capabilities(): Set<String> =
        listOf("IDLE", "UIDPLUS", "MOVE", "SPECIAL-USE", "LIST-EXTENDED", "CONDSTORE", "X-GM-EXT-1")
            .filter { store.hasCapability(it) }
            .toSet()

    override fun listFolders(): List<FolderInfo> {
        val root = store.defaultFolder as IMAPFolder
        val extended = store.hasCapability("SPECIAL-USE") && store.hasCapability("LIST-EXTENDED")
        val command = if (extended) "LIST \"\" \"*\" RETURN (SPECIAL-USE)" else "LIST \"\" \"*\""
        @Suppress("UNCHECKED_CAST")
        return root.doCommand { protocol ->
            val responses = protocol.command(command, null)
            val found = ArrayList<FolderInfo>()
            for (index in responses.indices) {
                val response = responses[index]
                if (response is IMAPResponse && response.keyEquals("LIST")) {
                    val info = ListInfo(response)
                    val delimiter = info.separator.takeIf { it != '\u0000' }
                    found += FolderInfo(info.name, delimiter, info.attrs.toSet())
                    responses[index] = null
                }
            }
            finish(protocol, responses)
            found
        } as List<FolderInfo>
    }

    override fun open(path: String, readOnly: Boolean): FolderStatus {
        val folder = folders.getOrPut(path) { store.getFolder(path) as IMAPFolder }
        if (folder.isOpen) folder.close(false)
        folder.open(if (readOnly) Folder.READ_ONLY else Folder.READ_WRITE)
        return FolderStatus(folder.uidValidity, folder.uidNext, folder.messageCount)
    }

    override fun listUids(path: String, fromUid: Long, toUid: Long?): List<Long> {
        val folder = ensureOpen(path)
        val range = "$fromUid:${toUid ?: "*"}"
        @Suppress("UNCHECKED_CAST")
        return folder.doCommand { protocol ->
            val responses = protocol.command("UID SEARCH UID $range", null)
            val found = ArrayList<Long>()
            for (index in responses.indices) {
                val response = responses[index]
                if (response is IMAPResponse && response.keyEquals("SEARCH")) {
                    while (true) {
                        val number = response.readLong()
                        if (number < 0) break
                        found += number
                    }
                    responses[index] = null
                }
            }
            finish(protocol, responses)
            found.filter { it >= fromUid && (toUid == null || it <= toUid) }.sorted()
        } as List<Long>
    }

    override fun fetchMessages(path: String, uids: List<Long>, deliveryHeaders: List<String>): List<app.dustbird.mail.core.net.FetchedMessage> {
        if (uids.isEmpty()) return emptyList()
        val folder = ensureOpen(path)
        val result = ArrayList<app.dustbird.mail.core.net.FetchedMessage>()
        uids.sorted().chunked(MAX_UIDS_PER_COMMAND).forEach { chunk ->
            val command = "UID FETCH ${uidSet(chunk)} (UID FLAGS INTERNALDATE RFC822.SIZE BODY.PEEK[]<0.$PREVIEW_BYTES>)"
            fetch(folder, command) { response ->
                val uid = (response.getItem(UID::class.java) as? UID)?.uid ?: return@fetch
                val body = (response.getItem(BODY::class.java) as? BODY)?.byteArray?.let { it.newBytes ?: ByteArray(0) } ?: ByteArray(0)
                val message = MessageParser.parseRaw(body)
                val content = MessageParser.content(message)
                val headers = MessageParser.headers(message, deliveryHeaders)
                result += FetchedMessage(
                    uid = uid,
                    flags = flagsOf(response.getItem(FLAGS::class.java) as? FLAGS),
                    internalDate = (response.getItem(INTERNALDATE::class.java) as? INTERNALDATE)?.date?.toInstant(),
                    size = (response.getItem(RFC822SIZE::class.java) as? RFC822SIZE)?.size?.toInt() ?: 0,
                    headers = headers,
                    preview = MessageParser.preview(content),
                    hasAttachment = content.attachments.isNotEmpty() || headers.contentType?.startsWith("multipart/mixed") == true,
                )
            }
        }
        return result.filter { it.uid in uids }.sortedBy { it.uid }
    }

    override fun fetchFlags(path: String, uids: List<Long>): Map<Long, MessageFlags> {
        if (uids.isEmpty()) return emptyMap()
        val folder = ensureOpen(path)
        val result = HashMap<Long, MessageFlags>()
        uids.sorted().chunked(MAX_UIDS_PER_COMMAND).forEach { chunk ->
            fetch(folder, "UID FETCH ${uidSet(chunk)} (UID FLAGS)") { response ->
                val uid = (response.getItem(UID::class.java) as? UID)?.uid ?: return@fetch
                result[uid] = flagsOf(response.getItem(FLAGS::class.java) as? FLAGS)
            }
        }
        return result.filterKeys { it in uids }
    }

    override fun fetchContent(path: String, uid: Long): ParsedContent? {
        val folder = ensureOpen(path)
        val message = folder.getMessageByUID(uid) as? IMAPMessage ?: return null
        return MessageParser.content(message)
    }

    override fun setSeen(path: String, uid: Long, seen: Boolean) {
        val folder = ensureOpen(path)
        folder.getMessageByUID(uid)?.setFlag(Flags.Flag.SEEN, seen)
    }

    override fun append(path: String, raw: ByteArray, seen: Boolean, draft: Boolean): Long? {
        val folder = folders.getOrPut(path) { store.getFolder(path) as IMAPFolder }
        val message: MimeMessage = MessageParser.parseRaw(raw)
        if (seen) message.setFlag(Flags.Flag.SEEN, true)
        if (draft) message.setFlag(Flags.Flag.DRAFT, true)
        val appended = folder.appendUIDMessages(arrayOf(message))
        return appended.firstOrNull()?.uid
    }

    override fun findByMessageId(path: String, messageId: String): List<Long> {
        val folder = ensureOpen(path)
        return folder.search(HeaderTerm("Message-ID", messageId)).map { folder.getUID(it) }
    }

    override fun removeByMessageId(path: String, messageId: String): Int {
        val folder = ensureOpen(path)
        val matches = folder.search(HeaderTerm("Message-ID", messageId))
        if (matches.isEmpty()) return 0
        folder.setFlags(matches, Flags(Flags.Flag.DELETED), true)
        if (store.hasCapability("UIDPLUS")) folder.expunge(matches)
        return matches.size
    }

    override fun idle(path: String, onChange: () -> Unit) {
        val folder = ensureOpen(path)
        folder.addMessageCountListener(object : javax.mail.event.MessageCountAdapter() {
            override fun messagesAdded(event: javax.mail.event.MessageCountEvent) = onChange()
            override fun messagesRemoved(event: javax.mail.event.MessageCountEvent) = onChange()
        })
        val watchdog = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "imap-idle-refresh").apply { isDaemon = true } }
        watchdog.scheduleWithFixedDelay(
            { runCatching { folder.doCommand { protocol -> protocol.noop(); null } } },
            IDLE_REFRESH_MINUTES,
            IDLE_REFRESH_MINUTES,
            TimeUnit.MINUTES,
        )
        try {
            while (store.isConnected) folder.idle(true)
        } finally {
            watchdog.shutdownNow()
        }
    }

    override fun close() {
        folders.values.forEach { runCatching { if (it.isOpen) it.close(false) } }
        runCatching { store.close() }
    }

    private fun ensureOpen(path: String): IMAPFolder {
        val folder = folders[path]
        if (folder == null || !folder.isOpen) open(path)
        return folders.getValue(path)
    }

    private fun fetch(folder: IMAPFolder, command: String, handle: (FetchResponse) -> Unit) {
        folder.doCommand { protocol ->
            val responses = protocol.command(command, null)
            for (index in responses.indices) {
                val response = responses[index]
                if (response is FetchResponse) {
                    handle(response)
                    responses[index] = null
                }
            }
            finish(protocol, responses)
            null
        }
    }

    private fun finish(protocol: IMAPProtocol, responses: Array<Response?>) {
        val last = responses.last { it != null }!!
        protocol.notifyResponseHandlers(responses)
        protocol.handleResult(last)
    }

    private fun flagsOf(flags: FLAGS?): MessageFlags = MessageFlags(
        seen = flags?.contains(Flags.Flag.SEEN) == true,
        answered = flags?.contains(Flags.Flag.ANSWERED) == true,
        flagged = flags?.contains(Flags.Flag.FLAGGED) == true,
        draft = flags?.contains(Flags.Flag.DRAFT) == true,
        deleted = flags?.contains(Flags.Flag.DELETED) == true,
    )

    companion object {
        private const val PREVIEW_BYTES = 16384
        private const val MAX_UIDS_PER_COMMAND = 100
        private const val IDLE_REFRESH_MINUTES = 25L

        fun uidSet(uids: List<Long>): String {
            val sorted = uids.sorted()
            val parts = ArrayList<String>()
            var start = sorted.first()
            var previous = start
            for (uid in sorted.drop(1)) {
                if (uid == previous + 1) {
                    previous = uid
                } else {
                    parts += if (start == previous) "$start" else "$start:$previous"
                    start = uid
                    previous = uid
                }
            }
            parts += if (start == previous) "$start" else "$start:$previous"
            return parts.joinToString(",")
        }
    }
}
