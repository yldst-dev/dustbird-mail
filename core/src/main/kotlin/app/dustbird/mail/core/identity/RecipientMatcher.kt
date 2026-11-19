package app.dustbird.mail.core.identity

import app.dustbird.mail.core.model.EmailAddress
import app.dustbird.mail.core.model.MailIdentity
import app.dustbird.mail.core.provider.Providers

data class ReceivedAddressing(
    val to: List<EmailAddress>,
    val cc: List<EmailAddress>,
    val deliveryHeaderValues: List<String>,
)

sealed interface RecipientMatch {
    data class Matched(val identityIds: List<Long>) : RecipientMatch
    data class OwnAddress(val address: String) : RecipientMatch
    data object Unknown : RecipientMatch
    data object NotReceived : RecipientMatch
}

object RecipientMatcher {
    private val appleMailDomains = setOf("icloud.com", "me.com", "mac.com")
    private val appleRelayDomains = appleMailDomains + "privaterelay.appleid.com"

    fun match(received: ReceivedAddressing, accountIdentities: List<MailIdentity>, ownAddress: (String) -> Boolean = { false }): RecipientMatch {
        val ordered = accountIdentities.sortedBy { it.sortOrder }
        val visible = (received.to + received.cc).map { Addresses.normalize(it.address) }
        val fromRecipients = ordered.filter { Addresses.normalize(it.address) in visible }
        if (fromRecipients.isNotEmpty()) return RecipientMatch.Matched(fromRecipients.map { it.id })
        val delivered = received.deliveryHeaderValues
            .flatMap { Addresses.parseList(it) }
            .map { Addresses.normalize(it.address) }
        val fromHeaders = ordered.filter { Addresses.normalize(it.address) in delivered }
        if (fromHeaders.isNotEmpty()) return RecipientMatch.Matched(fromHeaders.map { it.id })
        val own = (delivered + visible).firstOrNull { it.isNotEmpty() && ownAddress(it) }
        return if (own != null) RecipientMatch.OwnAddress(own) else RecipientMatch.Unknown
    }

    fun ownAddresses(
        providerId: String,
        loginName: String,
        accountIdentities: List<MailIdentity>,
        sentFrom: Collection<String> = emptyList(),
    ): (String) -> Boolean {
        val login = Addresses.normalize(loginName).takeIf { '@' in it }
        val sent = sentFrom.map { Addresses.normalize(it) }.filter { '@' in it }.toSet()
        if (providerId != Providers.ICLOUD.id) return { address -> address == login || address in sent }
        val customDomains = (accountIdentities.map { Addresses.normalize(it.address) } + sent).map(::domainOf)
            .filter { it.isNotEmpty() && it !in appleRelayDomains }
            .toSet()
        return { address -> address == login || domainOf(address).let { it in customDomains || it in appleRelayDomains } }
    }

    fun isHideMyEmail(providerId: String, loginName: String, address: String): Boolean {
        val key = Addresses.normalize(address)
        return providerId == Providers.ICLOUD.id && key != Addresses.normalize(loginName) && domainOf(key) in appleMailDomains
    }

    private fun domainOf(address: String): String = address.substringAfterLast('@', "")
}
