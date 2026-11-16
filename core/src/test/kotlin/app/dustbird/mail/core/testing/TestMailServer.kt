package app.dustbird.mail.core.testing

import java.io.File
import java.net.URLClassLoader

class TestMailServer private constructor(
    private val greenMail: Any,
    private val loader: URLClassLoader,
    val bindHost: String,
    val imapPort: Int,
    val smtpPort: Int,
) : AutoCloseable {

    val host: String = "localhost"

    override fun close() {
        greenMail.javaClass.getMethod("stop").invoke(greenMail)
        loader.close()
    }

    companion object {
        fun start(
            users: Map<String, String>,
            bindHost: String = "127.0.0.1",
            imapPort: Int = Fixtures.freePort(),
            smtpPort: Int = Fixtures.freePort(),
            keystore: File = Fixtures.file("test-server.p12"),
        ): TestMailServer {
            System.setProperty("greenmail.tls.keystore.file", keystore.absolutePath)
            System.setProperty("greenmail.tls.keystore.password", Fixtures.PASSWORD)
            val urls = System.getProperty("greenmail.classpath").split(File.pathSeparator).map { File(it).toURI().toURL() }
            val loader = URLClassLoader(urls.toTypedArray(), ClassLoader.getPlatformClassLoader())
            val setupClass = loader.loadClass("com.icegreen.greenmail.util.ServerSetup")
            val setupConstructor = setupClass.getConstructor(Int::class.javaPrimitiveType, String::class.java, String::class.java)
            val setups = java.lang.reflect.Array.newInstance(setupClass, 2)
            java.lang.reflect.Array.set(setups, 0, setupConstructor.newInstance(imapPort, bindHost, "imaps"))
            java.lang.reflect.Array.set(setups, 1, setupConstructor.newInstance(smtpPort, bindHost, "smtps"))
            val greenMailClass = loader.loadClass("com.icegreen.greenmail.util.GreenMail")
            val greenMail = greenMailClass.getConstructor(setups.javaClass).newInstance(setups)
            val setUser = greenMailClass.getMethod("setUser", String::class.java, String::class.java, String::class.java)
            users.forEach { (address, password) -> setUser.invoke(greenMail, address, address, password) }
            greenMailClass.getMethod("start").invoke(greenMail)
            return TestMailServer(greenMail, loader, bindHost, imapPort, smtpPort)
        }
    }
}
