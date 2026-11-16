package app.dustbird.mail.core.identity

import app.dustbird.mail.core.model.EmailAddress
import app.dustbird.mail.core.model.MailIdentity

data class ReceivedAddressing(
    val to: List<EmailAddress>,
    val cc: List<EmailAddress>,
    val deliveryHeaderValues: List<String>,
)

sealed interface RecipientMatch {
    data class Matched(val identityIds: List<Long>) : RecipientMatch
    data object Unknown : RecipientMatch
}

object RecipientMatcher {
    fun match(received: ReceivedAddressing, accountIdentities: List<MailIdentity>): RecipientMatch {
        val ordered = accountIdentities.sortedBy { it.sortOrder }
        val visible = (received.to + received.cc).map { Addresses.normalize(it.address) }.toSet()
        val fromRecipients = ordered.filter { Addresses.normalize(it.address) in visible }
        if (fromRecipients.isNotEmpty()) return RecipientMatch.Matched(fromRecipients.map { it.id })
        val delivered = received.deliveryHeaderValues
            .flatMap { Addresses.parseList(it) }
            .map { Addresses.normalize(it.address) }
            .toSet()
        val fromHeaders = ordered.filter { Addresses.normalize(it.address) in delivered }
        if (fromHeaders.isNotEmpty()) return RecipientMatch.Matched(fromHeaders.map { it.id })
        return RecipientMatch.Unknown
    }
}
