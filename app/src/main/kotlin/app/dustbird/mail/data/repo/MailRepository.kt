@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

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
import app.dustbird.mail.core.model.MailAccount
import app.dustbird.mail.core.model.MailIdentity
import app.dustbird.mail.core.net.ConnectFailure
import app.dustbird.mail.core.net.ConnectFailures
import app.dustbird.mail.data.Codec
import app.dustbird.mail.data.db.MailDatabase
import app.dustbird.mail.data.db.MessageBodyEntity
import app.dustbird.mail.data.db.MessageEntity
import app.dustbird.mail.data.db.PendingFlagEntity
import app.dustbird.mail.data.db.SentFrom
import app.dustbird.mail.data.toModel
import app.dustbird.mail.sync.ImapAccess
import app.dustbird.mail.sync.MissingCredentialException
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.CancellationException
import java.io.File
import app.dustbird.mail.sync.toEntity
import app.dustbird.mail.data.db.ListState
import app.dustbird.mail.data.db.FolderEntity
import app.dustbird.mail.data.AttachmentFiles
import app.dustbird.mail.core.provider.Providers
import app.dustbird.mail.core.net.MessageFlag
import app.dustbird.mail.core.identity.Addresses

sealed interface MailboxScope {
    data object Unified : MailboxScope
    data class Account(val accountId: Long, val role: FolderRole) : MailboxScope
    data class Folder(val accountId: Long, val folderId: Long) : MailboxScope
}

enum class MessageFilter { ALL, UNREAD, FLAGGED }

enum class SearchField { ALL, SENDER, RECIPIENT, SUBJECT }

data class SearchFilter(
    val since: Long? = null,
    val until: Long? = null,
    val accountId: Long? = null,
    val role: FolderRole? = null,
    val unread: Boolean = false,
    val starred: Boolean = false,
    val attachment: Boolean = false,
    val field: SearchField = SearchField.ALL,
) {
    val active: Boolean get() = this != SearchFilter()
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
    val flagged: Boolean = false,
    val answered: Boolean = false,
    val folderId: Long = 0,
    val digest: String? = null,
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

sealed interface AttachmentResult {
    data class Ready(val file: File, val info: AttachmentInfo) : AttachmentResult
    data class Failed(val failure: ConnectFailure?) : AttachmentResult
}

data class ServerSearchResult(val added: Int, val failedAccounts: Int, val hits: List<Long> = emptyList())

sealed interface BodyResult {
    data class Loaded(val text: String?, val html: String?, val attachments: List<AttachmentInfo>) : BodyResult
    data class Failed(val failure: ConnectFailure?) : BodyResult
}

class MailRepository(
    private val db: MailDatabase,
    private val accounts: AccountRepository,
    private val access: ImapAccess,
    private val files: AttachmentFiles,
    private val onSeenChanged: suspend (accountId: Long) -> Unit = {},
    digestsShown: Flow<Boolean> = flowOf(false),
) {
    private val digests: Flow<Map<Long, String>> = digestsShown.distinctUntilChanged().flatMapLatest { shown ->
        if (!shown) flowOf(emptyMap()) else db.digests().observeAll().map { rows -> rows.filter { it.text.isNotBlank() }.associate { it.messageRowId to it.text } }
    }

    fun messages(scope: MailboxScope, limit: Int, filter: MessageFilter = MessageFilter.ALL): Flow<List<MessageSummary>> {
        val unread = filter == MessageFilter.UNREAD
        val flagged = filter == MessageFilter.FLAGGED
        val rows = when (scope) {
            MailboxScope.Unified -> db.messages().observeUnified(FolderRole.INBOX, limit, unread, flagged)
            is MailboxScope.Account -> db.messages().observeByAccountAndRole(scope.accountId, scope.role, limit, unread, flagged)
            is MailboxScope.Folder -> db.messages().observeByFolder(scope.folderId, limit, unread, flagged)
        }
        return combine(rows, addressing, digests) { list, known, digest -> list.map { it.toSummary(known, digest[it.id]) } }
    }

    fun search(query: String, filter: SearchFilter = SearchFilter(), limit: Int = 200, serverHits: List<Long> = emptyList()): Flow<List<MessageSummary>> =
        combine(rows(query, filter, limit), addressing, digests) { list, known, digest ->
            withServerHits(list, serverHits, filter).map { it.toSummary(known, digest[it.id]) }
        }

    suspend fun searchRows(query: String, accountId: Long?, limit: Int, serverHits: List<Long> = emptyList()): List<MessageEntity> {
        val filter = SearchFilter(accountId = accountId)
        return withServerHits(rows(query, filter, limit).first(), serverHits, filter).take(limit)
    }

    private fun rows(query: String, filter: SearchFilter, limit: Int): Flow<List<MessageEntity>> {
        val field = filter.field
        return db.messages().search(
            pattern = likePattern(query),
            anyText = query.isBlank(),
            inSubject = field == SearchField.ALL || field == SearchField.SUBJECT,
            inSender = field == SearchField.ALL || field == SearchField.SENDER,
            inRecipient = field == SearchField.ALL || field == SearchField.RECIPIENT,
            inPreview = field == SearchField.ALL,
            accountId = filter.accountId ?: -1,
            role = filter.role,
            since = filter.since ?: Long.MIN_VALUE,
            until = filter.until ?: Long.MAX_VALUE,
            unread = filter.unread,
            starred = filter.starred,
            attachment = filter.attachment,
            limit = limit,
        )
    }

    private suspend fun withServerHits(local: List<MessageEntity>, serverHits: List<Long>, filter: SearchFilter): List<MessageEntity> {
        val extra = serverHits.chunked(500).flatMap { ids ->
            db.messages().filter(
                ids, filter.accountId ?: -1, filter.role, filter.since ?: Long.MIN_VALUE, filter.until ?: Long.MAX_VALUE,
                filter.unread, filter.starred, filter.attachment,
            )
        }
        return (local + extra)
            .distinctBy { it.accountId to (it.messageId ?: "row:${it.id}") }
            .sortedWith(compareByDescending<MessageEntity> { it.sortDate }.thenByDescending { it.id })
    }

    private fun likePattern(query: String): String =
        "%" + query.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

    suspend fun searchServer(query: String, accountIds: List<Long>, perFolder: Int = 50): ServerSearchResult {
        var added = 0
        var failed = 0
        val hits = ArrayList<Long>()
        for (accountId in accountIds) {
            val account = db.accounts().get(accountId) ?: continue
            val delivery = Providers.byId(account.providerId).deliveryHeaders
            val archive = if (db.folders().byRole(accountId, FolderRole.ARCHIVE) != null) FolderRole.ARCHIVE else FolderRole.ALL
            val folders = listOf(FolderRole.INBOX, archive, FolderRole.SENT)
                .mapNotNull { db.folders().byRole(accountId, it) }
                .filter { it.uidValidity != 0L }
            try {
                access.use(accountId) { session, _ ->
                    for (folder in folders) {
                        session.open(folder.path, readOnly = true)
                        val known = db.messages().uids(folder.id).toSet()
                        val found = session.search(folder.path, query).takeLast(perFolder)
                        val fetched = session.fetchMessages(folder.path, found.filter { it !in known }, delivery)
                        db.sync().insertMessages(
                            fetched.map { it.toEntity(accountId, folder.id, folder.uidValidity).copy(listState = ListState.SEARCH_ONLY) },
                        )
                        added += fetched.size
                        hits += db.messages().byUids(folder.id, found).map { it.id }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                failed++
            }
        }
        return ServerSearchResult(added, failed, hits)
    }

    suspend fun attachment(id: Long, index: Int): AttachmentResult {
        val info = (body(id) as? BodyResult.Loaded)?.attachments?.getOrNull(index) ?: return AttachmentResult.Failed(null)
        val target = files.downloadTarget(id, index, info.fileName)
        if (target.exists() && target.length() > 0) return AttachmentResult.Ready(target, info)
        val message = db.messages().get(id) ?: return AttachmentResult.Failed(null)
        val folder = db.folders().get(message.folderId) ?: return AttachmentResult.Failed(null)
        val temp = File(target.parentFile, target.name + ".part")
        return try {
            val found = access.use(message.accountId) { session, _ ->
                temp.outputStream().use { session.fetchAttachment(folder.path, message.uid, index, it) }
            }
            if (found && temp.renameTo(target)) AttachmentResult.Ready(target, info) else AttachmentResult.Failed(null)
        } catch (_: MissingCredentialException) {
            AttachmentResult.Failed(ConnectFailure.AUTH)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            AttachmentResult.Failed(ConnectFailures.classify(error))
        } finally {
            temp.delete()
        }
    }

    suspend fun setFlagged(ids: List<Long>, flagged: Boolean): Boolean {
        val rows = ids.chunked(500).flatMap { db.messages().byIds(it) }.filter { it.flagged != flagged }
        var ok = true
        rows.groupBy { it.folderId }.forEach { (folderId, group) ->
            val rowIds = group.map { it.id }
            db.messages().setFlagged(rowIds, flagged)
            try {
                val folder = checkNotNull(db.folders().get(folderId))
                access.use(folder.accountId) { session, _ -> session.setFlag(folder.path, group.map { it.uid }, MessageFlag.FLAGGED, flagged) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                db.messages().setFlagged(rowIds, !flagged)
                ok = false
            }
        }
        return ok
    }

    suspend fun setSeen(ids: List<Long>, seen: Boolean) = ids.mapNotNull { markSeen(it, seen) }.toSet().forEach { onSeenChanged(it) }

    suspend fun markAnswered(accountId: Long, messageId: String) {
        val row = db.messages().byMessageId(accountId, messageId) ?: return
        if (row.answered) return
        db.messages().markAnswered(row.id)
        val folder = db.folders().get(row.folderId) ?: return
        try {
            access.use(accountId) { session, _ -> session.setFlag(folder.path, listOf(row.uid), MessageFlag.ANSWERED, true) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
        }
    }

    fun folders(): Flow<List<FolderEntity>> = db.folders().observeAll()

    suspend fun knownAddresses(limit: Int = 400): List<EmailAddress> {
        val own = accounts.identities.first().map { Addresses.normalize(it.address) }.toSet()
        return db.messages().recent(limit)
            .flatMap { row ->
                listOfNotNull(row.fromAddress?.let { EmailAddress(it, row.fromName) }) +
                    Codec.parseAddresses(row.toAddresses) + Codec.parseAddresses(row.ccAddresses)
            }
            .filter { Addresses.isValid(it.address) && Addresses.normalize(it.address) !in own }
            .distinctBy { Addresses.normalize(it.address) }
    }

    fun detail(id: Long): Flow<MessageDetail?> = combine(db.messages().observe(id), addressing, db.folders().observeAll()) { row, known, folders ->
        row?.let { entity ->
            MessageDetail(
                summary = entity.toSummary(known),
                to = Codec.parseAddresses(entity.toAddresses),
                cc = Codec.parseAddresses(entity.ccAddresses),
                replyTo = Codec.parseAddresses(entity.replyTo),
                messageId = entity.messageId,
                inReplyTo = entity.inReplyTo,
                references = Codec.parseStrings(entity.references),
                allowRemoteImages = entity.allowRemoteImages,
                folderRole = folders.firstOrNull { it.id == entity.folderId }?.role,
            )
        }
    }

    fun unreadCounts(): Flow<Map<Long, Int>> = db.messages().observeUnreadCounts().map { rows -> rows.associate { it.accountId to it.count } }

    suspend fun body(id: Long): BodyResult {
        val cached = try {
            db.messages().body(id)
        } catch (_: android.database.sqlite.SQLiteException) {
            null
        }
        cached?.let { return BodyResult.Loaded(it.text, it.html, Codec.parseAttachments(it.attachments)) }
        val message = db.messages().get(id) ?: return BodyResult.Failed(null)
        val folder = db.folders().get(message.folderId) ?: return BodyResult.Failed(null)
        return try {
            val content = access.use(message.accountId) { session, _ -> session.fetchContent(folder.path, message.uid) }
                ?: return BodyResult.Failed(null)
            if ((content.text?.length ?: 0) + (content.html?.length ?: 0) <= MAX_CACHED_BODY_CHARS) {
                db.messages().upsertBody(MessageBodyEntity(id, content.text, content.html, Codec.attachments(content.attachments)))
            }
            BodyResult.Loaded(content.text, content.html, content.attachments)
        } catch (_: MissingCredentialException) {
            BodyResult.Failed(ConnectFailure.AUTH)
        } catch (error: Exception) {
            BodyResult.Failed(ConnectFailures.classify(error))
        }
    }

    suspend fun setSeen(id: Long, seen: Boolean) {
        markSeen(id, seen)?.let { onSeenChanged(it) }
    }

    private suspend fun markSeen(id: Long, seen: Boolean): Long? {
        val message = db.messages().get(id) ?: return null
        if (message.seen == seen) return null
        db.withTransaction {
            db.messages().setSeen(id, seen)
            db.pendingFlags().upsert(PendingFlagEntity(accountId = message.accountId, folderId = message.folderId, uid = message.uid, seen = seen))
        }
        return message.accountId
    }

    suspend fun allowImages(id: Long) = db.messages().allowImages(id)

    suspend fun replySeed(id: Long, replyAll: Boolean, attribution: String, hiddenAddressLabel: String? = null): DraftSeed? {
        if (hiddenAddressLabel != null) adoptHiddenAddress(id, hiddenAddressLabel)
        val original = original(id) ?: return null
        return ReplyComposer.reply(original, accounts.identities.first(), replyAll, attribution)
    }

    private suspend fun adoptHiddenAddress(id: Long, label: String) {
        val message = db.messages().get(id) ?: return
        val role = db.folders().get(message.folderId)?.role
        if (role == FolderRole.SENT || role == FolderRole.DRAFTS) return
        val account = accounts.accounts.first().firstOrNull { it.id == message.accountId } ?: return
        val identities = accounts.identities.first()
        val own = identities.filter { it.accountId == account.id }
        val address = (match(message, own, RecipientMatcher.ownAddresses(account.providerId, account.username, own)) as? RecipientMatch.OwnAddress)?.address ?: return
        if (!RecipientMatcher.isHideMyEmail(account.providerId, account.username, address)) return
        if (identities.any { Addresses.same(it.address, address) }) return
        val free = generateSequence(1) { it + 1 }.map { if (it == 1) label else "$label $it" }
            .first { candidate -> identities.none { it.label.trim().equals(candidate, ignoreCase = true) } }
        accounts.addIdentity(account.id, IdentityInput(address, "", "", free))
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

    private fun MessageEntity.toSummary(known: Addressing, digest: String? = null) = MessageSummary(
        id = id,
        accountId = accountId,
        subject = subject,
        fromName = fromName,
        fromAddress = fromAddress,
        preview = preview,
        sortDate = sortDate,
        seen = seen,
        hasAttachment = hasAttachment,
        match = known.match(this),
        flagged = flagged,
        answered = answered,
        folderId = folderId,
        digest = digest,
    )

    private fun match(message: MessageEntity, accountIdentities: List<MailIdentity>, ownAddress: (String) -> Boolean = { false }): RecipientMatch = RecipientMatcher.match(
        ReceivedAddressing(
            Codec.parseAddresses(message.toAddresses),
            Codec.parseAddresses(message.ccAddresses),
            Codec.parseStrings(message.deliveryValues),
        ),
        accountIdentities,
        ownAddress,
    )

    private inner class Addressing(identities: List<MailIdentity>, accounts: List<MailAccount>, folders: List<FolderEntity>, sentFrom: List<SentFrom>) {
        private val byAccount = identities.groupBy { it.accountId }
        private val sent = sentFrom.groupBy({ it.accountId }, { it.address })
        private val own = accounts.associate { account ->
            account.id to RecipientMatcher.ownAddresses(account.providerId, account.username, byAccount[account.id].orEmpty(), sent[account.id].orEmpty())
        }
        private val outgoing = folders.filter { it.role == FolderRole.SENT || it.role == FolderRole.DRAFTS }.map { it.id }.toSet()

        fun match(message: MessageEntity): RecipientMatch =
            if (message.folderId in outgoing) RecipientMatch.NotReceived else match(message, byAccount[message.accountId].orEmpty(), own[message.accountId] ?: { false })
    }

    private val addressing: Flow<Addressing> = combine(
        accounts.identities,
        accounts.accounts,
        db.folders().observeAll(),
        db.messages().observeSentFrom().distinctUntilChanged(),
    ) { identities, list, folders, sentFrom -> Addressing(identities, list, folders, sentFrom) }
}

private const val MAX_CACHED_BODY_CHARS = 600_000
