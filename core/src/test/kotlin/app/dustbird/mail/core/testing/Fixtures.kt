package app.dustbird.mail.core.testing

import java.io.File
import java.io.FileInputStream
import java.net.ServerSocket
import java.security.KeyStore
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

object Fixtures {
    const val PASSWORD = "changeit"

    fun file(name: String): File = File(System.getProperty("fixtures.dir"), name)

    fun freePort(): Int = ServerSocket(0).use { it.localPort }

    fun trustFixtures() {
        val store = KeyStore.getInstance("PKCS12").apply {
            FileInputStream(file("test-truststore.p12")).use { load(it, PASSWORD.toCharArray()) }
        }
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        val context = SSLContext.getInstance("TLS").apply { init(null, factory.trustManagers, null) }
        SSLContext.setDefault(context)
    }
}
