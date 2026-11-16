package app.dustbird.mail.ui.accounts.manage

import androidx.annotation.StringRes
import app.dustbird.mail.R
import app.dustbird.mail.core.identity.Rejection
import app.dustbird.mail.core.model.ConnectionStages
import app.dustbird.mail.core.model.MailAccount
import app.dustbird.mail.core.model.SendVerification
import app.dustbird.mail.core.model.StageState
import app.dustbird.mail.core.model.SyncMode
import app.dustbird.mail.core.net.ConnectFailure
import app.dustbird.mail.core.provider.Providers
import app.dustbird.mail.sync.AccountSyncState

@StringRes
fun StageState.labelRes(): Int = when (this) {
    StageState.UNKNOWN -> R.string.accounts_stage_unknown
    StageState.OK -> R.string.accounts_stage_ok
    StageState.FAILED -> R.string.accounts_stage_failed
}

@StringRes
fun SendVerification.labelRes(): Int = when (this) {
    SendVerification.UNVERIFIED -> R.string.accounts_verification_unverified
    SendVerification.VERIFIED -> R.string.accounts_verification_verified
    SendVerification.FAILED -> R.string.accounts_verification_failed
}

@StringRes
fun ConnectFailure.messageRes(): Int = when (this) {
    ConnectFailure.AUTH -> R.string.accounts_failure_auth
    ConnectFailure.TLS -> R.string.accounts_failure_tls
    ConnectFailure.UNREACHABLE -> R.string.accounts_failure_unreachable
    ConnectFailure.SERVICE_DISABLED -> R.string.accounts_failure_service_disabled
    ConnectFailure.OTHER -> R.string.accounts_failure_other
}

@StringRes
fun Rejection.messageRes(): Int = when (this) {
    Rejection.INVALID_ADDRESS -> R.string.accounts_rejection_invalid_address
    Rejection.DUPLICATE_ADDRESS -> R.string.accounts_rejection_duplicate_address
    Rejection.DUPLICATE_LABEL -> R.string.accounts_rejection_duplicate_label
    Rejection.BLANK_LABEL -> R.string.accounts_rejection_blank_label
    Rejection.LAST_IDENTITY -> R.string.accounts_rejection_last_identity
    Rejection.UNKNOWN_IDENTITY -> R.string.accounts_rejection_unknown_identity
    Rejection.DUPLICATE_MAILBOX -> R.string.accounts_rejection_duplicate_mailbox
}

@StringRes
fun SyncMode.labelRes(): Int = when (this) {
    SyncMode.MANUAL -> R.string.accounts_sync_manual
    SyncMode.PERIODIC -> R.string.accounts_sync_periodic
    SyncMode.IDLE -> R.string.accounts_sync_idle
}

@StringRes
fun SyncMode.descriptionRes(): Int = when (this) {
    SyncMode.MANUAL -> R.string.accounts_sync_manual_desc
    SyncMode.PERIODIC -> R.string.accounts_sync_periodic_desc
    SyncMode.IDLE -> R.string.accounts_sync_idle_desc
}

@StringRes
fun providerNameRes(providerId: String): Int = when (providerId) {
    Providers.ICLOUD.id -> R.string.accounts_service_icloud
    Providers.GMAIL.id -> R.string.accounts_service_gmail
    Providers.NAVER.id -> R.string.accounts_service_naver
    else -> R.string.accounts_service_custom
}

fun MailAccount.reauthRequired(state: AccountSyncState?): Boolean = needsReauth || state is AccountSyncState.NeedsReauth

fun MailAccount.hasConnectionProblem(state: AccountSyncState?): Boolean = reauthRequired(state) || state is AccountSyncState.Error

@StringRes
fun connectionStatusRes(account: MailAccount, state: AccountSyncState?): Int =
    if (account.reauthRequired(state)) R.string.accounts_state_needs_reauth else when (state) {
        null, AccountSyncState.Idle -> R.string.accounts_state_ok
        AccountSyncState.Syncing -> R.string.accounts_state_syncing
        AccountSyncState.NeedsReauth -> R.string.accounts_state_needs_reauth
        is AccountSyncState.Error -> state.failure.messageRes()
    }

fun ConnectionStages.rows(): List<Pair<Int, StageState>> = listOf(
    R.string.accounts_stage_imap_auth to imapAuth,
    R.string.accounts_stage_smtp_auth to smtpAuth,
    R.string.accounts_stage_send to send,
    R.string.accounts_stage_receive to receive,
    R.string.accounts_stage_sent_copy to sentCopy,
)
