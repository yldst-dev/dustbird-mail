package app.dustbird.mail.ui.accounts.add

import androidx.annotation.StringRes
import androidx.compose.ui.semantics.role
import app.dustbird.mail.R
import app.dustbird.mail.core.identity.Rejection
import app.dustbird.mail.core.model.FolderRole
import app.dustbird.mail.core.net.ConnectFailure
import app.dustbird.mail.core.provider.Providers

class ServiceTexts(
    @StringRes val name: Int,
    @StringRes val hint: Int,
    @StringRes val secret: Int,
    val checklist: List<Int>,
    val notes: Set<Int>,
)

fun serviceTexts(providerId: String): ServiceTexts = when (providerId) {
    Providers.ICLOUD.id -> ServiceTexts(
        R.string.add_account_service_icloud, R.string.add_account_service_icloud_hint, R.string.add_account_secret_icloud,
        listOf(R.string.add_account_prepare_icloud_1, R.string.add_account_prepare_icloud_2, R.string.add_account_prepare_icloud_3),
        setOf(R.string.add_account_prepare_icloud_3),
    )
    Providers.GMAIL.id -> ServiceTexts(
        R.string.add_account_service_gmail, R.string.add_account_service_gmail_hint, R.string.add_account_secret_gmail,
        listOf(
            R.string.add_account_prepare_gmail_1, R.string.add_account_prepare_gmail_2, R.string.add_account_prepare_gmail_3,
            R.string.add_account_prepare_gmail_4, R.string.add_account_prepare_gmail_5,
        ),
        setOf(R.string.add_account_prepare_gmail_1, R.string.add_account_prepare_gmail_4, R.string.add_account_prepare_gmail_5),
    )
    Providers.NAVER.id -> ServiceTexts(
        R.string.add_account_service_naver, R.string.add_account_service_naver_hint, R.string.add_account_secret_naver,
        listOf(
            R.string.add_account_prepare_naver_1, R.string.add_account_prepare_naver_2, R.string.add_account_prepare_naver_3,
            R.string.add_account_prepare_naver_4, R.string.add_account_prepare_naver_5,
        ),
        setOf(R.string.add_account_prepare_naver_4, R.string.add_account_prepare_naver_5),
    )
    Providers.OUTLOOK.id -> ServiceTexts(
        R.string.add_account_service_outlook, R.string.add_account_service_outlook_hint, R.string.microsoft_client_id,
        listOf(
            R.string.add_account_prepare_outlook_2, R.string.add_account_prepare_outlook_3,
            R.string.add_account_prepare_outlook_4, R.string.add_account_prepare_outlook_5,
        ),
        setOf(R.string.add_account_prepare_outlook_3, R.string.add_account_prepare_outlook_4, R.string.add_account_prepare_outlook_5),
    )
    else -> ServiceTexts(
        R.string.add_account_service_custom, R.string.add_account_service_custom_hint, R.string.add_account_secret_custom,
        listOf(R.string.add_account_prepare_custom_1, R.string.add_account_prepare_custom_2, R.string.add_account_prepare_custom_3),
        setOf(R.string.add_account_prepare_custom_3),
    )
}

@StringRes
fun failureTitle(failure: ConnectFailure): Int = when (failure) {
    ConnectFailure.AUTH -> R.string.add_account_fail_auth_title
    ConnectFailure.TLS -> R.string.add_account_fail_tls_title
    ConnectFailure.UNREACHABLE -> R.string.add_account_fail_unreachable_title
    ConnectFailure.SERVICE_DISABLED -> R.string.add_account_fail_disabled_title
    ConnectFailure.OTHER -> R.string.add_account_fail_other_title
}

fun failureBodies(failure: ConnectFailure, providerId: String?): List<Int> = when (failure) {
    ConnectFailure.AUTH -> listOf(
        R.string.add_account_fail_auth_body,
        when (providerId) {
            Providers.ICLOUD.id -> R.string.add_account_fail_auth_icloud
            Providers.GMAIL.id -> R.string.add_account_fail_auth_gmail
            Providers.NAVER.id -> R.string.add_account_fail_auth_naver
            Providers.OUTLOOK.id -> R.string.add_account_fail_auth_outlook
            else -> R.string.add_account_fail_auth_custom
        },
    )
    ConnectFailure.TLS -> listOf(R.string.add_account_fail_tls_body)
    ConnectFailure.UNREACHABLE -> listOf(R.string.add_account_fail_unreachable_body)
    ConnectFailure.SERVICE_DISABLED -> listOf(
        R.string.add_account_fail_disabled_body,
        when (providerId) {
            Providers.NAVER.id -> R.string.add_account_fail_disabled_naver
            Providers.GMAIL.id -> R.string.add_account_fail_disabled_gmail
            else -> R.string.add_account_fail_disabled_default
        },
    )
    ConnectFailure.OTHER -> listOf(R.string.add_account_fail_other_body)
}

@StringRes
fun rejectionText(reason: Rejection): Int = when (reason) {
    Rejection.DUPLICATE_MAILBOX -> R.string.add_account_save_duplicate_mailbox
    Rejection.INVALID_ADDRESS -> R.string.add_account_save_invalid_address
    Rejection.DUPLICATE_ADDRESS -> R.string.add_account_save_duplicate_address
    Rejection.DUPLICATE_LABEL -> R.string.add_account_save_duplicate_label
    Rejection.BLANK_LABEL -> R.string.add_account_save_blank_label
    else -> R.string.add_account_save_other
}

@StringRes
fun roleName(role: FolderRole): Int = when (role) {
    FolderRole.SENT -> R.string.add_account_role_sent
    FolderRole.DRAFTS -> R.string.add_account_role_drafts
    else -> R.string.add_account_role_spam
}

@StringRes
fun stepTitle(step: AddStep): Int = when (step) {
    AddStep.CHOOSE -> R.string.add_account_title_choose
    AddStep.PREPARE -> R.string.add_account_title_prepare
    AddStep.FORM -> R.string.add_account_title_form
    AddStep.VERIFY -> R.string.add_account_title_verify
    AddStep.SYNC -> R.string.add_account_title_sync
    AddStep.ROLES -> R.string.add_account_title_roles
}
