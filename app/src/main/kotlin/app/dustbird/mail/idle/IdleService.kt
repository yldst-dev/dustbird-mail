package app.dustbird.mail.idle

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.dustbird.mail.MailApplication
import app.dustbird.mail.MainActivity
import app.dustbird.mail.R
import app.dustbird.mail.core.model.FolderRole
import app.dustbird.mail.core.model.SyncMode
import app.dustbird.mail.sync.MissingCredentialException
import app.dustbird.mail.sync.SyncOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class IdleService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = HashMap<Long, Job>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!enterForeground(0)) {
            stopSelf()
            return START_NOT_STICKY
        }
        val container = (application as MailApplication).container
        scope.launch {
            container.accounts.accounts
                .map { list -> list.filter { it.syncMode == SyncMode.IDLE && !it.needsReauth }.map { it.id }.toSet() }
                .distinctUntilChanged()
                .collect { ids ->
                    jobs.keys.filter { it !in ids }.forEach { jobs.remove(it)?.cancel() }
                    ids.filter { it !in jobs }.forEach { id -> jobs[id] = launch { keepAlive(id) } }
                    if (ids.isEmpty() || !enterForeground(ids.size)) stopSelf()
                }
        }
        return START_NOT_STICKY
    }

    private fun enterForeground(accounts: Int): Boolean = try {
        startForeground(accounts)
        true
    } catch (_: IllegalStateException) {
        false
    } catch (_: SecurityException) {
        false
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun keepAlive(accountId: Long) {
        val container = (application as MailApplication).container
        var backoff = INITIAL_BACKOFF_MS
        while (currentCoroutineContext().isActive) {
            try {
                val outcome = container.sync.syncAccount(accountId)
                if (outcome == SyncOutcome.NeedsReauth) return
                val account = container.db.accounts().get(accountId) ?: return
                val credentials = container.accounts.credentialsFor(accountId) ?: throw MissingCredentialException(accountId)
                val inbox = container.db.folders().byRole(accountId, FolderRole.INBOX)?.path ?: "INBOX"
                val endpoint = app.dustbird.mail.core.model.ServerEndpoint(account.imapHost, account.imapPort, account.imapSecurity)
                val session = container.connector.connect(endpoint, credentials)
                val closer = currentCoroutineContext().job.invokeOnCompletion { session.close() }
                try {
                    if (!session.capabilities().contains("IDLE")) {
                        delay(FALLBACK_POLL_MS)
                    } else {
                        backoff = INITIAL_BACKOFF_MS
                        withContext(Dispatchers.IO) {
                            session.idle(inbox) { scope.launch { container.sync.syncAccount(accountId) } }
                        }
                    }
                } finally {
                    closer.dispose()
                    session.close()
                }
            } catch (_: MissingCredentialException) {
                return
            } catch (_: Exception) {
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
        }
    }

    private fun startForeground(accounts: Int) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.notify_channel_idle), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notify_idle_title))
            .setContentText(getString(R.string.notify_idle_text, accounts))
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private companion object {
        const val CHANNEL = "idle"
        const val NOTIFICATION_ID = 7001
        const val INITIAL_BACKOFF_MS = 15_000L
        const val MAX_BACKOFF_MS = 600_000L
        const val FALLBACK_POLL_MS = 300_000L
    }
}

class IdleController(private val context: Context) {
    fun apply(enabled: Boolean) {
        val intent = Intent(context, IdleService::class.java)
        if (!enabled) {
            context.stopService(intent)
            return
        }
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (_: IllegalStateException) {
        }
    }
}
