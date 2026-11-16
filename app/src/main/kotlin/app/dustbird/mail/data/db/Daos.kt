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

@Dao
interface MessageDao {
    @Query(
        "SELECT m.* FROM message m JOIN folder f ON f.id = m.folderId WHERE f.accountId = :accountId AND f.role = :role " +
            "ORDER BY m.sortDate DESC, m.id DESC LIMIT :limit",
    )
    fun observeByAccountAndRole(accountId: Long, role: FolderRole, limit: Int): Flow<List<MessageEntity>>

    @Query(
        "SELECT m.* FROM message m JOIN folder f ON f.id = m.folderId WHERE f.role = :role AND m.id IN (" +
            "SELECT MIN(m2.id) FROM message m2 JOIN folder f2 ON f2.id = m2.folderId WHERE f2.role = :role " +
            "GROUP BY m2.accountId, COALESCE(m2.messageId, 'row:' || m2.id)) " +
            "ORDER BY m.sortDate DESC, m.id DESC LIMIT :limit",
    )
    fun observeUnified(role: FolderRole, limit: Int): Flow<List<MessageEntity>>

    @Query("SELECT * FROM message WHERE id = :id")
    fun observe(id: Long): Flow<MessageEntity?>

    @Query("SELECT * FROM message WHERE id = :id")
    suspend fun get(id: Long): MessageEntity?

    @Query("SELECT uid FROM message WHERE folderId = :folderId")
    suspend fun uids(folderId: Long): List<Long>

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
            "WHERE f.role = 'INBOX' AND m.seen = 0 GROUP BY f.accountId",
    )
    fun observeUnreadCounts(): Flow<List<UnreadCount>>

    @Query("SELECT * FROM message_body WHERE messageRowId = :id")
    suspend fun body(id: Long): MessageBodyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBody(entity: MessageBodyEntity)
}

@Dao
interface DraftDao {
    @Query("SELECT * FROM draft ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<DraftEntity>>

    @Query("SELECT * FROM draft WHERE id = :id")
    suspend fun get(id: Long): DraftEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DraftEntity): Long

    @Query("DELETE FROM draft WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE draft SET accountId = :accountId, identityId = :fallbackId WHERE identityId = :identityId")
    suspend fun moveIdentity(identityId: Long, fallbackId: Long, accountId: Long)

    @Query("SELECT COUNT(*) FROM draft WHERE accountId = :accountId")
    suspend fun countForAccount(accountId: Long): Int
}

@Dao
interface OutboxDao {
    @Query("SELECT * FROM outbox ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<OutboxEntity>>

    @Query("SELECT * FROM outbox WHERE id = :id")
    suspend fun get(id: Long): OutboxEntity?

    @Query("SELECT * FROM outbox WHERE accountId = :accountId AND state IN (:states) ORDER BY createdAt")
    suspend fun byAccountAndStates(accountId: Long, states: List<SendState>): List<OutboxEntity>

    @Query("SELECT * FROM outbox WHERE state = 'SENDING'")
    suspend fun sending(): List<OutboxEntity>

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
