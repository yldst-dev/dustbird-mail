package app.dustbird.mail.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        AccountEntity::class, IdentityEntity::class, FolderEntity::class, MessageEntity::class, MessageBodyEntity::class,
        DraftEntity::class, OutboxEntity::class, PendingFlagEntity::class, NotifiedEntity::class, DraftAttachmentEntity::class,
        AgentSessionEntity::class, AgentItemEntity::class, AgentMemoryEntity::class, AgentAutomationEntity::class, AgentLogEntity::class,
    ],
    version = 3,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
)
abstract class MailDatabase : RoomDatabase() {
    abstract fun accounts(): AccountDao
    abstract fun identities(): IdentityDao
    abstract fun folders(): FolderDao
    abstract fun messages(): MessageDao
    abstract fun drafts(): DraftDao
    abstract fun draftAttachments(): DraftAttachmentDao
    abstract fun agent(): AgentDao
    abstract fun outbox(): OutboxDao
    abstract fun pendingFlags(): PendingFlagDao
    abstract fun notified(): NotifiedDao
    abstract fun sync(): SyncDao
    abstract fun maintenance(): MaintenanceDao

    companion object {
        fun create(context: Context): MailDatabase =
            Room.databaseBuilder(context, MailDatabase::class.java, "mail.db").build()
    }
}
