package app.dustbird.mail.data.repo

import androidx.room.withTransaction
import app.dustbird.mail.data.SettingsRepository
import app.dustbird.mail.data.db.MailDatabase
import app.dustbird.mail.data.secure.CredentialStore
import app.dustbird.mail.sync.MailNotifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.dustbird.mail.data.AttachmentFiles

class MaintenanceRepository(
    private val db: MailDatabase,
    private val credentials: CredentialStore,
    private val settings: SettingsRepository,
    private val notifier: MailNotifier,
    private val files: AttachmentFiles,
) {
    suspend fun clearAccountCache(accountId: Long) {
        val messageIds = db.messages().idsForAccount(accountId)
        withContext(Dispatchers.IO) { files.clearDownloads(messageIds) }
        db.withTransaction {
            db.maintenance().deleteMessages(accountId)
            db.maintenance().resetFolders(accountId)
            db.maintenance().deletePending(accountId)
            db.maintenance().deleteNotified(accountId)
        }
        notifier.cancel(accountId)
    }

    suspend fun resetEverything() {
        val ids = db.accounts().getAll().map { it.id }
        withContext(Dispatchers.IO) { db.clearAllTables() }
        credentials.deleteAll()
        withContext(Dispatchers.IO) { files.clearAll() }
        settings.clearAll()
        ids.forEach(notifier::cancel)
    }
}
