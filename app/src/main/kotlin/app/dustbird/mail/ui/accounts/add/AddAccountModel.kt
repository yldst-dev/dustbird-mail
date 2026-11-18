package app.dustbird.mail.ui.accounts.add

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.role
import app.dustbird.mail.R
import app.dustbird.mail.core.folder.FolderInfo
import app.dustbird.mail.core.folder.FolderRoles
import app.dustbird.mail.core.folder.RoleResolution
import app.dustbird.mail.core.identity.Addresses
import app.dustbird.mail.core.identity.Rejection
import app.dustbird.mail.core.model.FolderRole
import app.dustbird.mail.core.model.TransportSecurity
import app.dustbird.mail.core.net.CheckResult
import app.dustbird.mail.core.provider.ProviderProfile
import app.dustbird.mail.data.db.FolderEntity
import app.dustbird.mail.sync.SyncOutcome

enum class AddStep { CHOOSE, PREPARE, FORM, VERIFY, SYNC, ROLES }

enum class FormField { ADDRESS, PASSWORD, DISPLAY_NAME, LABEL, USERNAME, IMAP_HOST, IMAP_PORT, SMTP_HOST, SMTP_PORT }

val advancedFields: Set<FormField> = setOf(FormField.USERNAME, FormField.IMAP_HOST, FormField.IMAP_PORT, FormField.SMTP_HOST, FormField.SMTP_PORT)

data class FormValues(
    val address: String,
    val password: String,
    val displayName: String,
    val label: String,
    val identityName: String,
    val username: String,
    val imapHost: String,
    val imapPort: String,
    val imapSecurity: TransportSecurity,
    val smtpHost: String,
    val smtpPort: String,
    val smtpSecurity: TransportSecurity,
) {
    override fun toString(): String = "FormValues(address=***, password=***)"
}

object FormRules {
    private val host = Regex("^(?=.{1,253}$)[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*$")

    fun isValidHost(value: String): Boolean = host.matches(value)

    fun parsePort(value: String): Int? =
        value.takeIf { it.isNotEmpty() && it.all { char -> char in '0'..'9' } }?.toIntOrNull()?.takeIf { it in 1..65535 }

    fun isComplete(values: FormValues): Boolean = values.password.isNotEmpty() &&
        listOf(values.address, values.displayName, values.label, values.username, values.imapHost, values.imapPort, values.smtpHost, values.smtpPort).none { it.isBlank() }

    fun invalidFields(values: FormValues): Set<FormField> = buildSet {
        if (!Addresses.isValid(values.address)) add(FormField.ADDRESS)
        if (values.password.isEmpty()) add(FormField.PASSWORD)
        if (values.displayName.isBlank()) add(FormField.DISPLAY_NAME)
        if (values.label.isBlank()) add(FormField.LABEL)
        if (values.username.isBlank()) add(FormField.USERNAME)
        if (!isValidHost(values.imapHost)) add(FormField.IMAP_HOST)
        if (parsePort(values.imapPort) == null) add(FormField.IMAP_PORT)
        if (!isValidHost(values.smtpHost)) add(FormField.SMTP_HOST)
        if (parsePort(values.smtpPort) == null) add(FormField.SMTP_PORT)
    }
}

class AddAccountFields {
    val address = TextFieldState()
    val password = TextFieldState()
    val displayName = TextFieldState()
    val label = TextFieldState()
    val identityName = TextFieldState()
    val username = TextFieldState()
    val imapHost = TextFieldState()
    val imapPort = TextFieldState()
    val smtpHost = TextFieldState()
    val smtpPort = TextFieldState()
    var imapSecurity by mutableStateOf(TransportSecurity.TLS)
    var smtpSecurity by mutableStateOf(TransportSecurity.STARTTLS)
    var advancedOpen by mutableStateOf(false)

    fun reset(profile: ProviderProfile, suggestedName: String) {
        listOf(address, password, identityName, username).forEach { it.clearText() }
        displayName.setTextAndPlaceCursorAtEnd(suggestedName)
        label.setTextAndPlaceCursorAtEnd(suggestedName)
        imapHost.setTextAndPlaceCursorAtEnd(profile.imap?.host.orEmpty())
        imapPort.setTextAndPlaceCursorAtEnd(profile.imap?.port?.toString().orEmpty())
        smtpHost.setTextAndPlaceCursorAtEnd(profile.smtp?.host.orEmpty())
        smtpPort.setTextAndPlaceCursorAtEnd(profile.smtp?.port?.toString().orEmpty())
        imapSecurity = profile.imap?.security ?: TransportSecurity.TLS
        smtpSecurity = profile.smtp?.security ?: TransportSecurity.STARTTLS
        advancedOpen = profile.imap == null
    }

    fun apply(suggestion: AccountSuggestion): List<Int> {
        val filled = LinkedHashSet<Int>()
        fun put(state: TextFieldState, value: String?, label: Int) {
            if (value == null) return
            state.setTextAndPlaceCursorAtEnd(value)
            filled += label
        }
        put(address, suggestion.address, R.string.add_account_field_address)
        put(displayName, suggestion.displayName, R.string.add_account_field_display_name)
        put(label, suggestion.label, R.string.add_account_field_label)
        put(identityName, suggestion.identityName, R.string.add_account_field_identity_name)
        put(username, suggestion.username, R.string.add_account_field_username)
        put(imapHost, suggestion.imapHost, R.string.add_account_section_imap)
        put(imapPort, suggestion.imapPort, R.string.add_account_section_imap)
        suggestion.imapSecurity?.let {
            imapSecurity = it
            filled += R.string.add_account_section_imap
        }
        put(smtpHost, suggestion.smtpHost, R.string.add_account_section_smtp)
        put(smtpPort, suggestion.smtpPort, R.string.add_account_section_smtp)
        suggestion.smtpSecurity?.let {
            smtpSecurity = it
            filled += R.string.add_account_section_smtp
        }
        if (R.string.add_account_field_username in filled || R.string.add_account_section_imap in filled || R.string.add_account_section_smtp in filled) advancedOpen = true
        return filled.toList()
    }

    fun values() = FormValues(
        address = address.text.trim().toString(),
        password = password.text.toString(),
        displayName = displayName.text.trim().toString(),
        label = label.text.trim().toString(),
        identityName = identityName.text.trim().toString(),
        username = username.text.trim().toString(),
        imapHost = imapHost.text.trim().toString(),
        imapPort = imapPort.text.trim().toString(),
        imapSecurity = imapSecurity,
        smtpHost = smtpHost.text.trim().toString(),
        smtpPort = smtpPort.text.trim().toString(),
        smtpSecurity = smtpSecurity,
    )
}

sealed interface Stage {
    data object Pending : Stage
    data object Running : Stage
    data class Done(val result: CheckResult) : Stage
}

val Stage.passed: Boolean get() = this is Stage.Done && result is CheckResult.Ok

sealed interface SaveError {
    data class Rejected(val reason: Rejection) : SaveError
    data object Storage : SaveError
}

data class RolePrompt(
    val role: FolderRole,
    val options: List<String>,
    val missing: Boolean,
    val selected: String? = null,
)

private val confirmableRoles = listOf(FolderRole.SENT, FolderRole.DRAFTS, FolderRole.SPAM)

fun rolePrompts(folders: List<FolderEntity>): List<RolePrompt> {
    val infos = folders.map { entity ->
        FolderInfo(
            path = entity.path,
            delimiter = entity.delimiter?.firstOrNull(),
            attributes = entity.attributes.split(' ').filter { it.isNotEmpty() }.toSet(),
        )
    }
    val confirmed = folders.filter { it.roleConfirmed && it.role != null }.associate { it.role!! to it.path }
    val resolved = FolderRoles.resolve(infos, confirmed)
    val assigned = resolved.values.mapNotNull { (it as? RoleResolution.Assigned)?.path }.toSet()
    val others = infos.filter { it.selectable && it.path !in assigned }.map { it.path }
    return confirmableRoles.mapNotNull { role ->
        when (val resolution = resolved.getValue(role)) {
            is RoleResolution.Assigned -> null
            is RoleResolution.NeedsConfirmation -> RolePrompt(role, resolution.candidates, missing = false)
            RoleResolution.Missing -> others.takeIf { it.isNotEmpty() }?.let { RolePrompt(role, it, missing = true) }
        }
    }
}

data class AddAccountUiState(
    val step: AddStep = AddStep.CHOOSE,
    val providerId: String? = null,
    val acknowledged: Boolean = false,
    val showErrors: Boolean = false,
    val imap: Stage = Stage.Pending,
    val smtp: Stage = Stage.Pending,
    val busy: Boolean = false,
    val saveError: SaveError? = null,
    val accountId: Long? = null,
    val sync: SyncOutcome? = null,
    val roles: List<RolePrompt> = emptyList(),
    val finished: Boolean = false,
    val assist: AssistState = AssistState.Idle,
)
