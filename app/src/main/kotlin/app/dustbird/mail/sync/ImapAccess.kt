package app.dustbird.mail.sync

import app.dustbird.mail.core.model.Credentials
import app.dustbird.mail.core.model.ServerEndpoint
import app.dustbird.mail.core.net.ImapConnector
import app.dustbird.mail.core.net.ImapSession
import app.dustbird.mail.data.db.AccountEntity
import app.dustbird.mail.data.db.MailDatabase
import app.dustbird.mail.data.secure.CredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MissingCredentialException(val accountId: Long) : Exception("Credential is missing for account $accountId")

class ImapAccess(
    private val db: MailDatabase,
    private val credentials: CredentialStore,
    private val connector: ImapConnector,
) {
    suspend fun <T> use(accountId: Long, block: suspend (ImapSession, AccountEntity) -> T): T = withContext(Dispatchers.IO) {
        val account = db.accounts().get(accountId) ?: error("Unknown account $accountId")
        val secret = credentials.get(accountId) ?: throw MissingCredentialException(accountId)
        val endpoint = ServerEndpoint(account.imapHost, account.imapPort, account.imapSecurity)
        connector.connect(endpoint, Credentials(account.username, secret)).use { block(it, account) }
    }
}
