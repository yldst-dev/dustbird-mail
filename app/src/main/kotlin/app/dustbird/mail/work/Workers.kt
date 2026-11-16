package app.dustbird.mail.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.dustbird.mail.MailApplication
import app.dustbird.mail.core.model.SyncMode
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as MailApplication).container
        val ids = container.db.accounts().getAll().filter { it.syncMode == SyncMode.PERIODIC }.map { it.id }
        container.send.processAll()
        container.sync.syncAccounts(ids)
        return Result.success()
    }
}

class QueueWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val send = (applicationContext as MailApplication).container.send
        send.processAll()
        return if (send.hasQueued()) Result.retry() else Result.success()
    }
}

class WorkScheduler(context: Context) {
    private val work = WorkManager.getInstance(context)
    private val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun applyPeriodic(enabled: Boolean) {
        if (!enabled) {
            work.cancelUniqueWork(PERIODIC)
            return
        }
        val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(connected).build()
        work.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun enqueueQueue() {
        val request = OneTimeWorkRequestBuilder<QueueWorker>()
            .setConstraints(connected)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .build()
        work.enqueueUniqueWork(QUEUE, ExistingWorkPolicy.KEEP, request)
    }

    private companion object {
        const val PERIODIC = "periodic-sync"
        const val QUEUE = "send-queue"
    }
}
