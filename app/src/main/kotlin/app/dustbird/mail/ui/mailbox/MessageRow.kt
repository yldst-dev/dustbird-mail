package app.dustbird.mail.ui.mailbox

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.dustbird.mail.R
import app.dustbird.mail.core.identity.RecipientMatch
import app.dustbird.mail.data.repo.MessageSummary
import app.dustbird.mail.ui.theme.CapsulePadding
import app.dustbird.mail.ui.theme.capsuleItemShapes
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.Icon

private val ReadWeight = FontWeight(450)
private val UnreadWeight = FontWeight(700)
private val ReadSubjectWeight = FontWeight(500)
private val UnreadSubjectWeight = FontWeight(650)

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MessageRow(
    message: MessageSummary,
    time: String,
    accountLabel: String?,
    accountIndex: Int,
    identityLabels: Map<Long, String>,
    selected: Boolean?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    checked: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    val unread = !message.seen
    val unreadText = stringResource(R.string.mailbox_row_unread)
    val sender = message.fromName?.takeIf { it.isNotBlank() }
        ?: message.fromAddress?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.mailbox_row_no_sender)
    val subject = message.subject.ifBlank { stringResource(R.string.mailbox_row_no_subject) }
    val matched = (message.match as? RecipientMatch.Matched)?.identityIds?.mapNotNull { identityLabels[it] }.orEmpty()
    val identityText = when (val match = message.match) {
        is RecipientMatch.Unknown -> stringResource(R.string.mailbox_row_unknown_identity)
        is RecipientMatch.OwnAddress -> match.address
        else -> matched.joinToString(", ").ifEmpty { null }
    }
    val scheme = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val attachmentDescription = stringResource(R.string.mailbox_row_attachment_description)
    val starredText = stringResource(R.string.mailbox_row_starred)
    val answeredText = stringResource(R.string.mailbox_row_answered)
    val checkedText = stringResource(R.string.mailbox_row_selected)
    val states = listOfNotNull(
        checkedText.takeIf { checked },
        unreadText.takeIf { unread },
        starredText.takeIf { message.flagged },
        answeredText.takeIf { message.answered },
    )
    val highlighted = checked || selected == true
    val rowModifier = modifier.semantics {
        if (states.isNotEmpty()) stateDescription = states.joinToString(", ")
        if (selected != null || checked) this.selected = highlighted
    }
    val motion = MaterialTheme.motionScheme
    val highlight by animateFloatAsState(if (highlighted) 1f else 0f, motion.defaultEffectsSpec(), label = "row highlight")
    val mark by animateFloatAsState(if (checked) 1f else 0f, motion.fastSpatialSpec(), label = "row check")
    val base = ListItemDefaults.colors()
    val colors = if (highlight <= 0f) {
        base
    } else {
        val amount = highlight.coerceAtMost(1f)
        ListItemDefaults.colors(
            containerColor = lerp(base.containerColor, base.selectedContainerColor, amount),
            contentColor = lerp(base.contentColor, base.selectedContentColor, amount),
            leadingContentColor = lerp(base.leadingContentColor, base.selectedLeadingContentColor, amount),
            trailingContentColor = lerp(base.trailingContentColor, base.selectedTrailingContentColor, amount),
            overlineContentColor = lerp(base.overlineContentColor, base.selectedOverlineContentColor, amount),
            supportingContentColor = lerp(base.supportingContentColor, base.selectedSupportingContentColor, amount),
        )
    }

    val leading: @Composable () -> Unit = {
        Box(Modifier.padding(top = 2.dp).size(20.dp), contentAlignment = Alignment.Center) {
            if (unread) {
                Box(Modifier.size(10.dp).graphicsLayer { alpha = 1f - mark.coerceIn(0f, 1f) }.background(scheme.primary, CircleShape))
            }
            if (checked || mark > 0f) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = mark
                            scaleY = mark
                            alpha = mark.coerceIn(0f, 1f)
                        }
                        .background(scheme.primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = scheme.onPrimary, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
    val headline: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = sender,
                style = typography.titleMedium,
                fontWeight = if (unread) UnreadWeight else ReadWeight,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (message.hasAttachment) {
                Icon(painterResource(R.drawable.ic_attach_file), contentDescription = attachmentDescription, tint = scheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            }
            if (message.answered) {
                Icon(painterResource(R.drawable.ic_reply), contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            }
            if (message.flagged) {
                Icon(painterResource(R.drawable.ic_star_filled), contentDescription = null, tint = scheme.tertiary, modifier = Modifier.size(16.dp))
            }
            Text(
                text = time,
                style = if (unread) typography.labelMediumEmphasized else typography.labelMedium,
                color = if (unread) scheme.onSurface else scheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
    val supporting: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = subject,
                style = typography.bodyMedium,
                fontWeight = if (unread) UnreadSubjectWeight else ReadSubjectWeight,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val digest = message.digest
            if (digest != null) {
                val digestDescription = stringResource(R.string.mailbox_row_digest_description, digest)
                Row(
                    modifier = Modifier.clearAndSetSemantics { contentDescription = digestDescription },
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_bird),
                        contentDescription = null,
                        tint = scheme.primary,
                        modifier = Modifier.padding(top = 2.dp).size(16.dp),
                    )
                    Text(text = digest, style = typography.bodyMedium, color = scheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            } else if (message.preview.isNotBlank()) {
                Text(text = message.preview, style = typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (accountLabel != null || identityText != null) {
                FlowRow(
                    modifier = Modifier.padding(top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    if (accountLabel != null) AccountLabel(accountLabel, accountIndex, Modifier.testTag(AccountLabelTag))
                    if (identityText != null) IdentityLabel(stringResource(R.string.mailbox_row_received_at), identityText)
                }
            }
        }
    }

    ListItem(
        onClick = onClick,
        onLongClick = onLongClick,
        shapes = capsuleItemShapes(),
        colors = colors,
        contentPadding = CapsulePadding,
        modifier = rowModifier,
        leadingContent = leading,
        supportingContent = supporting,
        verticalAlignment = Alignment.Top,
        content = headline,
    )
}

const val AccountLabelTag = "row_account_label"
