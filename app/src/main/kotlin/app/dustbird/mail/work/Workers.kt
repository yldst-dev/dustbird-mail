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
import app.dustbird.mail.data.repo.MoveTarget
import app.dustbird.mail.core.model.FolderRole
import androidx.work.workDataOf

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as MailApplication).container
        val ids = container.db.accounts().getAll().filter { it.syncMode != SyncMode.MANUAL && !it.needsReauth }.map { it.id }
        container.send.processAll()
        container.sync.syncAccounts(ids)
        return Result.success()
    }
}

class QueueWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as MailApplication).container
        val send = container.send
        send.processAll()
        val next = send.nextDue()
        if (next != null) container.scheduler.enqueueQueueAt(next)
        val ranEarly = next != null && WorkScheduler.dueName(next) in tags
        return if (send.hasQueued() || ranEarly) Result.retry() else Result.success()
    }
}

class AgentWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        (applicationContext as MailApplication).container.agent.runDue()
        return Result.success()
    }
}

class MailActionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val actions = (applicationContext as MailApplication).container.actions
        val ids = listOf(inputData.getLong(MESSAGE, -1))
        val target = MoveTarget.Role(if (inputData.getBoolean(ARCHIVE, false)) FolderRole.ARCHIVE else FolderRole.TRASH)
        if (!actions.movesToFolder(ids, target)) return Result.success()
        return if (actions.run(ids, target) > 0 || runAttemptCount >= 2) Result.success() else Result.retry()
    }

    companion object {
        const val MESSAGE = "message"
        const val ARCHIVE = "archive"
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

    fun applyAgent(enabled: Boolean) {
        if (!enabled) {
            work.cancelUniqueWork(AGENT)
            return
        }
        val request = PeriodicWorkRequestBuilder<AgentWorker>(1, TimeUnit.HOURS).setConstraints(connected).build()
        work.enqueueUniquePeriodicWork(AGENT, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun enqueueQueue() {
        val request = OneTimeWorkRequestBuilder<QueueWorker>()
            .setConstraints(connected)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .build()
        work.enqueueUniqueWork(QUEUE, ExistingWorkPolicy.KEEP, request)
    }

    fun enqueueQueueAt(time: Long) {
        val request = OneTimeWorkRequestBuilder<QueueWorker>()
            .setConstraints(connected)
            .setInitialDelay((time - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .addTag(dueName(time))
            .build()
        work.enqueueUniqueWork(dueName(time), ExistingWorkPolicy.KEEP, request)
    }

    fun enqueueMailAction(messageId: Long, archive: Boolean) {
        val request = OneTimeWorkRequestBuilder<MailActionWorker>()
            .setConstraints(connected)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .setInputData(workDataOf(MailActionWorker.MESSAGE to messageId, MailActionWorker.ARCHIVE to archive))
            .build()
        work.enqueueUniqueWork("mail-action-$messageId", ExistingWorkPolicy.KEEP, request)
    }

    companion object {
        private const val PERIODIC = "periodic-sync"
        private const val QUEUE = "send-queue"
        private const val AGENT = "agent-daily"

        fun dueName(time: Long) = "send-due-$time"
    }
}
