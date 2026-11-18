package app.dustbird.mail.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import app.dustbird.mail.MailApplication
import app.dustbird.mail.R
import app.dustbird.mail.core.model.FolderRole
import app.dustbird.mail.data.repo.MoveTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MailActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val messageId = intent.getLongExtra(EXTRA_MESSAGE, -1)
        val accountId = intent.getLongExtra(EXTRA_ACCOUNT, -1)
        val action = intent.action
        if (messageId < 0 || accountId < 0 || action == null) return
        val container = (context.applicationContext as MailApplication).container
        val pending = goAsync()
        container.scope.launch {
            try {
                val accepted = when (action) {
                    ACTION_READ -> {
                        container.mail.setSeen(messageId, true)
                        true
                    }
                    ACTION_ARCHIVE, ACTION_DELETE -> {
                        val archive = action == ACTION_ARCHIVE
                        val movable = container.actions.movesToFolder(listOf(messageId), MoveTarget.Role(if (archive) FolderRole.ARCHIVE else FolderRole.TRASH))
                        if (movable) container.scheduler.enqueueMailAction(messageId, archive)
                        movable
                    }
                    else -> return@launch
                }
                if (accepted) {
                    AndroidNotifier.cancelMessage(context, accountId, messageId)
                } else {
                    withContext(Dispatchers.Main) { Toast.makeText(context, R.string.notify_action_unavailable, Toast.LENGTH_LONG).show() }
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_READ = "app.dustbird.mail.action.MARK_READ"
        const val ACTION_ARCHIVE = "app.dustbird.mail.action.ARCHIVE"
        const val ACTION_DELETE = "app.dustbird.mail.action.DELETE"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_ACCOUNT = "account"
    }
}
