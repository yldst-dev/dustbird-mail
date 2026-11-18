package app.dustbird.mail.data.repo

import app.dustbird.mail.core.mime.MimeComposer
import app.dustbird.mail.core.mime.OutgoingMessage
import app.dustbird.mail.core.model.EmailAddress
import app.dustbird.mail.core.model.FolderRole
import app.dustbird.mail.core.send.MessageIds
import app.dustbird.mail.data.Codec
import app.dustbird.mail.data.db.DraftEntity
import app.dustbird.mail.data.db.IdentityEntity
import app.dustbird.mail.data.db.MailDatabase
import app.dustbird.mail.sync.ImapAccess
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.File
import app.dustbird.mail.data.db.DraftAttachmentEntity
import app.dustbird.mail.data.ImportedFile
import app.dustbird.mail.data.AttachmentFiles
import app.dustbird.mail.core.mime.OutgoingAttachment
import android.net.Uri

data class DraftContent(
    val id: Long?,
    val identityId: Long,
    val to: List<EmailAddress>,
    val cc: List<EmailAddress>,
    val bcc: List<EmailAddress>,
    val subject: String,
    val body: String,
    val inReplyTo: String?,
    val references: List<String>,
)

class DraftRepository(
    private val db: MailDatabase,
    private val access: ImapAccess,
    private val files: AttachmentFiles,
) {
    val drafts: Flow<List<DraftEntity>> = db.drafts().observeAll()

    fun attachments(draftId: Long): Flow<List<DraftAttachmentEntity>> = db.draftAttachments().observe(draftId)

    suspend fun addAttachment(draftId: Long, uri: Uri): Boolean =
        store(draftId) { remaining -> files.import(draftId, uri, remaining) }

    suspend fun addAttachment(draftId: Long, source: File, fileName: String?, mimeType: String): Boolean =
        store(draftId) { remaining -> files.importFile(draftId, source, fileName, mimeType, remaining) }

    private suspend fun store(draftId: Long, import: (Long) -> ImportedFile?): Boolean {
        val remaining = AttachmentFiles.MAX_TOTAL_BYTES - db.draftAttachments().forDraft(draftId).sumOf { it.size }
        val imported = withContext(Dispatchers.IO) { import(remaining) } ?: return false
        db.draftAttachments().insert(
            DraftAttachmentEntity(draftId = draftId, fileName = imported.fileName, mimeType = imported.mimeType, size = imported.size, path = imported.path),
        )
        return true
    }

    suspend fun removeAttachment(id: Long) {
        val row = db.draftAttachments().get(id) ?: return
        db.draftAttachments().delete(id)
        File(row.path).delete()
    }

    suspend fun outgoingAttachments(draftId: Long): List<OutgoingAttachment> =
        db.draftAttachments().forDraft(draftId).map { OutgoingAttachment(it.fileName, it.mimeType, File(it.path)) }

    suspend fun save(content: DraftContent): Long {
        val identity = db.identities().get(content.identityId) ?: error("Unknown identity ${content.identityId}")
        val existing = content.id?.let { db.drafts().get(it) }
        val entity = DraftEntity(
            id = existing?.id ?: 0,
            accountId = identity.accountId,
            identityId = identity.id,
            toAddresses = Codec.addresses(content.to),
            ccAddresses = Codec.addresses(content.cc),
            bccAddresses = Codec.addresses(content.bcc),
            subject = content.subject,
            body = content.body,
            inReplyTo = content.inReplyTo,
            references = Codec.strings(content.references),
            messageId = existing?.messageId ?: MessageIds.generate(identity.address),
            serverCopyAccountId = existing?.serverCopyAccountId,
            updatedAt = System.currentTimeMillis(),
        )
        if (existing == null) return db.drafts().insert(entity)
        db.drafts().update(entity)
        return existing.id
    }

    suspend fun load(id: Long): DraftContent? = db.drafts().get(id)?.let {
        DraftContent(
            id = it.id,
            identityId = it.identityId,
            to = Codec.parseAddresses(it.toAddresses),
            cc = Codec.parseAddresses(it.ccAddresses),
            bcc = Codec.parseAddresses(it.bccAddresses),
            subject = it.subject,
            body = it.body,
            inReplyTo = it.inReplyTo,
            references = Codec.parseStrings(it.references),
        )
    }

    suspend fun syncToServer(id: Long): Boolean {
        val draft = db.drafts().get(id) ?: return false
        val identity = db.identities().get(draft.identityId) ?: return false
        val previousAccount = draft.serverCopyAccountId
        if (previousAccount != null && previousAccount != draft.accountId) removeServerCopy(previousAccount, draft.messageId)
        val folder = db.folders().byRole(draft.accountId, FolderRole.DRAFTS) ?: return false
        val attachments = outgoingAttachments(id)
        val composed = withContext(Dispatchers.IO) { MimeComposer.composeDraft(outgoing(draft, identity).copy(attachments = attachments)) }
        return try {
            access.use(draft.accountId) { session, _ ->
                session.open(folder.path)
                session.removeByMessageId(folder.path, draft.messageId)
                session.append(folder.path, composed.rawWithBcc, seen = true, draft = true)
            }
            db.drafts().setServerCopy(id, draft.accountId)
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun delete(id: Long) {
        val draft = db.drafts().get(id) ?: return
        draft.serverCopyAccountId?.let { removeServerCopy(it, draft.messageId) }
        db.drafts().delete(id)
        files.clearDraft(id)
    }

    suspend fun discardLocalOnly(id: Long) {
        db.drafts().delete(id)
        files.clearDraft(id)
    }

    suspend fun removeServerCopy(accountId: Long, messageId: String) {
        val folder = db.folders().byRole(accountId, FolderRole.DRAFTS) ?: return
        try {
            access.use(accountId) { session, _ ->
                session.open(folder.path)
                session.removeByMessageId(folder.path, messageId)
            }
        } catch (_: Exception) {
        }
    }

    private fun outgoing(draft: DraftEntity, identity: IdentityEntity) = OutgoingMessage(
        from = EmailAddress(identity.address, identity.displayName.ifBlank { null }),
        to = Codec.parseAddresses(draft.toAddresses),
        cc = Codec.parseAddresses(draft.ccAddresses),
        bcc = Codec.parseAddresses(draft.bccAddresses),
        subject = draft.subject,
        text = draft.body,
        messageId = draft.messageId,
        inReplyTo = draft.inReplyTo,
        references = Codec.parseStrings(draft.references),
        sentAt = Instant.now(),
    )
}
