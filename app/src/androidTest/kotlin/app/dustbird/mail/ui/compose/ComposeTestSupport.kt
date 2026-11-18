package app.dustbird.mail.ui.compose

import android.app.Application
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import app.dustbird.mail.AppContainer
import app.dustbird.mail.core.agent.AgentBackend
import app.dustbird.mail.core.model.EmailAddress
import app.dustbird.mail.core.model.AuthMethod
import app.dustbird.mail.core.model.FolderRole
import app.dustbird.mail.core.model.SendVerification
import app.dustbird.mail.core.model.SyncMode
import app.dustbird.mail.core.model.TransportSecurity
import app.dustbird.mail.data.Codec
import app.dustbird.mail.data.db.AccountEntity
import app.dustbird.mail.data.db.FolderEntity
import app.dustbird.mail.data.db.IdentityEntity
import app.dustbird.mail.data.db.MailDatabase
import app.dustbird.mail.data.db.MessageBodyEntity
import app.dustbird.mail.data.db.MessageEntity
import app.dustbird.mail.data.secure.CredentialStore
import app.dustbird.mail.ui.nav.ComposeArgs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

internal class FakeCredentialStore : CredentialStore {
    private val secrets = mutableMapOf<Long, String>()

    override fun put(accountId: Long, secret: String) {
        secrets[accountId] = secret
    }

    override fun get(accountId: Long): String? = secrets[accountId]

    override fun delete(accountId: Long) {
        secrets.remove(accountId)
    }

    override fun deleteAll() = secrets.clear()
}

internal data class SeededIdentity(val accountId: Long, val identityId: Long, val address: String)

internal data class SeededMessage(val accountId: Long, val identityId: Long, val messageId: Long)

internal class ComposeFixture(agentBackend: AgentBackend? = null) {
    val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    val db: MailDatabase = Room.inMemoryDatabaseBuilder(context, MailDatabase::class.java).build()
    val container = AppContainer(context, credentials = FakeCredentialStore(), db = db, agentBackend = agentBackend)
    private var previousDefault: Long? = null
    private var previousDelay = 0

    fun open() = runBlocking {
        previousDefault = container.accounts.appDefaultIdentityId.first()
        previousDelay = container.settings.sendDelaySeconds.first()
        container.settings.setSendDelaySeconds(0)
    }

    fun close() {
        runBlocking {
            container.accounts.setAppDefaultIdentity(previousDefault)
            container.settings.setSendDelaySeconds(previousDelay)
        }
        container.scope.cancel()
        db.close()
    }

    fun seedAccount(key: String, signature: String = "", providerId: String = "custom", address: String = "$key@example.com", username: String = address): SeededIdentity = runBlocking {
        val accountId = db.accounts().insert(
            AccountEntity(
                providerId = providerId, displayName = key, label = key, username = username, imapHost = "imap.$key.test", imapPort = 993,
                imapSecurity = TransportSecurity.TLS, smtpHost = "smtp.$key.test", smtpPort = 587, smtpSecurity = TransportSecurity.STARTTLS,
                authMethod = AuthMethod.APP_PASSWORD, sortOrder = db.accounts().nextSortOrder(), syncMode = SyncMode.MANUAL,
                notificationsEnabled = true, needsReauth = false, mailboxKey = key,
            ),
        )
        val identityId = db.identities().insert(
            IdentityEntity(
                accountId = accountId, address = address, addressKey = address, displayName = "", signature = signature, label = key,
                labelKey = key, sortOrder = 0, isAccountDefault = true, verification = SendVerification.UNVERIFIED,
            ),
        )
        SeededIdentity(accountId, identityId, address)
    }

    fun setDefaultIdentity(identityId: Long) = runBlocking { container.accounts.setAppDefaultIdentity(identityId) }

    fun seedMessage(owner: SeededIdentity, fromAddress: String, subject: String, text: String, messageIdHeader: String, to: List<EmailAddress> = emptyList(), cc: List<EmailAddress> = emptyList()): SeededMessage = runBlocking {
        val folderId = db.folders().insert(
            FolderEntity(
                accountId = owner.accountId, path = "INBOX", delimiter = "/", attributes = "", role = FolderRole.INBOX, roleConfirmed = true,
                uidValidity = 1, highestUid = 1, baselineComplete = true, syncedAt = 0,
            ),
        )
        val id = db.messages().upsert(
            MessageEntity(
                accountId = owner.accountId, folderId = folderId, uid = 1, uidValidity = 1, messageId = messageIdHeader, inReplyTo = null,
                references = "", fromName = null, fromAddress = fromAddress, replyTo = "", toAddresses = Codec.addresses(to),
                ccAddresses = Codec.addresses(cc), deliveryValues = "", subject = subject, sortDate = 1_700_000_000_000, preview = "", size = 1,
                hasAttachment = false, seen = true, answered = false, flagged = false, allowRemoteImages = false,
            ),
        )
        db.messages().upsertBody(MessageBodyEntity(id, text, null, ""))
        SeededMessage(owner.accountId, owner.identityId, id)
    }

    fun openViewModel(
        args: ComposeArgs = ComposeArgs(),
        handle: SavedStateHandle = SavedStateHandle(),
    ): ComposeViewModel {
        lateinit var viewModel: ComposeViewModel
        onMain { viewModel = ComposeViewModel(container, context.applicationContext as Application, args, handle) }
        waitUntil { viewModel.loadState != LoadState.Loading }
        return viewModel
    }
}

internal fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

internal fun waitUntil(timeoutMillis: Long = 5000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (!condition()) {
        check(System.currentTimeMillis() < deadline) { "Condition was not met in time" }
        Thread.sleep(25)
    }
}
