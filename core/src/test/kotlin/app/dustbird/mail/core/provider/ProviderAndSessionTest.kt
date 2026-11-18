package app.dustbird.mail.core.provider

import app.dustbird.mail.core.model.AuthMethod
import app.dustbird.mail.core.model.ServerEndpoint
import app.dustbird.mail.core.model.TransportSecurity
import app.dustbird.mail.core.net.SessionConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProviderAndSessionTest {
    @Test
    fun profilesHaveUniqueIdsAndOnlyCustomLeavesServersEmpty() {
        assertEquals(Providers.all.size, Providers.all.map { it.id }.toSet().size)
        Providers.all.filter { it.id != "custom" }.forEach {
            assertNotNull(it.imap)
            assertNotNull(it.smtp)
            assertTrue(it.imap!!.port in 1..65535 && it.smtp!!.port in 1..65535)
        }
        assertNull(Providers.CUSTOM.imap)
    }

    @Test
    fun profilesMatchResearchedServerSettings() {
        assertEquals(ServerEndpoint("imap.mail.me.com", 993, TransportSecurity.TLS), Providers.ICLOUD.imap)
        assertEquals(ServerEndpoint("smtp.mail.me.com", 587, TransportSecurity.STARTTLS), Providers.ICLOUD.smtp)
        assertEquals(ServerEndpoint("imap.gmail.com", 993, TransportSecurity.TLS), Providers.GMAIL.imap)
        assertEquals(ServerEndpoint("imap.naver.com", 993, TransportSecurity.TLS), Providers.NAVER.imap)
        assertEquals(587, Providers.NAVER.smtp!!.port)
    }

    @Test
    fun onlyOutlookUsesTokenAuthentication() {
        assertEquals(listOf("outlook"), Providers.all.filter { AuthMethod.OAUTH2 in it.authMethods }.map { it.id })
        assertEquals(ServerEndpoint("outlook.office365.com", 993, TransportSecurity.TLS), Providers.OUTLOOK.imap)
        assertEquals(ServerEndpoint("smtp-mail.outlook.com", 587, TransportSecurity.STARTTLS), Providers.OUTLOOK.smtp)
    }

    @Test
    fun bearerCredentialsSwitchBothProtocolsToXoauth2AndHideThePrefix() {
        val endpoint = ServerEndpoint("h.example.test", 1, TransportSecurity.TLS)
        val token = app.dustbird.mail.core.model.Credentials("user@example.test", app.dustbird.mail.core.model.Credentials.BEARER_PREFIX + "token-1")
        assertTrue(token.bearer)
        assertEquals("token-1", token.password)
        assertEquals("XOAUTH2", SessionConfig.imap(endpoint, token.bearer).getProperty("mail.imap.auth.mechanisms"))
        assertEquals("XOAUTH2", SessionConfig.smtp(endpoint, bearer = token.bearer).getProperty("mail.smtp.auth.mechanisms"))
        val plain = app.dustbird.mail.core.model.Credentials("user@example.test", "app-password")
        assertFalse(plain.bearer)
        assertEquals("app-password", plain.password)
    }

    @Test
    fun usernameFollowsProfileStyle() {
        assertEquals("user@icloud.com", Providers.usernameFor(Providers.ICLOUD, "user@icloud.com"))
        assertEquals("user@me.com", Providers.usernameFor(Providers.ICLOUD, "user@me.com"))
        assertEquals("user@example.test", Providers.usernameFor(Providers.GMAIL, "user@example.test"))
    }

    @Test
    fun aCustomDomainOnICloudLeavesTheLoginNameForTheICloudAddress() {
        assertTrue(Providers.isCustomICloudDomain(Providers.ICLOUD, "user@example.test"))
        assertEquals("", Providers.usernameFor(Providers.ICLOUD, "user@example.test"))
        assertFalse(Providers.isCustomICloudDomain(Providers.ICLOUD, "user@iCloud.com"))
        assertFalse(Providers.isCustomICloudDomain(Providers.ICLOUD, "user@exa"))
        assertFalse(Providers.isCustomICloudDomain(Providers.GMAIL, "user@example.test"))
    }

    @Test
    fun implicitTlsEndpointsRequireIdentityCheckAndModernProtocols() {
        val imap = SessionConfig.imap(ServerEndpoint("h.example.test", 993, TransportSecurity.TLS))
        assertEquals("true", imap.getProperty("mail.imap.ssl.enable"))
        assertEquals("true", imap.getProperty("mail.imap.ssl.checkserveridentity"))
        assertEquals("TLSv1.2 TLSv1.3", imap.getProperty("mail.imap.ssl.protocols"))
        val smtp = SessionConfig.smtp(ServerEndpoint("h.example.test", 465, TransportSecurity.TLS), "me@example.test")
        assertEquals("true", smtp.getProperty("mail.smtp.ssl.enable"))
        assertEquals("true", smtp.getProperty("mail.smtp.ssl.checkserveridentity"))
        assertEquals("me@example.test", smtp.getProperty("mail.smtp.from"))
    }

    @Test
    fun starttlsEndpointsRefuseToContinueWithoutTls() {
        val smtp = SessionConfig.smtp(ServerEndpoint("h.example.test", 587, TransportSecurity.STARTTLS))
        assertEquals("true", smtp.getProperty("mail.smtp.starttls.enable"))
        assertEquals("true", smtp.getProperty("mail.smtp.starttls.required"))
        assertEquals("true", smtp.getProperty("mail.smtp.ssl.checkserveridentity"))
        assertNull(smtp.getProperty("mail.smtp.ssl.enable"))
    }

    @Test
    fun noTrustOverrideAndNoWeakAuthMechanismIsConfigured() {
        TransportSecurity.entries.forEach { security ->
            val endpoint = ServerEndpoint("h.example.test", 1, security)
            listOf(SessionConfig.imap(endpoint), SessionConfig.smtp(endpoint)).forEach { props ->
                val keys = props.stringPropertyNames()
                assertFalse(keys.any { it.endsWith(".ssl.trust") || it.endsWith(".ssl.socketFactory") || it.endsWith(".ssl.socketFactory.class") })
                assertFalse(keys.any { it.endsWith(".debug") } || "mail.debug" in keys)
                assertEquals("false", props.getProperty("mail.mime.allowutf8"))
            }
            assertEquals("PLAIN LOGIN", SessionConfig.smtp(endpoint).getProperty("mail.smtp.auth.mechanisms"))
            assertEquals("PLAIN LOGIN", SessionConfig.imap(endpoint).getProperty("mail.imap.auth.mechanisms"))
        }
    }

    @Test
    fun smtpTransportUrlCarriesTheProtocolName() {
        assertEquals("smtp", SessionConfig.smtpUrl.protocol)
    }
}
