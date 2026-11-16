package app.dustbird.mail.data

import app.dustbird.mail.core.mime.AttachmentInfo
import app.dustbird.mail.core.model.ConnectionStages
import app.dustbird.mail.core.model.EmailAddress
import app.dustbird.mail.core.model.MailAccount
import app.dustbird.mail.core.model.MailIdentity
import app.dustbird.mail.core.model.ServerEndpoint
import app.dustbird.mail.data.db.AccountEntity
import app.dustbird.mail.data.db.IdentityEntity

object Codec {
    private const val FIELD = '\u001F'
    private const val ITEM = '\u001E'

    private fun clean(value: String?): String = value.orEmpty().filterNot { it == FIELD || it == ITEM }

    fun addresses(list: List<EmailAddress>): String = list.joinToString(ITEM.toString()) { "${clean(it.name)}$FIELD${clean(it.address)}" }

    fun parseAddresses(value: String): List<EmailAddress> = value.split(ITEM).filter { it.isNotEmpty() }.map {
        val parts = it.split(FIELD)
        EmailAddress(parts.getOrElse(1) { "" }, parts.getOrNull(0)?.takeIf { name -> name.isNotEmpty() })
    }

    fun strings(list: List<String>): String = list.joinToString(ITEM.toString()) { clean(it) }

    fun parseStrings(value: String): List<String> = value.split(ITEM).filter { it.isNotEmpty() }

    fun attachments(list: List<AttachmentInfo>): String =
        list.joinToString(ITEM.toString()) { "${clean(it.fileName)}$FIELD${clean(it.mimeType)}$FIELD${it.size}" }

    fun parseAttachments(value: String): List<AttachmentInfo> = value.split(ITEM).filter { it.isNotEmpty() }.map {
        val parts = it.split(FIELD)
        AttachmentInfo(parts[0].ifEmpty { null }, parts.getOrElse(1) { "" }, parts.getOrNull(2)?.toIntOrNull() ?: -1)
    }
}

fun AccountEntity.toModel() = MailAccount(
    id = id,
    providerId = providerId,
    displayName = displayName,
    label = label,
    username = username,
    imap = ServerEndpoint(imapHost, imapPort, imapSecurity),
    smtp = ServerEndpoint(smtpHost, smtpPort, smtpSecurity),
    authMethod = authMethod,
    sortOrder = sortOrder,
    syncMode = syncMode,
    notificationsEnabled = notificationsEnabled,
    needsReauth = needsReauth,
    stages = ConnectionStages(stageImapAuth, stageSmtpAuth, stageSend, stageReceive, stageSentCopy),
)

fun IdentityEntity.toModel() = MailIdentity(id, accountId, address, displayName, signature, label, sortOrder, isAccountDefault, verification)
