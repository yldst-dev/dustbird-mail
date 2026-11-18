package app.dustbird.mail.core.provider

import app.dustbird.mail.core.model.AuthMethod
import app.dustbird.mail.core.model.ServerEndpoint
import app.dustbird.mail.core.model.TransportSecurity
import app.dustbird.mail.core.model.UsernameStyle

enum class ProfileVerification { CONFIRMED_BY_PRIOR_CLIENT, PREAUTH_PROBED, UNVERIFIED }

data class ProviderProfile(
    val id: String,
    val imap: ServerEndpoint?,
    val smtp: ServerEndpoint?,
    val usernameStyle: UsernameStyle,
    val authMethods: Set<AuthMethod>,
    val serverSavesSentCopy: Boolean,
    val multiFolderVisibility: Boolean,
    val deliveryHeaders: List<String>,
    val maxConnections: Int,
    val verification: ProfileVerification,
)

object Providers {
    private val defaultDeliveryHeaders = listOf("Delivered-To", "X-Original-To")

    val ICLOUD = ProviderProfile(
        id = "icloud",
        imap = ServerEndpoint("imap.mail.me.com", 993, TransportSecurity.TLS),
        smtp = ServerEndpoint("smtp.mail.me.com", 587, TransportSecurity.STARTTLS),
        usernameStyle = UsernameStyle.FULL_ADDRESS,
        authMethods = setOf(AuthMethod.APP_PASSWORD),
        serverSavesSentCopy = false,
        multiFolderVisibility = false,
        deliveryHeaders = defaultDeliveryHeaders,
        maxConnections = 2,
        verification = ProfileVerification.CONFIRMED_BY_PRIOR_CLIENT,
    )

    val GMAIL = ProviderProfile(
        id = "gmail",
        imap = ServerEndpoint("imap.gmail.com", 993, TransportSecurity.TLS),
        smtp = ServerEndpoint("smtp.gmail.com", 465, TransportSecurity.TLS),
        usernameStyle = UsernameStyle.FULL_ADDRESS,
        authMethods = setOf(AuthMethod.APP_PASSWORD),
        serverSavesSentCopy = true,
        multiFolderVisibility = true,
        deliveryHeaders = defaultDeliveryHeaders,
        maxConnections = 2,
        verification = ProfileVerification.PREAUTH_PROBED,
    )

    val NAVER = ProviderProfile(
        id = "naver",
        imap = ServerEndpoint("imap.naver.com", 993, TransportSecurity.TLS),
        smtp = ServerEndpoint("smtp.naver.com", 587, TransportSecurity.STARTTLS),
        usernameStyle = UsernameStyle.FULL_ADDRESS,
        authMethods = setOf(AuthMethod.APP_PASSWORD),
        serverSavesSentCopy = false,
        multiFolderVisibility = false,
        deliveryHeaders = defaultDeliveryHeaders,
        maxConnections = 2,
        verification = ProfileVerification.PREAUTH_PROBED,
    )

    val OUTLOOK = ProviderProfile(
        id = "outlook",
        imap = ServerEndpoint("outlook.office365.com", 993, TransportSecurity.TLS),
        smtp = ServerEndpoint("smtp-mail.outlook.com", 587, TransportSecurity.STARTTLS),
        usernameStyle = UsernameStyle.FULL_ADDRESS,
        authMethods = setOf(AuthMethod.OAUTH2),
        serverSavesSentCopy = true,
        multiFolderVisibility = false,
        deliveryHeaders = defaultDeliveryHeaders,
        maxConnections = 2,
        verification = ProfileVerification.UNVERIFIED,
    )

    val CUSTOM = ProviderProfile(
        id = "custom",
        imap = null,
        smtp = null,
        usernameStyle = UsernameStyle.FULL_ADDRESS,
        authMethods = setOf(AuthMethod.APP_PASSWORD),
        serverSavesSentCopy = false,
        multiFolderVisibility = false,
        deliveryHeaders = defaultDeliveryHeaders,
        maxConnections = 2,
        verification = ProfileVerification.UNVERIFIED,
    )

    val all: List<ProviderProfile> = listOf(ICLOUD, GMAIL, NAVER, OUTLOOK, CUSTOM)

    fun byId(id: String): ProviderProfile = all.first { it.id == id }

    private val appleMailDomains = setOf("icloud.com", "me.com", "mac.com")

    fun isCustomICloudDomain(profile: ProviderProfile, address: String): Boolean {
        if (profile.id != ICLOUD.id || '@' !in address) return false
        val domain = address.substringAfterLast('@').trim().lowercase()
        return domain.contains('.') && domain !in appleMailDomains
    }

    fun usernameFor(profile: ProviderProfile, address: String): String = when {
        isCustomICloudDomain(profile, address) -> ""
        profile.usernameStyle == UsernameStyle.FULL_ADDRESS -> address
        else -> address.substringBefore('@')
    }
}
