package app.dustbird.mail.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import app.dustbird.mail.core.model.FolderRole
import app.dustbird.mail.core.send.SendState
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Query("SELECT * FROM account ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM account ORDER BY sortOrder, id")
    suspend fun getAll(): List<AccountEntity>

    @Query("SELECT * FROM account WHERE id = :id")
    suspend fun get(id: Long): AccountEntity?

    @Query("SELECT * FROM account WHERE id = :id")
    fun observe(id: Long): Flow<AccountEntity?>

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM account")
    suspend fun nextSortOrder(): Int

    @Insert
    suspend fun insert(entity: AccountEntity): Long

    @Update
    suspend fun update(entity: AccountEntity)

    @Query("DELETE FROM account WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE account SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun setSortOrder(id: Long, sortOrder: Int)

    @Query("UPDATE account SET needsReauth = :value WHERE id = :id")
    suspend fun setNeedsReauth(id: Long, value: Boolean)
}

@Dao
interface IdentityDao {
    @Query("SELECT * FROM identity ORDER BY accountId, sortOrder, id")
    fun observeAll(): Flow<List<IdentityEntity>>

    @Query("SELECT * FROM identity ORDER BY accountId, sortOrder, id")
    suspend fun getAll(): List<IdentityEntity>

    @Query("SELECT * FROM identity WHERE accountId = :accountId ORDER BY sortOrder, id")
    suspend fun forAccount(accountId: Long): List<IdentityEntity>

    @Query("SELECT * FROM identity WHERE id = :id")
    suspend fun get(id: Long): IdentityEntity?

    @Insert
    suspend fun insert(entity: IdentityEntity): Long

    @Update
    suspend fun update(entity: IdentityEntity)

    @Query("DELETE FROM identity WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE identity SET isAccountDefault = (id = :defaultId) WHERE accountId = :accountId")
    suspend fun setAccountDefault(accountId: Long, defaultId: Long)

    @Query("UPDATE identity SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun setSortOrder(id: Long, sortOrder: Int)

    @Query("UPDATE identity SET verification = :verification WHERE id = :id")
    suspend fun setVerification(id: Long, verification: app.dustbird.mail.core.model.SendVerification)
}

@Dao
interface FolderDao {
    @Query("SELECT * FROM folder WHERE accountId = :accountId ORDER BY path")
    suspend fun forAccount(accountId: Long): List<FolderEntity>

    @Query("SELECT * FROM folder WHERE accountId = :accountId ORDER BY path")
    fun observeForAccount(accountId: Long): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folder ORDER BY accountId, path")
    fun observeAll(): Flow<List<FolderEntity>>
    @Query("SELECT * FROM folder WHERE id = :id")
    suspend fun get(id: Long): FolderEntity?

    @Query("SELECT * FROM folder WHERE accountId = :accountId AND role = :role LIMIT 1")
    suspend fun byRole(accountId: Long, role: FolderRole): FolderEntity?

    @Query("SELECT * FROM folder WHERE accountId = :accountId AND path = :path")
    suspend fun byPath(accountId: Long, path: String): FolderEntity?

    @Insert
    suspend fun insert(entity: FolderEntity): Long

    @Update
    suspend fun update(entity: FolderEntity)

    @Query("DELETE FROM folder WHERE id = :id")
    suspend fun delete(id: Long)
}

data class UnreadCount(val accountId: Long, val count: Int)

private const val SEARCH_SCOPE = " m.listState != 1 AND (:accountId < 0 OR m.accountId = :accountId) AND (:role IS NULL OR f.role = :role) " +
    "AND m.sortDate >= :since AND m.sortDate < :until AND (:unread = 0 OR m.seen = 0) AND (:starred = 0 OR m.flagged = 1) " +
    "AND (:attachment = 0 OR m.hasAttachment = 1) "

@Dao
interface DigestDao {
    @Query("SELECT * FROM message_digest")
    fun observeAll(): Flow<List<MessageDigestEntity>>

    @Query(
        "SELECT m.* FROM message m JOIN folder f ON f.id = m.folderId WHERE f.role = 'INBOX' AND m.listState = 0 AND m.sortDate >= :since " +
            "AND m.accountId NOT IN (:blocked) AND m.id NOT IN (SELECT messageRowId FROM message_digest) ORDER BY m.sortDate DESC, m.id DESC LIMIT :limit",
    )
    suspend fun pending(since: Long, blocked: List<Long>, limit: Int): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<MessageDigestEntity>)
}

@Dao
interface MessageDao {
    @Query(
        "SELECT m.* FROM message m JOIN folder f ON f.id = m.folderId WHERE f.accountId = :accountId AND f.role = :role " +
            "AND m.listState = 0 AND (:unreadOnly = 0 OR m.seen = 0) AND (:flaggedOnly = 0 OR m.flagged = 1) " +
            "ORDER BY m.sortDate DESC, m.id DESC LIMIT :limit",
    )
    fun observeByAccountAndRole(accountId: Long, role: FolderRole, limit: Int, unreadOnly: Boolean, flaggedOnly: Boolean): Flow<List<MessageEntity>>
    @Query(
        "SELECT * FROM message WHERE folderId = :folderId AND listState = 0 AND (:unreadOnly = 0 OR seen = 0) " +
            "AND (:flaggedOnly = 0 OR flagged = 1) ORDER BY sortDate DESC, id DESC LIMIT :limit",
    )
    fun observeByFolder(folderId: Long, limit: Int, unreadOnly: Boolean, flaggedOnly: Boolean): Flow<List<MessageEntity>>
    @Query(
        "SELECT m.* FROM message m JOIN folder f ON f.id = m.folderId WHERE f.role = :role AND m.listState = 0 " +
            "AND (:unreadOnly = 0 OR m.seen = 0) AND (:flaggedOnly = 0 OR m.flagged = 1) AND m.id IN (" +
            "SELECT MIN(m2.id) FROM message m2 JOIN folder f2 ON f2.id = m2.folderId WHERE f2.role = :role AND m2.listState = 0 " +
            "GROUP BY m2.accountId, COALESCE(m2.messageId, 'row:' || m2.id)) " +
            "ORDER BY m.sortDate DESC, m.id DESC LIMIT :limit",
    )
    fun observeUnified(role: FolderRole, limit: Int, unreadOnly: Boolean, flaggedOnly: Boolean): Flow<List<MessageEntity>>
    @Query(
        "SELECT m.* FROM message m JOIN folder f ON f.id = m.folderId WHERE" + SEARCH_SCOPE +
            "AND (:anyText = 1 OR (:inSubject = 1 AND m.subject LIKE :pattern ESCAPE '\\') " +
            "OR (:inSender = 1 AND (m.fromName LIKE :pattern ESCAPE '\\' OR m.fromAddress LIKE :pattern ESCAPE '\\')) " +
            "OR (:inRecipient = 1 AND (m.toAddresses LIKE :pattern ESCAPE '\\' OR m.ccAddresses LIKE :pattern ESCAPE '\\')) " +
            "OR (:inPreview = 1 AND m.preview LIKE :pattern ESCAPE '\\')) ORDER BY m.sortDate DESC, m.id DESC LIMIT :limit",
    )
    fun search(
        pattern: String, anyText: Boolean, inSubject: Boolean, inSender: Boolean, inRecipient: Boolean, inPreview: Boolean,
        accountId: Long, role: FolderRole?, since: Long, until: Long, unread: Boolean, starred: Boolean, attachment: Boolean, limit: Int,
    ): Flow<List<MessageEntity>>

    @Query("SELECT m.* FROM message m JOIN folder f ON f.id = m.folderId WHERE m.id IN (:ids) AND" + SEARCH_SCOPE)
    suspend fun filter(
        ids: List<Long>, accountId: Long, role: FolderRole?, since: Long, until: Long, unread: Boolean, starred: Boolean, attachment: Boolean,
    ): List<MessageEntity>
    @Query("SELECT * FROM message WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<MessageEntity>
    @Query("SELECT * FROM message ORDER BY sortDate DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<MessageEntity>
    @Query("UPDATE message SET listState = :state WHERE id IN (:ids)")
    suspend fun setListState(ids: List<Long>, state: Int)
    @Query("UPDATE message SET listState = 0 WHERE listState = 1")
    suspend fun unhideAll()
    @Query("DELETE FROM message WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)
    @Query("UPDATE message SET flagged = :flagged WHERE id IN (:ids)")
    suspend fun setFlagged(ids: List<Long>, flagged: Boolean)
    @Query("UPDATE message SET answered = 1 WHERE id = :id")
    suspend fun markAnswered(id: Long)
    @Query("SELECT * FROM message WHERE id = :id")
    fun observe(id: Long): Flow<MessageEntity?>

    @Query("SELECT * FROM message WHERE id = :id")
    suspend fun get(id: Long): MessageEntity?

    @Query("SELECT uid FROM message WHERE folderId = :folderId")
    suspend fun uids(folderId: Long): List<Long>

    @Query("SELECT id FROM message WHERE accountId = :accountId")
    suspend fun idsForAccount(accountId: Long): List<Long>

    @Query("SELECT COALESCE(MIN(uid), 0) FROM message WHERE folderId = :folderId")
    suspend fun minUid(folderId: Long): Long

    @Query("SELECT COUNT(*) FROM message WHERE folderId = :folderId")
    suspend fun count(folderId: Long): Int

    @Query("SELECT * FROM message WHERE folderId = :folderId AND uid IN (:uids)")
    suspend fun byUids(folderId: Long, uids: List<Long>): List<MessageEntity>

    @Query("SELECT * FROM message WHERE accountId = :accountId AND messageId = :messageId LIMIT 1")
    suspend fun byMessageId(accountId: Long, messageId: String): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MessageEntity): Long

    @Query("UPDATE message SET seen = :seen WHERE id = :id")
    suspend fun setSeen(id: Long, seen: Boolean)

    @Query("UPDATE message SET seen = :seen, answered = :answered, flagged = :flagged WHERE folderId = :folderId AND uid = :uid")
    suspend fun setFlags(folderId: Long, uid: Long, seen: Boolean, answered: Boolean, flagged: Boolean)

    @Query("UPDATE message SET allowRemoteImages = 1 WHERE id = :id")
    suspend fun allowImages(id: Long)

    @Query("DELETE FROM message WHERE folderId = :folderId AND uid IN (:uids)")
    suspend fun deleteUids(folderId: Long, uids: List<Long>)

    @Query("DELETE FROM message WHERE folderId = :folderId")
    suspend fun deleteFolder(folderId: Long)

    @Query(
        "SELECT f.accountId AS accountId, COUNT(*) AS count FROM message m JOIN folder f ON f.id = m.folderId " +
            "WHERE f.role = 'INBOX' AND m.seen = 0 AND m.listState = 0 GROUP BY f.accountId",
    )
    fun observeUnreadCounts(): Flow<List<UnreadCount>>

    @Query("SELECT * FROM message_body WHERE messageRowId = :id")
    suspend fun body(id: Long): MessageBodyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBody(entity: MessageBodyEntity)
}

@Dao
interface DraftDao {
    @Query("SELECT * FROM draft WHERE id NOT IN (SELECT draftId FROM outbox WHERE draftId IS NOT NULL) ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<DraftEntity>>

    @Query("SELECT * FROM draft WHERE accountId = :accountId AND messageId = :messageId LIMIT 1")
    suspend fun byMessageId(accountId: Long, messageId: String): DraftEntity?

    @Query("SELECT * FROM draft WHERE id = :id")
    suspend fun get(id: Long): DraftEntity?

    @Insert
    suspend fun insert(entity: DraftEntity): Long

    @Update
    suspend fun update(entity: DraftEntity)

    @Query("UPDATE draft SET serverCopyAccountId = :accountId WHERE id = :id")
    suspend fun setServerCopy(id: Long, accountId: Long?)

    @Query("DELETE FROM draft WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE draft SET accountId = :accountId, identityId = :fallbackId WHERE identityId = :identityId")
    suspend fun moveIdentity(identityId: Long, fallbackId: Long, accountId: Long)

    @Query("SELECT COUNT(*) FROM draft WHERE accountId = :accountId")
    suspend fun countForAccount(accountId: Long): Int

    @Query("SELECT id FROM draft WHERE accountId = :accountId")
    suspend fun idsForAccount(accountId: Long): List<Long>

    @Query("SELECT COUNT(*) FROM draft WHERE identityId = :identityId")
    suspend fun countForIdentity(identityId: Long): Int
}

@Dao
interface DraftAttachmentDao {
    @Query("SELECT * FROM draft_attachment WHERE draftId = :draftId ORDER BY id")
    fun observe(draftId: Long): Flow<List<DraftAttachmentEntity>>
    @Query("SELECT * FROM draft_attachment WHERE draftId = :draftId ORDER BY id")
    suspend fun forDraft(draftId: Long): List<DraftAttachmentEntity>
    @Query("SELECT * FROM draft_attachment WHERE id = :id")
    suspend fun get(id: Long): DraftAttachmentEntity?
    @Insert
    suspend fun insert(entity: DraftAttachmentEntity): Long
    @Query("DELETE FROM draft_attachment WHERE id = :id")
    suspend fun delete(id: Long)
}
@Dao
interface OutboxDao {
    @Query("SELECT * FROM outbox ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<OutboxEntity>>

    @Query("SELECT * FROM outbox WHERE id = :id")
    suspend fun get(id: Long): OutboxEntity?

    @Query("SELECT * FROM outbox WHERE id = :id")
    fun observe(id: Long): Flow<OutboxEntity?>

    @Query("SELECT * FROM outbox WHERE accountId = :accountId AND state IN (:states) ORDER BY createdAt")
    suspend fun byAccountAndStates(accountId: Long, states: List<SendState>): List<OutboxEntity>

    @Query("SELECT * FROM outbox WHERE state = 'SENDING'")
    suspend fun sending(): List<OutboxEntity>

    @Query("SELECT * FROM outbox WHERE state = 'ACCEPTED'")
    suspend fun accepted(): List<OutboxEntity>

    @Query("SELECT * FROM outbox WHERE state = 'SAVED'")
    suspend fun saved(): List<OutboxEntity>

    @Query("UPDATE outbox SET rawPath = NULL WHERE id = :id")
    suspend fun clearRaw(id: Long)

    @Query("SELECT * FROM outbox WHERE draftId = :draftId")
    suspend fun byDraft(draftId: Long): List<OutboxEntity>

    @Query("SELECT MIN(sendAfter) FROM outbox WHERE state = 'QUEUED' AND sendAfter > :now")
    suspend fun nextDue(now: Long): Long?

    @Query("UPDATE outbox SET rawPath = :rawPath, envelopeFrom = :envelopeFrom, recipients = :recipients, draftId = NULL, updatedAt = :now WHERE id = :id")
    suspend fun release(id: Long, rawPath: String, envelopeFrom: String, recipients: String, now: Long)

    @Query("UPDATE outbox SET sendAfter = :sendAfter, updatedAt = :now WHERE id = :id")
    suspend fun setSendAfter(id: Long, sendAfter: Long, now: Long)

    @Query("SELECT COUNT(*) FROM outbox WHERE accountId = :accountId AND state IN ('QUEUED','SENDING','UNKNOWN','ACCEPTED','SAVE_FAILED')")
    suspend fun unsettledCount(accountId: Long): Int

    @Insert
    suspend fun insert(entity: OutboxEntity): Long

    @Query("UPDATE outbox SET state = :state, attempts = :attempts, failure = :failure, updatedAt = :now WHERE id = :id")
    suspend fun setState(id: Long, state: SendState, attempts: Int, failure: String?, now: Long)

    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface PendingFlagDao {
    @Query("SELECT * FROM pending_flag WHERE accountId = :accountId ORDER BY id")
    suspend fun forAccount(accountId: Long): List<PendingFlagEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PendingFlagEntity)

    @Delete
    suspend fun delete(entity: PendingFlagEntity)

    @Query("SELECT * FROM pending_flag WHERE folderId = :folderId")
    suspend fun forFolder(folderId: Long): List<PendingFlagEntity>
}

@Dao
interface NotifiedDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: NotifiedEntity): Long

    @Query("DELETE FROM notified WHERE notifiedAt < :before")
    suspend fun prune(before: Long)
}

data class FlagRow(val uid: Long, val seen: Boolean, val answered: Boolean, val flagged: Boolean)

@Dao
interface SyncDao {
    @Query("SELECT * FROM folder WHERE id = :id")
    fun folder(id: Long): FolderEntity

    @Update
    fun updateFolder(entity: FolderEntity)

    @Query("DELETE FROM message WHERE folderId = :folderId")
    fun deleteMessages(folderId: Long)

    @Query("DELETE FROM pending_flag WHERE folderId = :folderId")
    fun deletePending(folderId: Long)

    @Query("SELECT uid, seen, answered, flagged FROM message WHERE folderId = :folderId ORDER BY uid DESC LIMIT :limit")
    fun cachedFlags(folderId: Long, limit: Int): List<FlagRow>

    @Query("SELECT COALESCE(MIN(uid), 0) FROM message WHERE folderId = :folderId AND listState != 2")
    fun oldestUid(folderId: Long): Long
    @Query("UPDATE message SET listState = 0 WHERE folderId = :folderId AND uid IN (:uids) AND listState = 2")
    fun promote(folderId: Long, uids: List<Long>)

    @Query("SELECT * FROM pending_flag WHERE folderId = :folderId ORDER BY id")
    fun pending(folderId: Long): List<PendingFlagEntity>

    @Query("DELETE FROM pending_flag WHERE folderId = :folderId AND uid = :uid")
    fun clearPending(folderId: Long, uid: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertMessages(entities: List<MessageEntity>)

    @Query("UPDATE message SET seen = :seen, answered = :answered, flagged = :flagged WHERE folderId = :folderId AND uid = :uid")
    fun setFlags(folderId: Long, uid: Long, seen: Boolean, answered: Boolean, flagged: Boolean)

    @Query("DELETE FROM message WHERE folderId = :folderId AND uid IN (:uids)")
    fun deleteUids(folderId: Long, uids: List<Long>)

    @Query("UPDATE outbox SET state = :state, attempts = :attempts, failure = :failure, updatedAt = :now WHERE id = :id")
    fun setOutboxState(id: Long, state: app.dustbird.mail.core.send.SendState, attempts: Int, failure: String?, now: Long)
}

@Dao
interface MaintenanceDao {
    @Query("DELETE FROM message WHERE accountId = :accountId")
    suspend fun deleteMessages(accountId: Long)

    @Query("UPDATE folder SET uidValidity = 0, highestUid = 0, baselineComplete = 0, syncedAt = 0 WHERE accountId = :accountId")
    suspend fun resetFolders(accountId: Long)

    @Query("DELETE FROM pending_flag WHERE accountId = :accountId")
    suspend fun deletePending(accountId: Long)

    @Query("DELETE FROM notified WHERE accountId = :accountId")
    suspend fun deleteNotified(accountId: Long)
}

@Dao
interface AgentDao {
    @Query("SELECT * FROM agent_session ORDER BY updatedAt DESC LIMIT :limit")
    fun observeSessions(limit: Int): Flow<List<AgentSessionEntity>>
    @Query("SELECT * FROM agent_session WHERE id = :id")
    suspend fun session(id: Long): AgentSessionEntity?
    @Insert
    suspend fun insertSession(entity: AgentSessionEntity): Long
    @Query("UPDATE agent_session SET updatedAt = :now, title = :title WHERE id = :id")
    suspend fun touchSession(id: Long, title: String, now: Long)
    @Query("DELETE FROM agent_session WHERE id = :id")
    suspend fun deleteSession(id: Long)
    @Query("DELETE FROM agent_session WHERE updatedAt < :before")
    suspend fun pruneSessions(before: Long)
    @Query("SELECT * FROM agent_item WHERE sessionId = :sessionId ORDER BY id")
    fun observeItems(sessionId: Long): Flow<List<AgentItemEntity>>
    @Query("SELECT * FROM agent_item WHERE sessionId = :sessionId ORDER BY id")
    suspend fun items(sessionId: Long): List<AgentItemEntity>
    @Insert
    suspend fun insertItem(entity: AgentItemEntity): Long
    @Query("SELECT * FROM agent_memory ORDER BY id")
    fun observeMemory(): Flow<List<AgentMemoryEntity>>
    @Query("SELECT * FROM agent_memory ORDER BY id")
    suspend fun memory(): List<AgentMemoryEntity>
    @Insert
    suspend fun insertMemory(entity: AgentMemoryEntity): Long
    @Query("DELETE FROM agent_memory WHERE id = :id")
    suspend fun deleteMemory(id: Long)
    @Query("SELECT * FROM agent_automation ORDER BY id")
    fun observeAutomations(): Flow<List<AgentAutomationEntity>>
    @Query("SELECT * FROM agent_automation WHERE enabled = 1 AND trigger = :trigger ORDER BY id")
    suspend fun enabledAutomations(trigger: String): List<AgentAutomationEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAutomation(entity: AgentAutomationEntity): Long
    @Query("UPDATE agent_automation SET lastRunAt = :at WHERE id = :id")
    suspend fun markAutomationRun(id: Long, at: Long)
    @Query("DELETE FROM agent_automation WHERE id = :id")
    suspend fun deleteAutomation(id: Long)
    @Query("SELECT * FROM agent_log ORDER BY id DESC LIMIT :limit")
    fun observeLog(limit: Int): Flow<List<AgentLogEntity>>
    @Insert
    suspend fun insertLog(entity: AgentLogEntity)
    @Query("DELETE FROM agent_log WHERE at < :before")
    suspend fun pruneLog(before: Long)
}
