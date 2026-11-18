package app.dustbird.mail.core.model

enum class TransportSecurity { TLS, STARTTLS }

data class ServerEndpoint(val host: String, val port: Int, val security: TransportSecurity)

enum class AuthMethod { APP_PASSWORD, OAUTH2 }

enum class UsernameStyle { FULL_ADDRESS, LOCAL_PART }

enum class FolderRole { INBOX, SENT, DRAFTS, SPAM, TRASH, ARCHIVE, ALL }

enum class SendVerification { UNVERIFIED, VERIFIED, FAILED }

enum class SyncMode { MANUAL, PERIODIC, IDLE }

enum class StageState { UNKNOWN, OK, FAILED }

data class ConnectionStages(
    val imapAuth: StageState = StageState.UNKNOWN,
    val smtpAuth: StageState = StageState.UNKNOWN,
    val send: StageState = StageState.UNKNOWN,
    val receive: StageState = StageState.UNKNOWN,
    val sentCopy: StageState = StageState.UNKNOWN,
)

data class EmailAddress(val address: String, val name: String? = null)

data class MailAccount(
    val id: Long,
    val providerId: String,
    val displayName: String,
    val label: String,
    val username: String,
    val imap: ServerEndpoint,
    val smtp: ServerEndpoint,
    val authMethod: AuthMethod,
    val sortOrder: Int,
    val syncMode: SyncMode,
    val notificationsEnabled: Boolean,
    val needsReauth: Boolean = false,
    val stages: ConnectionStages = ConnectionStages(),
)

data class MailIdentity(
    val id: Long,
    val accountId: Long,
    val address: String,
    val displayName: String,
    val signature: String,
    val label: String,
    val sortOrder: Int,
    val isAccountDefault: Boolean,
    val verification: SendVerification,
)

data class Credentials(val username: String, val secret: String) {
    val bearer: Boolean get() = secret.startsWith(BEARER_PREFIX)
    val password: String get() = secret.removePrefix(BEARER_PREFIX)

    override fun toString(): String = "Credentials(username=***, secret=***)"

    companion object {
        const val BEARER_PREFIX = "oauth2-bearer:"
    }
}
