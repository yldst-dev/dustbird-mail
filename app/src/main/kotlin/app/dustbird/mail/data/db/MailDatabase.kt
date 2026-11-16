package app.dustbird.mail.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        AccountEntity::class, IdentityEntity::class, FolderEntity::class, MessageEntity::class, MessageBodyEntity::class,
        DraftEntity::class, OutboxEntity::class, PendingFlagEntity::class, NotifiedEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class MailDatabase : RoomDatabase() {
    abstract fun accounts(): AccountDao
    abstract fun identities(): IdentityDao
    abstract fun folders(): FolderDao
    abstract fun messages(): MessageDao
    abstract fun drafts(): DraftDao
    abstract fun outbox(): OutboxDao
    abstract fun pendingFlags(): PendingFlagDao
    abstract fun notified(): NotifiedDao
    abstract fun sync(): SyncDao

    companion object {
        fun create(context: Context): MailDatabase =
            Room.databaseBuilder(context, MailDatabase::class.java, "mail.db").build()
    }
}
