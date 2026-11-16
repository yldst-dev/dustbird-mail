package app.dustbird.mail

import android.app.Application
import android.content.Context
import app.dustbird.mail.core.net.ImapConnector
import app.dustbird.mail.core.net.JakartaImapConnector
import app.dustbird.mail.core.net.JakartaSmtpSender
import app.dustbird.mail.core.net.SmtpSender
import app.dustbird.mail.data.SettingsRepository
import app.dustbird.mail.data.db.MailDatabase
import app.dustbird.mail.data.repo.AccountRepository
import app.dustbird.mail.data.repo.DraftRepository
import app.dustbird.mail.data.repo.MailRepository
import app.dustbird.mail.data.repo.MaintenanceRepository
import app.dustbird.mail.data.secure.CredentialStore
import app.dustbird.mail.data.secure.KeystoreCredentialStore
import app.dustbird.mail.sync.ImapAccess
import app.dustbird.mail.sync.MailNotifier
import app.dustbird.mail.sync.NoopNotifier
import app.dustbird.mail.sync.SendService
import app.dustbird.mail.sync.SyncService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppContainer(
    private val context: Context,
    val connector: ImapConnector = JakartaImapConnector,
    val smtpSender: SmtpSender = JakartaSmtpSender,
    val credentials: CredentialStore = KeystoreCredentialStore(context),
    val db: MailDatabase = MailDatabase.create(context),
    notifierFactory: (AppContainer) -> MailNotifier = { NoopNotifier },
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsRepository(context)
    val notifier: MailNotifier by lazy { notifierFactory(this) }
    val accounts: AccountRepository = AccountRepository(db, credentials, settings) { sync.forget(it) }
    val imapAccess = ImapAccess(db, credentials, connector)
    val sync: SyncService by lazy { SyncService(db, imapAccess, accounts, notifier) }
    val mail = MailRepository(db, accounts, imapAccess)
    val drafts = DraftRepository(db, imapAccess)
    val maintenance: MaintenanceRepository by lazy { MaintenanceRepository(db, credentials, settings, notifier) }
    val send: SendService by lazy { SendService(db, accounts, drafts, credentials, smtpSender, connector) }

    fun start() {
        scope.launch { send.recoverAfterCrash() }
    }
}

class MailApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        container.start()
    }
}
