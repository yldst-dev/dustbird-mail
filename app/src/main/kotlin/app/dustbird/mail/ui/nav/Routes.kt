package app.dustbird.mail.ui.nav

enum class ComposeMode { NEW, REPLY, REPLY_ALL, FORWARD }

data class ComposeArgs(
    val draftId: Long? = null,
    val mode: ComposeMode = ComposeMode.NEW,
    val sourceMessageId: Long? = null,
)

object Routes {
    const val MAILBOX = "mailbox"
    const val OUTBOX = "outbox"
    const val ACCOUNTS = "accounts"
    const val ADD_ACCOUNT = "accounts/add"
    const val SETTINGS = "settings"
    const val LICENSES = "licenses"
    const val MESSAGE = "message/{id}"
    const val ACCOUNT_DETAIL = "accounts/{id}"
    const val COMPOSE = "compose?draft={draft}&mode={mode}&source={source}"

    fun message(id: Long) = "message/$id"
    fun accountDetail(id: Long) = "accounts/$id"
    fun compose(args: ComposeArgs) = "compose?draft=${args.draftId ?: -1}&mode=${args.mode.name}&source=${args.sourceMessageId ?: -1}"
}
