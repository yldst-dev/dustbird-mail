package app.dustbird.mail.core.testing

import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.util.Base64
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

class ScriptedSmtpServer(
    private val scenario: Scenario,
    keystore: File = Fixtures.file("test-server.p12"),
) : AutoCloseable {
    enum class Scenario { ACCEPT, NO_STARTTLS, DROP_DURING_DATA, DROP_AFTER_DATA, REJECT_AUTH, REJECT_MAIL, REJECT_RCPT, REJECT_FINAL }

    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    private val context: SSLContext = SSLContext.getInstance("TLS").apply {
        val store = KeyStore.getInstance("PKCS12").apply { FileInputStream(keystore).use { load(it, Fixtures.PASSWORD.toCharArray()) } }
        val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, Fixtures.PASSWORD.toCharArray()) }
        init(factory.keyManagers, null, null)
    }

    val port: Int get() = server.localPort

    @Volatile var tlsUsed = false
    @Volatile var envelopeFrom: String? = null
    @Volatile var data: String? = null
    val recipients = mutableListOf<String>()

    init {
        Thread { runCatching { handle(server.accept()) } }.apply { isDaemon = true }.start()
    }

    private fun handle(raw: Socket) {
        var socket: Socket = raw
        var reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
        var out: OutputStream = socket.getOutputStream()
        fun send(line: String) {
            out.write((line + "\r\n").toByteArray(Charsets.ISO_8859_1))
            out.flush()
        }
        send("220 scripted ESMTP")
        while (true) {
            val line = reader.readLine() ?: return
            val upper = line.uppercase()
            when {
                upper.startsWith("EHLO") || upper.startsWith("HELO") -> {
                    send("250-scripted")
                    if (!tlsUsed && scenario != Scenario.NO_STARTTLS) send("250-STARTTLS")
                    send("250-AUTH PLAIN LOGIN")
                    send("250 8BITMIME")
                }
                upper == "STARTTLS" -> {
                    send("220 Ready to start TLS")
                    val tls = context.socketFactory.createSocket(socket, null, socket.port, true) as SSLSocket
                    tls.useClientMode = false
                    tls.startHandshake()
                    socket = tls
                    reader = BufferedReader(InputStreamReader(tls.inputStream, Charsets.ISO_8859_1))
                    out = tls.outputStream
                    tlsUsed = true
                }
                upper.startsWith("AUTH PLAIN") -> {
                    if (upper == "AUTH PLAIN") {
                        send("334 ")
                        reader.readLine() ?: return
                    }
                    send(if (scenario == Scenario.REJECT_AUTH) "535 5.7.8 Authentication credentials invalid" else "235 2.7.0 Accepted")
                }
                upper.startsWith("AUTH LOGIN") -> {
                    send("334 " + Base64.getEncoder().encodeToString("Username:".toByteArray()))
                    reader.readLine() ?: return
                    send("334 " + Base64.getEncoder().encodeToString("Password:".toByteArray()))
                    reader.readLine() ?: return
                    send(if (scenario == Scenario.REJECT_AUTH) "535 5.7.8 Authentication credentials invalid" else "235 2.7.0 Accepted")
                }
                upper.startsWith("MAIL FROM") -> {
                    envelopeFrom = line.substringAfter('<').substringBefore('>')
                    send(if (scenario == Scenario.REJECT_MAIL) "553 5.7.1 Sender address rejected" else "250 2.1.0 OK")
                }
                upper.startsWith("RCPT TO") -> {
                    if (scenario == Scenario.REJECT_RCPT) {
                        send("550 5.1.1 No such user")
                    } else {
                        recipients += line.substringAfter('<').substringBefore('>')
                        send("250 2.1.5 OK")
                    }
                }
                upper == "DATA" -> {
                    send("354 Go ahead")
                    val body = StringBuilder()
                    var lines = 0
                    while (true) {
                        val dataLine = reader.readLine() ?: return
                        if (dataLine == ".") break
                        body.append(dataLine).append("\r\n")
                        lines++
                        if (scenario == Scenario.DROP_DURING_DATA && lines == 3) {
                            socket.setSoLinger(true, 0)
                            socket.close()
                            return
                        }
                    }
                    data = body.toString()
                    when (scenario) {
                        Scenario.DROP_AFTER_DATA -> {
                            socket.setSoLinger(true, 0)
                            socket.close()
                            return
                        }
                        Scenario.REJECT_FINAL -> send("554 5.7.1 Message rejected as spam")
                        else -> send("250 2.0.0 Queued")
                    }
                }
                upper == "RSET" -> send("250 OK")
                upper == "NOOP" -> send("250 OK")
                upper == "QUIT" -> {
                    send("221 Bye")
                    socket.close()
                    return
                }
                else -> send("502 Command not implemented")
            }
        }
    }

    override fun close() {
        runCatching { server.close() }
    }
}
