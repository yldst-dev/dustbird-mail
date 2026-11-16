package app.dustbird.mail.core.testing

fun main() {
    val server = TestMailServer.start(
        users = mapOf("tester@example.test" to "tester-pw", "friend@example.test" to "friend-pw"),
        bindHost = "0.0.0.0",
        keystore = Fixtures.file("emulator-server.p12"),
        imapPort = 3993,
        smtpPort = 3465,
    )
    println("Test mail server ready: imaps=${server.imapPort} smtps=${server.smtpPort}")
    Runtime.getRuntime().addShutdownHook(Thread { server.close() })
    Thread.currentThread().join()
}
