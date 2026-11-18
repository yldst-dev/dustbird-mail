package app.dustbird.mail.ui.compose

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.dustbird.mail.R
import app.dustbird.mail.core.model.EmailAddress
import app.dustbird.mail.core.model.MailAccount
import app.dustbird.mail.core.model.MailIdentity
import app.dustbird.mail.core.model.SendVerification
import app.dustbird.mail.data.repo.AccountWithIdentities
import app.dustbird.mail.ui.theme.Pill
import app.dustbird.mail.ui.theme.accountAccent
import app.dustbird.mail.data.db.DraftAttachmentEntity
import androidx.compose.ui.platform.LocalContext
import android.text.format.Formatter

@StringRes
internal fun verificationRes(state: SendVerification): Int = when (state) {
    SendVerification.UNVERIFIED -> R.string.compose_verification_unverified
    SendVerification.VERIFIED -> R.string.compose_verification_verified
    SendVerification.FAILED -> R.string.compose_verification_failed
}

internal fun accountCaption(account: MailAccount): String =
    if (account.label.isBlank() || account.label == account.displayName) account.displayName else "${account.displayName} · ${account.label}"

@Composable
internal fun AccountTag(account: MailAccount, index: Int, modifier: Modifier = Modifier) {
    val accent = accountAccent(index)
    Text(
        text = accountCaption(account),
        style = MaterialTheme.typography.labelMediumEmphasized,
        color = accent.content,
        modifier = modifier.background(accent.container, Pill).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
internal fun HintRow(text: String, modifier: Modifier = Modifier, warning: Boolean = false, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                painter = painterResource(if (warning) R.drawable.ic_warning else R.drawable.ic_info),
                contentDescription = null,
                tint = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp).size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }
        action?.invoke()
    }
}

@Composable
internal fun SenderRow(
    identity: MailIdentity,
    account: MailAccount,
    accountIndex: Int,
    showAccount: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(enabled = enabled, onClickLabel = stringResource(R.string.compose_sender_pick), role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.compose_label_sender), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(identity.label, style = MaterialTheme.typography.titleMediumEmphasized)
            Text(identity.address, style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.compose_verification_format, stringResource(verificationRes(identity.verification))),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (showAccount) AccountTag(account, accountIndex, Modifier.padding(top = 4.dp))
        }
        Icon(painterResource(R.drawable.ic_arrow_drop_down), contentDescription = null)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SenderSheet(groups: List<AccountWithIdentities>, selectedId: Long?, onSelect: (Long) -> Unit, onDismiss: () -> Unit) {
    val showAccounts = groups.size > 1
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraExtraLarge.copy(bottomStart = ZeroCornerSize, bottomEnd = ZeroCornerSize),
    ) {
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "title") {
                Text(
                    stringResource(R.string.compose_sender_pick),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
            groups.forEachIndexed { index, group ->
                if (showAccounts) {
                    item(key = "account-${group.account.id}") {
                        val accent = accountAccent(index)
                        Text(
                            text = accountCaption(group.account),
                            style = MaterialTheme.typography.labelLarge,
                            color = accent.content,
                            modifier = Modifier.fillMaxWidth().background(accent.container).heightIn(min = 40.dp).padding(horizontal = 24.dp, vertical = 8.dp),
                        )
                    }
                }
                items(group.identities, key = { it.id }) { identity ->
                    IdentityRow(identity, identity.id == selectedId) { onSelect(identity.id) }
                }
            }
        }
    }
}

@Composable
private fun IdentityRow(identity: MailIdentity, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 8.dp)
            .testTag("compose_identity_${identity.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                identity.label,
                style = if (selected) MaterialTheme.typography.bodyLargeEmphasized else MaterialTheme.typography.bodyLarge,
            )
            Text(identity.address, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (identity.verification == SendVerification.FAILED) {
                    Icon(painterResource(R.drawable.ic_warning), contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    stringResource(R.string.compose_verification_format, stringResource(verificationRes(identity.verification))),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (identity.verification == SendVerification.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RecipientRow(
    label: String,
    field: RecipientField,
    enabled: Boolean,
    tag: String,
    modifier: Modifier = Modifier,
    note: String? = null,
    hidden: Boolean = false,
    emptyError: Boolean = false,
    focusRequester: FocusRequester = remember { FocusRequester() },
    suggestions: List<EmailAddress> = emptyList(),
    onPick: (EmailAddress) -> Unit = {},
) {
    val hasInvalid = field.chips.any { !RecipientInput.isValid(it) }
    val errorText = when {
        hasInvalid -> stringResource(R.string.compose_error_invalid_field)
        emptyError -> stringResource(R.string.compose_error_no_recipient)
        else -> null
    }
    Column(modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            field.chips.forEach { chip ->
                key(chip.address) {
                    RecipientChip(
                        address = chip,
                        hidden = hidden,
                        enabled = enabled,
                        onEdit = {
                            field.edit(chip)
                            focusRequester.focus()
                        },
                        onRemove = { field.remove(chip) },
                    )
                }
            }
            Box(Modifier.weight(1f).widthIn(min = 160.dp).heightIn(min = 48.dp), contentAlignment = Alignment.CenterStart) {
                BasicTextField(
                    state = field.input,
                    enabled = enabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .testTag(tag)
                        .semantics { contentDescription = label }
                        .onFocusChanged { if (!it.isFocused) field.commit(flush = true) }
                        .onPreviewKeyEvent { event ->
                            val removeLast = event.type == KeyEventType.KeyDown && event.key == Key.Backspace &&
                                field.input.text.isEmpty() && field.chips.isNotEmpty()
                            if (removeLast) field.removeLast()
                            removeLast
                        },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Next,
                    ),
                    onKeyboardAction = { performDefaultAction ->
                        field.commit(flush = true)
                        performDefaultAction()
                    },
                    lineLimits = TextFieldLineLimits.SingleLine,
                )
            }
        }
        if (suggestions.isNotEmpty()) {
            FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                suggestions.forEach { suggestion ->
                    val description = stringResource(R.string.compose_suggestion_add, suggestion.address)
                    Surface(
                        onClick = { onPick(suggestion) },
                        enabled = enabled,
                        shape = Pill,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.semantics { contentDescription = description },
                    ) {
                        Column(Modifier.heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.Center) {
                            Text(suggestion.name ?: suggestion.address, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
                            if (suggestion.name != null) {
                                Text(suggestion.address, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
                            }
                        }
                    }
                }
            }
        }
        if (errorText != null) {
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.Top) {
                Icon(painterResource(R.drawable.ic_warning), contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 2.dp).size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(errorText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun RecipientChip(address: EmailAddress, hidden: Boolean, enabled: Boolean, onEdit: () -> Unit, onRemove: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val valid = RecipientInput.isValid(address)
    val container = when {
        !valid -> colors.errorContainer
        hidden -> colors.tertiaryContainer
        else -> colors.secondaryContainer
    }
    val content = when {
        !valid -> colors.onErrorContainer
        hidden -> colors.onTertiaryContainer
        else -> colors.onSecondaryContainer
    }
    val text = address.name ?: address.address
    val description = if (valid) address.address else stringResource(R.string.compose_chip_invalid, address.address)
    Surface(shape = Pill, color = container, contentColor = content) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .heightIn(min = 48.dp)
                    .clickable(enabled = enabled, onClickLabel = stringResource(R.string.compose_chip_edit), role = Role.Button, onClick = onEdit)
                    .semantics(mergeDescendants = true) { contentDescription = description }
                    .padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!valid) {
                    Icon(painterResource(R.drawable.ic_warning), contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
            }
            IconButton(onClick = onRemove, enabled = enabled) {
                Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.compose_chip_remove, address.address), modifier = Modifier.size(18.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AttachmentChips(attachments: List<DraftAttachmentEntity>, enabled: Boolean, onRemove: (Long) -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.compose_attachments_label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            attachments.forEach { attachment ->
                Surface(shape = Pill, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Row(Modifier.heightIn(min = 48.dp).padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(attachment.fileName, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            Formatter.formatShortFileSize(context, attachment.size),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        IconButton(onClick = { onRemove(attachment.id) }, enabled = enabled) {
                            Icon(
                                painterResource(R.drawable.ic_close),
                                contentDescription = stringResource(R.string.compose_attachment_remove, attachment.fileName),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun LabeledField(
    label: String,
    state: TextFieldState,
    enabled: Boolean,
    tag: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    focusRequester: FocusRequester = remember { FocusRequester() },
) {
    Column(modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        BasicTextField(
            state = state,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(vertical = 8.dp)
                .focusRequester(focusRequester)
                .testTag(tag)
                .semantics { contentDescription = label },
            textStyle = (if (singleLine) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge)
                .copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = if (singleLine) ImeAction.Next else ImeAction.Default,
            ),
            lineLimits = if (singleLine) TextFieldLineLimits.SingleLine else TextFieldLineLimits.MultiLine(minHeightInLines = 8),
        )
    }
}

@Composable
internal fun FieldDivider() = HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

@Composable
internal fun TextActionButton(@StringRes text: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick = onClick, modifier = modifier.heightIn(min = 48.dp)) { Text(stringResource(text)) }
}

internal fun FocusRequester.focus() {
    runCatching { requestFocus() }
}
