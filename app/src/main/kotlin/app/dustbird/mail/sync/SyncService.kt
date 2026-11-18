package app.dustbird.mail.sync

import app.dustbird.mail.core.folder.FolderInfo
import app.dustbird.mail.core.folder.FolderRoles
import app.dustbird.mail.core.folder.RoleResolution
import app.dustbird.mail.core.identity.RecipientMatch
import app.dustbird.mail.core.identity.RecipientMatcher
import app.dustbird.mail.core.identity.ReceivedAddressing
import app.dustbird.mail.core.model.FolderRole
import app.dustbird.mail.core.model.StageState
import app.dustbird.mail.core.net.ConnectFailure
import app.dustbird.mail.core.net.ConnectFailures
import app.dustbird.mail.core.net.FetchedMessage
import app.dustbird.mail.core.provider.Providers
import app.dustbird.mail.core.send.SendEvent
import app.dustbird.mail.core.send.SendMachine
import app.dustbird.mail.core.send.SendRecord
import app.dustbird.mail.core.send.SendState
import app.dustbird.mail.core.sync.FolderCursor
import app.dustbird.mail.core.sync.FolderStore
import app.dustbird.mail.core.sync.FolderSync
import app.dustbird.mail.core.sync.LocalFlags
import app.dustbird.mail.data.Codec
import app.dustbird.mail.data.db.FolderEntity
import app.dustbird.mail.data.db.ListState
import app.dustbird.mail.data.db.MailDatabase
import app.dustbird.mail.data.db.MessageEntity
import app.dustbird.mail.data.db.NotifiedEntity
import app.dustbird.mail.data.db.PendingFlagEntity
import app.dustbird.mail.data.repo.AccountRepository
import app.dustbird.mail.data.toModel
import androidx.room.withTransaction
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface SyncOutcome {
    data object Synced : SyncOutcome
    data object NeedsReauth : SyncOutcome
    data class Failed(val failure: ConnectFailure) : SyncOutcome
}

sealed interface AccountSyncState {
    data object Idle : AccountSyncState
    data object Syncing : AccountSyncState
    data object NeedsReauth : AccountSyncState
    data class Error(val failure: ConnectFailure) : AccountSyncState
}

class RoomFolderStore(
    private val db: MailDatabase,
    private val folderId: Long,
    private val accountId: Long,
) : FolderStore {
    private val sync get() = db.sync()

    override fun cursor(): FolderCursor? {
        val folder = sync.folder(folderId)
        return if (folder.uidValidity == 0L) null else FolderCursor(folder.uidValidity, folder.highestUid, folder.baselineComplete)
    }

    override fun reset(uidValidity: Long) {
        sync.deleteMessages(folderId)
        sync.deletePending(folderId)
        sync.updateFolder(sync.folder(folderId).copy(uidValidity = uidValidity, highestUid = 0, baselineComplete = false))
    }

    override fun cachedFlags(limit: Int): Map<Long, LocalFlags> =
        sync.cachedFlags(folderId, limit).associate { it.uid to LocalFlags(it.seen, it.answered, it.flagged) }

    override fun oldestUid(): Long = sync.oldestUid(folderId)

    override fun pendingSeen(): Map<Long, Boolean> = sync.pending(folderId).associate { it.uid to it.seen }

    override fun clearPending(uid: Long) = sync.clearPending(folderId, uid)

    override fun insert(messages: List<FetchedMessage>) {
        val validity = sync.folder(folderId).uidValidity
        sync.insertMessages(messages.map { it.toEntity(accountId, folderId, validity) })
        messages.map { it.uid }.chunked(500).forEach { sync.promote(folderId, it) }
    }

    override fun updateFlags(uid: Long, flags: LocalFlags) = sync.setFlags(folderId, uid, flags.seen, flags.answered, flags.flagged)

    override fun remove(uids: List<Long>) = uids.chunked(500).forEach { sync.deleteUids(folderId, it) }

    override fun saveCursor(cursor: FolderCursor) {
        sync.updateFolder(
            sync.folder(folderId).copy(
                uidValidity = cursor.uidValidity,
                highestUid = cursor.highestUid,
                baselineComplete = cursor.baselineComplete,
                syncedAt = System.currentTimeMillis(),
            ),
        )
    }
}

fun FetchedMessage.toEntity(accountId: Long, folderId: Long, uidValidity: Long) = MessageEntity(
    accountId = accountId,
    folderId = folderId,
    uid = uid,
    uidValidity = uidValidity,
    messageId = headers.messageId,
    inReplyTo = headers.inReplyTo,
    references = Codec.strings(headers.references),
    fromName = headers.from.firstOrNull()?.name,
    fromAddress = headers.from.firstOrNull()?.address,
    replyTo = Codec.addresses(headers.replyTo),
    toAddresses = Codec.addresses(headers.to),
    ccAddresses = Codec.addresses(headers.cc),
    deliveryValues = Codec.strings(headers.deliveryHeaderValues),
    subject = headers.subject,
    sortDate = (headers.sentAt ?: internalDate ?: Instant.now()).toEpochMilli(),
    preview = preview,
    size = size,
    hasAttachment = hasAttachment,
    seen = flags.seen,
    answered = flags.answered,
    flagged = flags.flagged,
    allowRemoteImages = false,
)

class SyncService(
    private val db: MailDatabase,
    private val access: ImapAccess,
    private val accounts: AccountRepository,
    private val notifier: MailNotifier,
    private val folderSync: FolderSync = FolderSync(),
    private val onNewMail: suspend (accountId: Long, messageRowIds: List<Long>) -> Unit = { _, _ -> },
) {
    private val locks = ConcurrentHashMap<Long, Mutex>()
    private val _state = MutableStateFlow<Map<Long, AccountSyncState>>(emptyMap())
    val state: StateFlow<Map<Long, AccountSyncState>> = _state.asStateFlow()

    private val syncedRoles = listOf(FolderRole.INBOX, FolderRole.SENT, FolderRole.DRAFTS, FolderRole.SPAM, FolderRole.ARCHIVE, FolderRole.TRASH)

    suspend fun syncAll(): Map<Long, SyncOutcome> = coroutineScope {
        db.accounts().getAll().map { account -> async { account.id to syncAccount(account.id) } }.awaitAll().toMap()
    }

    suspend fun syncAccounts(ids: Collection<Long>): Map<Long, SyncOutcome> = coroutineScope {
        ids.map { id -> async { id to syncAccount(id) } }.awaitAll().toMap()
    }

    suspend fun syncAccount(accountId: Long): SyncOutcome = locks.getOrPut(accountId) { Mutex() }.withLock {
        publish(accountId, AccountSyncState.Syncing)
        val outcome = try {
            run(accountId)
            SyncOutcome.Synced
        } catch (_: MissingCredentialException) {
            SyncOutcome.NeedsReauth.also { markNeedsReauth(accountId) }
        } catch (error: Exception) {
            when (val failure = ConnectFailures.classify(error)) {
                ConnectFailure.AUTH, ConnectFailure.SERVICE_DISABLED -> SyncOutcome.NeedsReauth.also { markNeedsReauth(accountId) }
                else -> SyncOutcome.Failed(failure)
            }
        }
        publish(
            accountId,
            when (outcome) {
                SyncOutcome.Synced -> AccountSyncState.Idle
                SyncOutcome.NeedsReauth -> AccountSyncState.NeedsReauth
                is SyncOutcome.Failed -> AccountSyncState.Error(outcome.failure)
            },
        )
        outcome
    }

    suspend fun loadOlder(accountId: Long, role: FolderRole): Int {
        val folder = db.folders().byRole(accountId, role) ?: return 0
        return loadOlderFolder(folder.id)
    }

    suspend fun loadOlderFolder(folderId: Long): Int {
        val folder = db.folders().get(folderId) ?: return 0
        val delivery = deliveryHeaders(folder.accountId)
        return access.use(folder.accountId) { session, _ ->
            folderSync.loadOlder(session, folder.path, RoomFolderStore(db, folder.id, folder.accountId), delivery)
        }
    }

    suspend fun syncFolder(folderId: Long): Boolean {
        val folder = db.folders().get(folderId) ?: return false
        val delivery = deliveryHeaders(folder.accountId)
        return locks.getOrPut(folder.accountId) { Mutex() }.withLock {
            try {
                access.use(folder.accountId) { session, _ ->
                    withContext(Dispatchers.IO) { folderSync.sync(session, folder.path, RoomFolderStore(db, folder.id, folder.accountId), delivery) }
                }
                true
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
                false
            }
        }
    }

    suspend fun confirmRole(accountId: Long, role: FolderRole, path: String) {
        db.withTransaction {
            db.folders().forAccount(accountId).filter { it.role == role && it.path != path }.forEach {
                db.folders().update(it.copy(role = null, roleConfirmed = false))
            }
            db.folders().byPath(accountId, path)?.let { db.folders().update(it.copy(role = role, roleConfirmed = true)) }
        }
    }

    fun forget(accountId: Long) {
        locks.remove(accountId)
        _state.update { it - accountId }
        notifier.cancel(accountId)
    }

    private suspend fun run(accountId: Long) {
        val delivery = deliveryHeaders(accountId)
        access.use(accountId) { session, account ->
            val listed = session.listFolders()
            persistFolders(accountId, listed)
            for (role in syncedRoles) {
                val folder = db.folders().byRole(accountId, role) ?: continue
                val result = withContext(Dispatchers.IO) {
                    folderSync.sync(session, folder.path, RoomFolderStore(db, folder.id, accountId), delivery)
                }
                if (role == FolderRole.INBOX) announce(accountId, folder, result.notifiable.filterNot { it.flags.seen })
                if (role == FolderRole.SENT) reconcileSent(accountId)
            }
            val inbox = db.folders().byRole(accountId, FolderRole.INBOX)
            val received = inbox != null && db.messages().count(inbox.id) > 0
            accounts.updateStages(accountId) {
                it.copy(imapAuth = StageState.OK, receive = if (received) StageState.OK else it.receive)
            }
            if (account.needsReauth) db.accounts().setNeedsReauth(accountId, false)
        }
        notifier.reauthResolved(accountId)
        sweepNotifications(accountId)
    }

    suspend fun sweepNotifications(accountId: Long) = notifier.sweep(accountId) { ids ->
        db.messages().byIds(ids).filter { !it.seen && it.listState == ListState.NORMAL }.map { it.id }.toSet()
    }

    private suspend fun announce(accountId: Long, folder: FolderEntity, fresh: List<FetchedMessage>) {
        if (fresh.isEmpty()) return
        publishNotifications(accountId, listOf(folder to fresh))
        val ids = db.messages().byUids(folder.id, fresh.map { it.uid }).map { it.id }
        if (ids.isNotEmpty()) onNewMail(accountId, ids)
    }

    private suspend fun persistFolders(accountId: Long, listed: List<FolderInfo>) {
        val existing = db.folders().forAccount(accountId).associateBy { it.path }
        val selectable = listed.filter { it.selectable }
        val confirmed = existing.values.filter { it.roleConfirmed && it.role != null }.associate { it.role!! to it.path }
        val resolved = FolderRoles.resolve(selectable, confirmed)
        val roleByPath = resolved.entries.mapNotNull { (role, resolution) -> (resolution as? RoleResolution.Assigned)?.let { it.path to role } }.toMap()
        selectable.forEach { info ->
            val previous = existing[info.path]
            val role = roleByPath[info.path]
            val entity = (previous ?: FolderEntity(
                accountId = accountId, path = info.path, delimiter = null, attributes = "", role = null, roleConfirmed = false,
                uidValidity = 0, highestUid = 0, baselineComplete = false, syncedAt = 0,
            )).copy(
                delimiter = info.delimiter?.toString(),
                attributes = info.attributes.joinToString(" "),
                role = role,
                roleConfirmed = previous?.roleConfirmed == true && role != null,
            )
            if (previous == null) db.folders().insert(entity) else db.folders().update(entity)
        }
        val listedPaths = selectable.map { it.path }.toSet()
        existing.values.filter { it.path !in listedPaths }.forEach { db.folders().delete(it.id) }
    }

    private suspend fun reconcileSent(accountId: Long) {
        val items = db.outbox().byAccountAndStates(accountId, listOf(SendState.ACCEPTED, SendState.UNKNOWN, SendState.SAVE_FAILED))
        for (item in items) {
            if (db.messages().byMessageId(accountId, item.messageId) == null) continue
            val next = SendMachine.next(SendRecord(item.state, item.attempts, item.failure), SendEvent.FoundInSent)
            db.outbox().setState(item.id, next.state, next.attempts, next.failure, System.currentTimeMillis())
            accounts.updateStages(accountId) { it.copy(sentCopy = StageState.OK) }
        }
    }

    private suspend fun publishNotifications(accountId: Long, found: List<Pair<FolderEntity, List<FetchedMessage>>>) {
        if (found.isEmpty()) return
        val account = db.accounts().get(accountId)?.toModel() ?: return
        if (!account.notificationsEnabled) return
        val identities = db.identities().forAccount(accountId).map { it.toModel() }
        val mails = ArrayList<NewMail>()
        for ((folder, messages) in found) {
            val rows = db.messages().byUids(folder.id, messages.map { it.uid }).associateBy { it.uid }
            for (message in messages) {
                val row = rows[message.uid] ?: continue
                val key = "${folder.id}|${message.uid}|${message.headers.messageId.orEmpty()}"
                if (db.notified().insert(NotifiedEntity(accountId = accountId, dedupKey = key, notifiedAt = System.currentTimeMillis())) < 0) continue
                val match = RecipientMatcher.match(
                    ReceivedAddressing(message.headers.to, message.headers.cc, message.headers.deliveryHeaderValues),
                    identities,
                )
                val label = (match as? RecipientMatch.Matched)?.identityIds?.firstNotNullOfOrNull { id -> identities.firstOrNull { it.id == id }?.label }
                val sender = message.headers.from.firstOrNull()?.let { it.name ?: it.address }.orEmpty()
                mails += NewMail(row.id, sender, message.headers.subject, message.preview, label)
            }
        }
        if (mails.isNotEmpty()) notifier.notifyNew(account, mails)
    }

    private suspend fun markNeedsReauth(accountId: Long) {
        val known = db.accounts().get(accountId)?.needsReauth == true
        accounts.markNeedsReauth(accountId)
        accounts.updateStages(accountId) { it.copy(imapAuth = StageState.FAILED) }
        if (!known) db.accounts().get(accountId)?.let { notifier.reauthRequired(it.toModel()) }
    }

    private suspend fun deliveryHeaders(accountId: Long): List<String> {
        val account = db.accounts().get(accountId) ?: return emptyList()
        return Providers.byId(account.providerId).deliveryHeaders
    }

    private fun publish(accountId: Long, state: AccountSyncState) = _state.update { it + (accountId to state) }
}
