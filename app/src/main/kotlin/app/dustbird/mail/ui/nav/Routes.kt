package app.dustbird.mail.ui.nav

import android.net.Uri
import app.dustbird.mail.core.model.EmailAddress


enum class ComposeMode { NEW, REPLY, REPLY_ALL, FORWARD }

data class ComposeArgs(
    val draftId: Long? = null,
    val mode: ComposeMode = ComposeMode.NEW,
    val sourceMessageId: Long? = null,
)

data class ComposePrefill(
    val to: List<EmailAddress> = emptyList(),
    val cc: List<EmailAddress> = emptyList(),
    val bcc: List<EmailAddress> = emptyList(),
    val subject: String = "",
    val body: String = "",
    val streams: List<Uri> = emptyList(),
)

object Routes {
    const val MAILBOX = "mailbox"
    const val OUTBOX = "outbox"
    const val DRAFTS = "drafts"
    const val ACCOUNTS = "accounts"
    const val ADD_ACCOUNT = "accounts/add"
    const val SETTINGS = "settings"
    const val LICENSES = "licenses"
    const val SEARCH = "search"
    const val AGENT = "agent?message={message}&session={session}"
    const val AGENT_SETTINGS = "agent/settings"
    const val MESSAGE = "message/{id}"
    const val ACCOUNT_DETAIL = "accounts/{id}"
    const val COMPOSE = "compose?draft={draft}&mode={mode}&source={source}"

    fun message(id: Long) = "message/$id"
    fun accountDetail(id: Long) = "accounts/$id"
    fun agent(messageId: Long? = null, sessionId: Long? = null) = "agent?message=${messageId ?: -1}&session=${sessionId ?: -1}"
    fun compose(args: ComposeArgs) = "compose?draft=${args.draftId ?: -1}&mode=${args.mode.name}&source=${args.sourceMessageId ?: -1}"
}
