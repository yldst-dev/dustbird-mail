package app.dustbird.mail.core.identity

import app.dustbird.mail.core.model.MailAccount
import app.dustbird.mail.core.model.MailIdentity

enum class Rejection {
    INVALID_ADDRESS,
    DUPLICATE_ADDRESS,
    DUPLICATE_LABEL,
    BLANK_LABEL,
    LAST_IDENTITY,
    UNKNOWN_IDENTITY,
    DUPLICATE_MAILBOX,
}

sealed interface RuleResult<out T> {
    data class Ok<T>(val value: T) : RuleResult<T>
    data class Rejected(val reason: Rejection) : RuleResult<Nothing>
}

data class IdentityDraft(val accountId: Long, val address: String, val label: String)

data class RemovalPlan(val fallbackIdentityId: Long, val newDefaultId: Long?)

object IdentityRules {
    fun validate(all: List<MailIdentity>, draft: IdentityDraft, editingId: Long? = null): Rejection? {
        if (!Addresses.isValid(draft.address)) return Rejection.INVALID_ADDRESS
        if (draft.label.isBlank()) return Rejection.BLANK_LABEL
        val others = all.filter { it.id != editingId }
        if (others.any { Addresses.same(it.address, draft.address) }) return Rejection.DUPLICATE_ADDRESS
        if (others.any { it.label.trim().equals(draft.label.trim(), ignoreCase = true) }) return Rejection.DUPLICATE_LABEL
        return null
    }

    fun defaultLabel(all: List<MailIdentity>, address: String): String {
        val base = address.trim().substringBefore('@')
        val taken = all.map { it.label.trim().lowercase() }.toSet()
        if (base.lowercase() !in taken) return base
        return generateSequence(2) { it + 1 }.map { "$base$it" }.first { it.lowercase() !in taken }
    }

    fun planRemoval(accountIdentities: List<MailIdentity>, id: Long): RuleResult<RemovalPlan> {
        val target = accountIdentities.firstOrNull { it.id == id } ?: return RuleResult.Rejected(Rejection.UNKNOWN_IDENTITY)
        val remaining = accountIdentities.filter { it.id != id }.sortedBy { it.sortOrder }
        if (remaining.isEmpty()) return RuleResult.Rejected(Rejection.LAST_IDENTITY)
        val currentDefault = remaining.firstOrNull { it.isAccountDefault }
        val fallback = currentDefault ?: remaining.first()
        val newDefault = if (target.isAccountDefault) fallback.id else null
        return RuleResult.Ok(RemovalPlan(fallback.id, newDefault))
    }

    fun reorder(items: List<MailIdentity>, orderedIds: List<Long>): List<MailIdentity> {
        val byId = items.associateBy { it.id }
        return orderedIds.mapNotNull { byId[it] }.mapIndexed { index, item -> item.copy(sortOrder = index) }
    }

    fun withDefault(items: List<MailIdentity>, defaultId: Long): List<MailIdentity> =
        items.map { it.copy(isAccountDefault = it.id == defaultId) }
}

object AccountRules {
    fun mailboxKey(imapHost: String, username: String): String = "${imapHost.trim().lowercase()}|${username.trim().lowercase()}"

    fun isDuplicateMailbox(all: List<MailAccount>, imapHost: String, username: String, editingId: Long? = null): Boolean {
        val key = mailboxKey(imapHost, username)
        return all.any { it.id != editingId && mailboxKey(it.imap.host, it.username) == key }
    }
}
