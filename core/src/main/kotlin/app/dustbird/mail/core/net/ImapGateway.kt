package app.dustbird.mail.core.net

import app.dustbird.mail.core.folder.FolderInfo
import app.dustbird.mail.core.mime.ParsedContent
import app.dustbird.mail.core.mime.ParsedHeaders
import app.dustbird.mail.core.model.Credentials
import app.dustbird.mail.core.model.ServerEndpoint
import java.time.Instant

data class FolderStatus(val uidValidity: Long, val uidNext: Long, val exists: Int)

data class MessageFlags(
    val seen: Boolean,
    val answered: Boolean,
    val flagged: Boolean,
    val draft: Boolean,
    val deleted: Boolean,
)

data class FetchedMessage(
    val uid: Long,
    val flags: MessageFlags,
    val internalDate: Instant?,
    val size: Int,
    val headers: ParsedHeaders,
    val preview: String,
    val hasAttachment: Boolean,
)

interface ImapSession : AutoCloseable {
    fun capabilities(): Set<String>
    fun listFolders(): List<FolderInfo>
    fun open(path: String, readOnly: Boolean = false): FolderStatus
    fun listUids(path: String, fromUid: Long, toUid: Long?): List<Long>
    fun fetchMessages(path: String, uids: List<Long>, deliveryHeaders: List<String>): List<FetchedMessage>
    fun fetchFlags(path: String, uids: List<Long>): Map<Long, MessageFlags>
    fun fetchContent(path: String, uid: Long): ParsedContent?
    fun setSeen(path: String, uid: Long, seen: Boolean)
    fun append(path: String, raw: ByteArray, seen: Boolean): Long?
    fun findByMessageId(path: String, messageId: String): List<Long>
    fun idle(path: String, onChange: () -> Unit)
}

fun interface ImapConnector {
    fun connect(endpoint: ServerEndpoint, credentials: Credentials): ImapSession
}
