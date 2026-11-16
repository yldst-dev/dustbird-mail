package app.dustbird.mail.core

import app.dustbird.mail.core.model.AuthMethod
import app.dustbird.mail.core.model.MailAccount
import app.dustbird.mail.core.model.MailIdentity
import app.dustbird.mail.core.model.SendVerification
import app.dustbird.mail.core.model.ServerEndpoint
import app.dustbird.mail.core.model.SyncMode
import app.dustbird.mail.core.model.TransportSecurity

fun identity(
    id: Long,
    accountId: Long,
    address: String,
    label: String = "label$id",
    sortOrder: Int = id.toInt(),
    isDefault: Boolean = false,
) = MailIdentity(id, accountId, address, "", "", label, sortOrder, isDefault, SendVerification.UNVERIFIED)

fun account(id: Long, host: String = "imap.example.test", username: String = "user$id@example.test") = MailAccount(
    id = id,
    providerId = "custom",
    displayName = "account$id",
    label = "a$id",
    username = username,
    imap = ServerEndpoint(host, 993, TransportSecurity.TLS),
    smtp = ServerEndpoint("smtp.example.test", 587, TransportSecurity.STARTTLS),
    authMethod = AuthMethod.APP_PASSWORD,
    sortOrder = id.toInt(),
    syncMode = SyncMode.MANUAL,
    notificationsEnabled = true,
)
