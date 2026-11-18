package app.dustbird.mail.data.repo

import app.dustbird.mail.core.model.FolderRole
import app.dustbird.mail.data.db.FolderEntity
import app.dustbird.mail.data.db.ListState
import app.dustbird.mail.data.db.MailDatabase
import app.dustbird.mail.data.db.MessageEntity
import app.dustbird.mail.sync.ImapAccess
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException

sealed interface MoveTarget {
    data class Role(val role: FolderRole) : MoveTarget
    data class Folder(val folderId: Long) : MoveTarget
}

data class MoveTicket(val id: Long, val count: Int, val skipped: Int)

data class StartedMove(val ticket: MoveTicket, val target: MoveTarget, val permanent: Boolean)

class MailActions(
    private val db: MailDatabase,
    private val access: ImapAccess,
    private val scope: CoroutineScope,
    private val afterCommit: suspend (Set<Long>) -> Unit,
    private val undoWindowMillis: Long = 6_000,
) {
    private class Step(val accountId: Long, val source: FolderEntity, val target: FolderEntity?, val rows: List<MessageEntity>)
    private class Pending(val steps: List<Step>, val job: Job)

    private val pending = ConcurrentHashMap<Long, Pending>()
    private val nextId = AtomicLong(1)
    private val _failures = MutableSharedFlow<Int>(extraBufferCapacity = 8)
    val failures: SharedFlow<Int> = _failures
    private val _started = MutableSharedFlow<StartedMove>(extraBufferCapacity = 8)
    val started: SharedFlow<StartedMove> = _started

    suspend fun deletesPermanently(ids: List<Long>): Boolean =
        plan(ids, MoveTarget.Role(FolderRole.TRASH)).first.any { it.target == null }

    suspend fun movesToFolder(ids: List<Long>, target: MoveTarget): Boolean =
        plan(ids, target).first.let { steps -> steps.isNotEmpty() && steps.none { it.target == null } }

    fun isPending(ticket: MoveTicket): Boolean = pending.containsKey(ticket.id)

    suspend fun start(ids: List<Long>, target: MoveTarget, announceSkipped: Boolean = true): MoveTicket? {
        val (steps, skipped) = plan(ids, target)
        if (steps.isEmpty()) {
            if (skipped == 0) return null
            return MoveTicket(0, 0, skipped).also { if (announceSkipped) _started.tryEmit(StartedMove(it, target, permanent = false)) }
        }
        db.messages().setListState(steps.flatMap { step -> step.rows.map { it.id } }, ListState.HIDDEN)
        val id = nextId.getAndIncrement()
        val job = scope.launch {
            delay(undoWindowMillis)
            pending.remove(id)?.let { commit(it.steps) }
        }
        pending[id] = Pending(steps, job)
        return MoveTicket(id, steps.sumOf { it.rows.size }, skipped).also {
            _started.tryEmit(StartedMove(it, target, permanent = steps.all { step -> step.target == null }))
        }
    }

    fun undo(ticket: MoveTicket) {
        val entry = pending.remove(ticket.id) ?: return
        entry.job.cancel()
        scope.launch { db.messages().setListState(entry.steps.flatMap { step -> step.rows.map { it.id } }, ListState.NORMAL) }
    }

    fun commitNow(ticket: MoveTicket) {
        val entry = pending.remove(ticket.id) ?: return
        entry.job.cancel()
        scope.launch { commit(entry.steps) }
    }

    suspend fun run(ids: List<Long>, target: MoveTarget): Int {
        val (steps, _) = plan(ids, target)
        db.messages().setListState(steps.flatMap { step -> step.rows.map { it.id } }, ListState.HIDDEN)
        return commit(steps)
    }

    private suspend fun plan(ids: List<Long>, target: MoveTarget): Pair<List<Step>, Int> {
        val rows = ids.chunked(500).flatMap { db.messages().byIds(it) }
        val steps = ArrayList<Step>()
        var skipped = ids.size - rows.size
        rows.groupBy { it.folderId }.forEach { (folderId, group) ->
            val source = db.folders().get(folderId)
            if (source == null) {
                skipped += group.size
                return@forEach
            }
            val accountId = source.accountId
            val destination: FolderEntity? = when (target) {
                is MoveTarget.Folder -> db.folders().get(target.folderId)?.takeIf { it.accountId == accountId }
                is MoveTarget.Role -> when (target.role) {
                    FolderRole.ARCHIVE -> db.folders().byRole(accountId, FolderRole.ARCHIVE) ?: db.folders().byRole(accountId, FolderRole.ALL)
                    else -> db.folders().byRole(accountId, target.role)
                }
            }
            val deleting = target == MoveTarget.Role(FolderRole.TRASH)
            when {
                deleting && (destination == null || destination.id == source.id) -> steps += Step(accountId, source, null, group)
                destination == null || destination.id == source.id -> skipped += group.size
                else -> steps += Step(accountId, source, destination, group)
            }
        }
        return steps to skipped
    }

    private suspend fun commit(steps: List<Step>): Int {
        var done = 0
        var failed = 0
        steps.groupBy { it.accountId }.forEach { (accountId, group) ->
            for (step in group) {
                val uids = step.rows.map { it.uid }
                val ids = step.rows.map { it.id }
                try {
                    val unsynced = db.pendingFlags().forFolder(step.source.id).filter { it.uid in uids }
                    access.use(accountId) { session, _ ->
                        session.open(step.source.path)
                        unsynced.forEach { session.setSeen(step.source.path, it.uid, it.seen) }
                        if (step.target == null) session.delete(step.source.path, uids) else session.move(step.source.path, uids, step.target.path)
                    }
                    unsynced.forEach { db.pendingFlags().delete(it) }
                    ids.chunked(500).forEach { db.messages().deleteByIds(it) }
                    done += ids.size
                } catch (error: CancellationException) {
                    withContext(NonCancellable) { ids.chunked(500).forEach { db.messages().setListState(it, ListState.NORMAL) } }
                    throw error
                } catch (_: Exception) {
                    ids.chunked(500).forEach { db.messages().setListState(it, ListState.NORMAL) }
                    failed += ids.size
                }
            }
        }
        if (failed > 0) _failures.tryEmit(failed)
        if (done > 0) scope.launch { runCatching { afterCommit(steps.map { it.accountId }.toSet()) } }
        return done
    }
}
