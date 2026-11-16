package app.dustbird.mail.ui.outbox

import androidx.annotation.StringRes
import app.dustbird.mail.R
import app.dustbird.mail.core.net.RejectKind
import app.dustbird.mail.core.send.FailureCodes
import app.dustbird.mail.core.send.SendState

enum class OutboxAction(@StringRes val label: Int) {
    RETRY(R.string.outbox_action_retry),
    DISCARD(R.string.outbox_action_discard),
    CHECK_UNKNOWN(R.string.outbox_action_check_unknown),
    CONFIRM_SENT(R.string.outbox_action_confirm_sent),
    RESEND(R.string.outbox_action_resend),
    RETRY_SAVE(R.string.outbox_action_retry_save),
}

data class OutboxPresentation(
    @StringRes val status: Int,
    @StringRes val detail: Int,
    val detailArg: String? = null,
    val actions: List<OutboxAction> = emptyList(),
    val inProgress: Boolean = false,
    val failed: Boolean = false,
)

fun outboxPresentation(state: SendState, failure: String?): OutboxPresentation = when (state) {
    SendState.DRAFT, SendState.QUEUED -> OutboxPresentation(
        status = R.string.outbox_status_queued,
        detail = if (failure == null) R.string.outbox_detail_queued else R.string.outbox_detail_queued_retry,
        inProgress = true,
    )
    SendState.SENDING -> OutboxPresentation(R.string.outbox_status_sending, R.string.outbox_detail_sending, inProgress = true)
    SendState.ACCEPTED -> OutboxPresentation(R.string.outbox_status_accepted, R.string.outbox_detail_accepted, inProgress = true)
    SendState.SAVED -> OutboxPresentation(R.string.outbox_status_saved, R.string.outbox_detail_saved, actions = listOf(OutboxAction.DISCARD))
    SendState.FAILED -> failedPresentation(failure)
    SendState.UNKNOWN -> OutboxPresentation(
        status = R.string.outbox_status_unknown,
        detail = R.string.outbox_detail_unknown,
        actions = listOf(OutboxAction.CHECK_UNKNOWN, OutboxAction.CONFIRM_SENT, OutboxAction.RESEND),
        failed = true,
    )
    SendState.SAVE_FAILED -> OutboxPresentation(
        status = R.string.outbox_status_save_failed,
        detail = when (failure) {
            FailureCodes.SAVE_NO_FOLDER -> R.string.outbox_detail_save_no_folder
            FailureCodes.SAVE_NOT_BY_SERVER -> R.string.outbox_detail_save_not_by_server
            else -> R.string.outbox_detail_save_error
        },
        actions = listOf(OutboxAction.RETRY_SAVE, OutboxAction.DISCARD),
    )
}

private fun failedPresentation(failure: String?): OutboxPresentation {
    val kind = failure?.substringBefore('|')
    val code = failure?.substringAfter('|', "")?.takeIf { it.isNotEmpty() }
    val detail = when (kind) {
        RejectKind.AUTH.name -> R.string.outbox_detail_failed_auth
        RejectKind.TLS.name -> R.string.outbox_detail_failed_tls
        RejectKind.SENDER.name -> R.string.outbox_detail_failed_sender
        RejectKind.RECIPIENT.name -> R.string.outbox_detail_failed_recipient
        RejectKind.SERVER.name -> if (code != null) R.string.outbox_detail_failed_server_code else R.string.outbox_detail_failed_server
        FailureCodes.NETWORK -> R.string.outbox_detail_failed_network
        else -> R.string.outbox_detail_failed_other
    }
    return OutboxPresentation(
        status = R.string.outbox_status_failed,
        detail = detail,
        detailArg = code.takeIf { kind == RejectKind.SERVER.name },
        actions = listOf(OutboxAction.RETRY, OutboxAction.DISCARD),
        failed = true,
    )
}
