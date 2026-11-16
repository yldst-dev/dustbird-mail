package app.dustbird.mail.core.identity

import app.dustbird.mail.core.model.EmailAddress
import app.dustbird.mail.core.model.MailIdentity

data class OriginalMessage(
    val accountId: Long,
    val messageId: String?,
    val references: List<String>,
    val from: EmailAddress?,
    val replyTo: List<EmailAddress>,
    val to: List<EmailAddress>,
    val cc: List<EmailAddress>,
    val subject: String,
    val text: String,
    val matchedIdentityIds: List<Long>,
)

data class DraftSeed(
    val accountId: Long,
    val identityId: Long,
    val identityNeedsConfirmation: Boolean,
    val to: List<EmailAddress>,
    val cc: List<EmailAddress>,
    val subject: String,
    val inReplyTo: String?,
    val references: List<String>,
    val body: String,
)

object ReplyComposer {
    private val replyPrefix = Regex("^\\s*(re|답장|회신)\\s*:\\s*", RegexOption.IGNORE_CASE)
    private val forwardPrefix = Regex("^\\s*(fwd?|전달)\\s*:\\s*", RegexOption.IGNORE_CASE)

    fun reply(
        original: OriginalMessage,
        allIdentities: List<MailIdentity>,
        replyAll: Boolean,
        attribution: String,
    ): DraftSeed {
        val own = allIdentities.map { Addresses.normalize(it.address) }.toSet()
        val primary = original.replyTo.ifEmpty { listOfNotNull(original.from) }
        val primaryIsOwn = primary.isNotEmpty() && primary.all { Addresses.normalize(it.address) in own }
        val (to, cc) = if (replyAll) {
            val toCandidates = if (primaryIsOwn) original.to else primary + original.to
            dedupe(toCandidates, own) to dedupe(original.cc, own, alreadyUsed = toCandidates)
        } else {
            val target = if (primaryIsOwn) original.to else primary
            dedupe(target, own) to emptyList()
        }
        val (identity, needsConfirmation) = chooseIdentity(original, allIdentities)
        val id = original.messageId
        return DraftSeed(
            accountId = original.accountId,
            identityId = identity.id,
            identityNeedsConfirmation = needsConfirmation,
            to = to,
            cc = cc,
            subject = "Re: " + original.subject.replace(replyPrefix, ""),
            inReplyTo = id,
            references = (original.references + listOfNotNull(id)).distinct(),
            body = "\n\n" + attribution + "\n" + quote(original.text),
        )
    }

    fun forward(original: OriginalMessage, allIdentities: List<MailIdentity>, header: String): DraftSeed {
        val (identity, needsConfirmation) = chooseIdentity(original, allIdentities)
        return DraftSeed(
            accountId = original.accountId,
            identityId = identity.id,
            identityNeedsConfirmation = needsConfirmation,
            to = emptyList(),
            cc = emptyList(),
            subject = "Fwd: " + original.subject.replace(forwardPrefix, ""),
            inReplyTo = null,
            references = emptyList(),
            body = "\n\n" + header + "\n\n" + original.text,
        )
    }

    fun quote(text: String): String = text.lines().joinToString("\n") { if (it.isEmpty()) ">" else "> $it" }

    private fun chooseIdentity(original: OriginalMessage, all: List<MailIdentity>): Pair<MailIdentity, Boolean> {
        val account = all.filter { it.accountId == original.accountId }.sortedBy { it.sortOrder }
        require(account.isNotEmpty()) { "Account has no identity" }
        val matched = original.matchedIdentityIds.mapNotNull { id -> account.firstOrNull { it.id == id } }
        if (matched.isNotEmpty()) return matched.first() to (matched.size > 1)
        val fallback = account.firstOrNull { it.isAccountDefault } ?: account.first()
        return fallback to true
    }

    private fun dedupe(
        list: List<EmailAddress>,
        own: Set<String>,
        alreadyUsed: List<EmailAddress> = emptyList(),
    ): List<EmailAddress> {
        val seen = alreadyUsed.map { Addresses.normalize(it.address) }.toMutableSet()
        return list.filter { entry ->
            val key = Addresses.normalize(entry.address)
            key !in own && seen.add(key)
        }
    }
}
