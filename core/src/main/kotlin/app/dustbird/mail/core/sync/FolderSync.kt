package app.dustbird.mail.core.sync

import app.dustbird.mail.core.net.FetchedMessage
import app.dustbird.mail.core.net.ImapSession
import app.dustbird.mail.core.net.MessageFlags

data class FolderCursor(val uidValidity: Long, val highestUid: Long, val baselineComplete: Boolean)

data class LocalFlags(val seen: Boolean, val answered: Boolean, val flagged: Boolean)

interface FolderStore {
    fun cursor(): FolderCursor?
    fun reset(uidValidity: Long)
    fun cachedFlags(limit: Int): Map<Long, LocalFlags>
    fun oldestUid(): Long
    fun pendingSeen(): Map<Long, Boolean>
    fun clearPending(uid: Long)
    fun insert(messages: List<FetchedMessage>)
    fun updateFlags(uid: Long, flags: LocalFlags)
    fun remove(uids: List<Long>)
    fun saveCursor(cursor: FolderCursor)
}

data class FolderSyncResult(
    val inserted: List<FetchedMessage>,
    val notifiable: List<FetchedMessage>,
    val removed: Int,
    val reset: Boolean,
)

class FolderSync(private val pageSize: Int = 50, private val flagWindow: Int = 500) {

    fun sync(session: ImapSession, path: String, store: FolderStore, deliveryHeaders: List<String>): FolderSyncResult {
        val status = session.open(path)
        var cursor = store.cursor()
        var reset = false
        if (cursor == null || cursor.uidValidity != status.uidValidity) {
            reset = cursor != null
            store.reset(status.uidValidity)
            cursor = FolderCursor(status.uidValidity, 0, baselineComplete = false)
        }

        store.pendingSeen().forEach { (uid, seen) ->
            session.setSeen(path, uid, seen)
            store.clearPending(uid)
        }

        val newUids = if (cursor.highestUid == 0L) {
            session.listUids(path, 1, null).takeLast(pageSize)
        } else {
            session.listUids(path, cursor.highestUid + 1, null)
        }
        val inserted = session.fetchMessages(path, newUids, deliveryHeaders)
        if (inserted.isNotEmpty()) store.insert(inserted)
        val highest = maxOf(cursor.highestUid, newUids.maxOrNull() ?: 0L)

        val removed = reconcile(session, path, store, skip = inserted.map { it.uid }.toSet())
        store.saveCursor(FolderCursor(status.uidValidity, highest, baselineComplete = true))
        return FolderSyncResult(
            inserted = inserted,
            notifiable = if (cursor.baselineComplete) inserted else emptyList(),
            removed = removed,
            reset = reset,
        )
    }

    fun loadOlder(session: ImapSession, path: String, store: FolderStore, deliveryHeaders: List<String>): Int {
        session.open(path, readOnly = true)
        val oldest = store.oldestUid().takeIf { it > 0 } ?: ((store.cursor()?.highestUid ?: 0) + 1)
        if (oldest <= 1) return 0
        val uids = session.listUids(path, 1, oldest - 1).takeLast(pageSize)
        val fetched = session.fetchMessages(path, uids, deliveryHeaders)
        if (fetched.isNotEmpty()) store.insert(fetched)
        return fetched.size
    }

    private fun reconcile(session: ImapSession, path: String, store: FolderStore, skip: Set<Long>): Int {
        val local = store.cachedFlags(flagWindow).filterKeys { it !in skip }
        if (local.isEmpty()) return 0
        val server = session.fetchFlags(path, local.keys.toList())
        val pending = store.pendingSeen()
        val gone = local.keys.filter { it !in server }
        if (gone.isNotEmpty()) store.remove(gone)
        local.forEach { (uid, mine) ->
            val theirs: MessageFlags = server[uid] ?: return@forEach
            val merged = LocalFlags(
                seen = pending[uid] ?: theirs.seen,
                answered = theirs.answered,
                flagged = theirs.flagged,
            )
            if (merged != mine) store.updateFlags(uid, merged)
        }
        return gone.size
    }
}
