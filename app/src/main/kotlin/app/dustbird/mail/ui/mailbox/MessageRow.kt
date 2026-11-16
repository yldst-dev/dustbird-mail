package app.dustbird.mail.ui.mailbox

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.dustbird.mail.R
import app.dustbird.mail.core.identity.RecipientMatch
import app.dustbird.mail.data.repo.MessageSummary

private val ReadWeight = FontWeight(450)
private val UnreadWeight = FontWeight(700)

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
) {
    val unread = !message.seen
    val unreadText = stringResource(R.string.mailbox_row_unread)
    val sender = message.fromName?.takeIf { it.isNotBlank() }
        ?: message.fromAddress?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.mailbox_row_no_sender)
    val subject = message.subject.ifBlank { stringResource(R.string.mailbox_row_no_subject) }
    val matched = (message.match as? RecipientMatch.Matched)?.identityIds?.mapNotNull { identityLabels[it] }.orEmpty()
    val identityText = when {
        message.match is RecipientMatch.Unknown -> stringResource(R.string.mailbox_row_unknown_identity)
        matched.isNotEmpty() -> matched.joinToString(", ")
        else -> null
    }
    val animations = rememberAnimationsEnabled()
    val scheme = MaterialTheme.colorScheme
    val spatial: FiniteAnimationSpec<Dp> = if (animations) MaterialTheme.motionScheme.defaultSpatialSpec() else snap()
    val effects: FiniteAnimationSpec<Color> = if (animations) MaterialTheme.motionScheme.defaultEffectsSpec() else snap()
    val corner by animateDpAsState(if (selected == true) 16.dp else 0.dp, spatial, label = "rowCorner")
    val background by animateColorAsState(if (selected == true) scheme.secondaryContainer else Color.Transparent, effects, label = "rowBackground")
    val attachmentDescription = stringResource(R.string.mailbox_row_attachment_description)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(corner))
            .background(background)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                if (unread) stateDescription = unreadText
                if (selected != null) this.selected = selected
            }
            .heightIn(min = 72.dp)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.padding(top = 7.dp).size(10.dp)) {
            if (unread) Box(Modifier.size(10.dp).background(scheme.primary, CircleShape))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                Text(
                    text = sender,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (unread) UnreadWeight else ReadWeight,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = time,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (unread) scheme.onSurface else scheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Text(
                text = subject,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (message.preview.isNotBlank()) {
                Text(
                    text = message.preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (accountLabel != null || message.hasAttachment || identityText != null) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    if (accountLabel != null) AccountLabel(accountLabel, accountIndex, Modifier.testTag(AccountLabelTag))
                    if (message.hasAttachment) {
                        Text(
                            text = stringResource(R.string.mailbox_row_attachment),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.semantics { contentDescription = attachmentDescription },
                        )
                    }
                    if (identityText != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = stringResource(R.string.mailbox_row_received_at),
                                style = MaterialTheme.typography.labelMedium,
                                color = scheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                            Text(
                                text = identityText,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = scheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                        }
                    }
                }
            }
        }
    }
}

const val AccountLabelTag = "row_account_label"
