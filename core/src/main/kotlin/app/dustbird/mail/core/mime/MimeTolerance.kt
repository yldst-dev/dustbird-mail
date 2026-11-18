package app.dustbird.mail.core.mime

object MimeTolerance {
    init {
        mapOf(
            "mail.mime.decodetext.strict" to "false",
            "mail.mime.decodefilename" to "true",
            "mail.mime.base64.ignoreerrors" to "true",
            "mail.mime.ignoreunknownencoding" to "true",
            "mail.mime.uudecode.ignoreerrors" to "true",
            "mail.mime.uudecode.ignoremissingbeginend" to "true",
            "mail.mime.parameters.strict" to "false",
            "mail.mime.multipart.allowempty" to "true",
        ).forEach { (name, value) -> if (System.getProperty(name) == null) System.setProperty(name, value) }
    }

    fun ensure() = Unit
}
