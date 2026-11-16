package app.dustbird.mail.core.testing

import java.io.File
import java.net.ServerSocket

object Fixtures {
    const val PASSWORD = "changeit"

    fun file(name: String): File = File(System.getProperty("fixtures.dir"), name)

    fun freePort(): Int = ServerSocket(0).use { it.localPort }

    fun freePorts(count: Int): List<Int> {
        val sockets = List(count) { ServerSocket(0) }
        return sockets.map { it.localPort }.also { sockets.forEach(ServerSocket::close) }
    }
}
