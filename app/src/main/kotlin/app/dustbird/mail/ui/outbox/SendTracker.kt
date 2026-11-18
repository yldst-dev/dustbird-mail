package app.dustbird.mail.ui.outbox

import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dustbird.mail.R
import app.dustbird.mail.core.send.SendState
import app.dustbird.mail.data.db.OutboxEntity
import app.dustbird.mail.ui.LocalAppContainer
import app.dustbird.mail.ui.mailbox.rememberAnimationsEnabled
import app.dustbird.mail.ui.theme.capsuleShape
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class TrackedPhase(@StringRes val text: Int, @StringRes val action: Int? = null) {
    WAITING(R.string.send_tracker_waiting, R.string.send_tracker_cancel),
    SENDING(R.string.send_tracker_sending),
    SENT(R.string.send_tracker_sent),
    COPY_FAILED(R.string.send_tracker_copy_failed, R.string.send_tracker_open),
    FAILED(R.string.send_tracker_failed, R.string.send_tracker_open),
}

fun trackedPhase(row: OutboxEntity, now: Long): TrackedPhase = when (row.state) {
    SendState.DRAFT, SendState.QUEUED -> if (row.draftId != null && row.sendAfter > now) TrackedPhase.WAITING else TrackedPhase.SENDING
    SendState.SENDING -> TrackedPhase.SENDING
    SendState.ACCEPTED, SendState.SAVED -> TrackedPhase.SENT
    SendState.SAVE_FAILED -> TrackedPhase.COPY_FAILED
    SendState.FAILED, SendState.UNKNOWN -> TrackedPhase.FAILED
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SendTracker(onEdit: (Long) -> Unit, onOpenOutbox: () -> Unit, modifier: Modifier = Modifier) {
    val send = LocalAppContainer.current.send
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val trackedId by send.tracked.collectAsStateWithLifecycle()
    val item by remember(send) { send.trackedItem }.collectAsStateWithLifecycle(initialValue = null)
    val row = item?.takeIf { it.id == trackedId }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val phase = row?.let { trackedPhase(it, now) }
    val shown = remember { arrayOfNulls<Pair<TrackedPhase, Long>>(1) }
    if (row != null && phase != null) shown[0] = phase to ((row.sendAfter - now + 999) / 1000).coerceAtLeast(1)

    LaunchedEffect(row?.id, row?.sendAfter, row?.state) {
        now = System.currentTimeMillis()
        while (row != null && row.state == SendState.QUEUED && row.sendAfter > now) {
            delay(200)
            now = System.currentTimeMillis()
        }
    }
    LaunchedEffect(row?.id, phase) {
        if (row != null && phase == TrackedPhase.SENT) {
            delay(2500)
            send.untrack(row.id)
        }
    }

    val animations = rememberAnimationsEnabled()
    val motion = MaterialTheme.motionScheme
    AnimatedVisibility(
        visible = phase != null,
        modifier = modifier,
        enter = if (animations) fadeIn(motion.defaultEffectsSpec()) + slideInVertically(motion.defaultSpatialSpec()) { it / 2 } else EnterTransition.None,
        exit = if (animations) fadeOut(motion.fastEffectsSpec()) + slideOutVertically(motion.fastEffectsSpec()) { it / 2 } else ExitTransition.None,
        label = "send tracker",
    ) {
        val (current, seconds) = shown[0] ?: return@AnimatedVisibility
        Surface(
            modifier = Modifier.widthIn(max = 560.dp).testTag("send_tracker"),
            shape = capsuleShape(),
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        ) {
            Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (current == TrackedPhase.SENDING) {
                    LoadingIndicator(Modifier.size(24.dp), color = MaterialTheme.colorScheme.inversePrimary)
                    Spacer(Modifier.width(12.dp))
                }
                Text(
                    text = if (current == TrackedPhase.WAITING) stringResource(current.text, seconds) else stringResource(current.text),
                    modifier = Modifier.weight(1f, fill = false).padding(vertical = 10.dp, horizontal = 0.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.width(8.dp))
                current.action?.let { label ->
                    TextButton(
                        onClick = {
                            val id = row?.id ?: return@TextButton
                            if (current == TrackedPhase.WAITING) {
                                scope.launch {
                                    val draftId = send.cancelHeld(id)
                                    if (draftId != null) onEdit(draftId) else Toast.makeText(context.applicationContext, R.string.send_tracker_too_late, Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                send.untrack(id)
                                onOpenOutbox()
                            }
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.inversePrimary),
                    ) { Text(stringResource(label)) }
                }
                if (current == TrackedPhase.FAILED || current == TrackedPhase.COPY_FAILED) {
                    IconButton(onClick = { row?.id?.let(send::untrack) }) {
                        Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.send_tracker_dismiss))
                    }
                } else if (current.action == null) {
                    Spacer(Modifier.width(12.dp))
                }
            }
        }
    }
}
