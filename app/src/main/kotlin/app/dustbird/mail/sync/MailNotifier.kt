package app.dustbird.mail.sync

import app.dustbird.mail.core.model.MailAccount

data class NewMail(
    val messageRowId: Long,
    val sender: String,
    val subject: String,
    val preview: String,
    val identityLabel: String?,
)

interface MailNotifier {
    fun notifyNew(account: MailAccount, mails: List<NewMail>)
    fun reauthRequired(account: MailAccount)
    fun cancel(accountId: Long)
    fun prune(existingAccountIds: Set<Long>)
    fun reauthResolved(accountId: Long) = Unit
    fun sendFailed(outboxId: Long, subject: String, watched: Boolean) = Unit
    suspend fun sweep(accountId: Long, stillUnread: suspend (List<Long>) -> Set<Long>) = Unit
}

object NoopNotifier : MailNotifier {
    override fun notifyNew(account: MailAccount, mails: List<NewMail>) = Unit
    override fun reauthRequired(account: MailAccount) = Unit
    override fun cancel(accountId: Long) = Unit
    override fun prune(existingAccountIds: Set<Long>) = Unit
}
