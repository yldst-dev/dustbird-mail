Test-only TLS fixtures. The private keys here protect nothing: they are throwaway keys for local test servers (GreenMail and scripted sockets) and are trusted only by the JVM tests and by the debug build's network security configuration. Password for every store is `changeit`.

- test-server.p12: certificate for localhost, used by JVM tests
- emulator-server.p12: certificate whose name is 10.0.2.2, used by the emulator reaching a server on the host. Jakarta Mail compares only DNS entries of a certificate, so an IP address must be the common name
- wrong-host.p12: certificate for wrong.example.test, used to prove host name mismatch is rejected
- test-truststore.p12: trust anchors for all three certificates, used by JVM tests only
