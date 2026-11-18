package app.dustbird.mail.ui.outbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dustbird.mail.AppContainer
import app.dustbird.mail.R
import app.dustbird.mail.core.model.MailAccount
import app.dustbird.mail.core.send.SendState
import app.dustbird.mail.data.db.OutboxEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class OutboxViewModel(private val container: AppContainer) : ViewModel() {
    val items: StateFlow<List<OutboxEntity>?> = container.send.items.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val accounts: StateFlow<List<MailAccount>> =
        container.accounts.accounts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val identityLabels: StateFlow<Map<Long, String>> = container.accounts.identities
        .map { list -> list.associate { it.id to it.label } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _busy = MutableStateFlow<Set<Long>>(emptySet())
    private val _notices = MutableSharedFlow<Int>(extraBufferCapacity = 1)

    private val _edits = MutableSharedFlow<Long>(extraBufferCapacity = 1)

    val busy: StateFlow<Set<Long>> = _busy
    val notices: SharedFlow<Int> = _notices
    val edits: SharedFlow<Long> = _edits

    fun perform(id: Long, action: OutboxAction) {
        if (id in _busy.value) return
        _busy.update { it + id }
        container.scope.launch {
            try {
                val send = container.send
                when (action) {
                    OutboxAction.RETRY -> send.retry(id)
                    OutboxAction.DISCARD -> send.discard(id)
                    OutboxAction.CHECK_UNKNOWN -> {
                        send.checkUnknown(id)
                        if (container.db.outbox().get(id)?.state == SendState.UNKNOWN) _notices.tryEmit(R.string.outbox_notice_not_found)
                    }
                    OutboxAction.CONFIRM_SENT -> send.confirmSent(id)
                    OutboxAction.RESEND -> send.resend(id)
                    OutboxAction.RETRY_SAVE -> send.retrySave(id)
                    OutboxAction.SEND_NOW -> send.sendNow(id)
                    OutboxAction.CANCEL_HOLD -> send.cancelHeld(id)?.let { _edits.tryEmit(it) } ?: _notices.tryEmit(R.string.send_tracker_too_late)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _notices.tryEmit(R.string.outbox_notice_failed)
            } finally {
                _busy.update { it - id }
            }
        }
    }
}
