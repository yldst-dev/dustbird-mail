package app.dustbird.mail.data.repo

import app.dustbird.mail.core.identity.DraftSeed
import app.dustbird.mail.core.identity.OriginalMessage
import app.dustbird.mail.core.identity.ReceivedAddressing
import app.dustbird.mail.core.identity.RecipientMatch
import app.dustbird.mail.core.identity.RecipientMatcher
import app.dustbird.mail.core.identity.ReplyComposer
import app.dustbird.mail.core.mime.AttachmentInfo
import app.dustbird.mail.core.mime.MessageParser
import app.dustbird.mail.core.model.EmailAddress
import app.dustbird.mail.core.model.FolderRole
import app.dustbird.mail.core.model.MailIdentity
import app.dustbird.mail.core.net.ConnectFailure
import app.dustbird.mail.core.net.ConnectFailures
import app.dustbird.mail.data.Codec
import app.dustbird.mail.data.db.MailDatabase
import app.dustbird.mail.data.db.MessageBodyEntity
import app.dustbird.mail.data.db.MessageEntity
import app.dustbird.mail.data.db.PendingFlagEntity
import app.dustbird.mail.data.toModel
import app.dustbird.mail.sync.ImapAccess
import app.dustbird.mail.sync.MissingCredentialException
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

sealed interface MailboxScope {
    data object Unified : MailboxScope
    data class Account(val accountId: Long, val role: FolderRole) : MailboxScope
}

data class MessageSummary(
    val id: Long,
    val accountId: Long,
    val subject: String,
    val fromName: String?,
    val fromAddress: String?,
    val preview: String,
    val sortDate: Long,
    val seen: Boolean,
    val hasAttachment: Boolean,
    val match: RecipientMatch,
)

data class MessageDetail(
    val summary: MessageSummary,
    val to: List<EmailAddress>,
    val cc: List<EmailAddress>,
    val replyTo: List<EmailAddress>,
    val messageId: String?,
    val inReplyTo: String?,
    val references: List<String>,
    val allowRemoteImages: Boolean,
    val folderRole: FolderRole?,
)

sealed interface BodyResult {
    data class Loaded(val text: String?, val html: String?, val attachments: List<AttachmentInfo>) : BodyResult
    data class Failed(val failure: ConnectFailure?) : BodyResult
}

class MailRepository(
    private val db: MailDatabase,
    private val accounts: AccountRepository,
    private val access: ImapAccess,
) {
    fun messages(scope: MailboxScope, limit: Int): Flow<List<MessageSummary>> {
        val rows = when (scope) {
            MailboxScope.Unified -> db.messages().observeUnified(FolderRole.INBOX, limit)
            is MailboxScope.Account -> db.messages().observeByAccountAndRole(scope.accountId, scope.role, limit)
        }
        return combine(rows, accounts.identities) { list, identities -> list.map { it.toSummary(identities) } }
    }

    fun detail(id: Long): Flow<MessageDetail?> = combine(db.messages().observe(id), accounts.identities) { row, identities ->
        row?.let { entity ->
            MessageDetail(
                summary = entity.toSummary(identities),
                to = Codec.parseAddresses(entity.toAddresses),
                cc = Codec.parseAddresses(entity.ccAddresses),
                replyTo = Codec.parseAddresses(entity.replyTo),
                messageId = entity.messageId,
                inReplyTo = entity.inReplyTo,
                references = Codec.parseStrings(entity.references),
                allowRemoteImages = entity.allowRemoteImages,
                folderRole = null,
            )
        }
    }

    fun unreadCounts(): Flow<Map<Long, Int>> = db.messages().observeUnreadCounts().map { rows -> rows.associate { it.accountId to it.count } }

    suspend fun body(id: Long): BodyResult {
        db.messages().body(id)?.let { return BodyResult.Loaded(it.text, it.html, Codec.parseAttachments(it.attachments)) }
        val message = db.messages().get(id) ?: return BodyResult.Failed(null)
        val folder = db.folders().get(message.folderId) ?: return BodyResult.Failed(null)
        return try {
            val content = access.use(message.accountId) { session, _ -> session.fetchContent(folder.path, message.uid) }
                ?: return BodyResult.Failed(null)
            db.messages().upsertBody(MessageBodyEntity(id, content.text, content.html, Codec.attachments(content.attachments)))
            BodyResult.Loaded(content.text, content.html, content.attachments)
        } catch (_: MissingCredentialException) {
            BodyResult.Failed(ConnectFailure.AUTH)
        } catch (error: Exception) {
            BodyResult.Failed(ConnectFailures.classify(error))
        }
    }

    suspend fun setSeen(id: Long, seen: Boolean) {
        val message = db.messages().get(id) ?: return
        if (message.seen == seen) return
        db.withTransaction {
            db.messages().setSeen(id, seen)
            db.pendingFlags().upsert(PendingFlagEntity(accountId = message.accountId, folderId = message.folderId, uid = message.uid, seen = seen))
        }
    }

    suspend fun allowImages(id: Long) = db.messages().allowImages(id)

    suspend fun replySeed(id: Long, replyAll: Boolean, attribution: String): DraftSeed? {
        val original = original(id) ?: return null
        return ReplyComposer.reply(original, accounts.identities.first(), replyAll, attribution)
    }

    suspend fun forwardSeed(id: Long, header: String): DraftSeed? {
        val original = original(id) ?: return null
        return ReplyComposer.forward(original, accounts.identities.first(), header)
    }

    private suspend fun original(id: Long): OriginalMessage? {
        val message = db.messages().get(id) ?: return null
        val identities = accounts.identities.first().filter { it.accountId == message.accountId }
        val body = body(id)
        val text = (body as? BodyResult.Loaded)?.let { MessageParser.plainText(app.dustbird.mail.core.mime.ParsedContent(it.text, it.html, it.attachments)) }.orEmpty()
        val match = match(message, identities)
        val folderRole = db.folders().get(message.folderId)?.role
        return OriginalMessage(
            accountId = message.accountId,
            messageId = message.messageId,
            references = Codec.parseStrings(message.references),
            from = message.fromAddress?.let { EmailAddress(it, message.fromName) },
            replyTo = Codec.parseAddresses(message.replyTo),
            to = Codec.parseAddresses(message.toAddresses),
            cc = Codec.parseAddresses(message.ccAddresses),
            subject = message.subject,
            text = text,
            matchedIdentityIds = (match as? RecipientMatch.Matched)?.identityIds.orEmpty(),
            sentByMe = folderRole == FolderRole.SENT || folderRole == FolderRole.DRAFTS,
        )
    }

    private fun MessageEntity.toSummary(identities: List<MailIdentity>) = MessageSummary(
        id = id,
        accountId = accountId,
        subject = subject,
        fromName = fromName,
        fromAddress = fromAddress,
        preview = preview,
        sortDate = sortDate,
        seen = seen,
        hasAttachment = hasAttachment,
        match = match(this, identities.filter { it.accountId == accountId }),
    )

    private fun match(message: MessageEntity, accountIdentities: List<MailIdentity>): RecipientMatch = RecipientMatcher.match(
        ReceivedAddressing(
            Codec.parseAddresses(message.toAddresses),
            Codec.parseAddresses(message.ccAddresses),
            Codec.parseStrings(message.deliveryValues),
        ),
        accountIdentities,
    )
}
