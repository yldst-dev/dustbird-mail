package app.dustbird.mail.agent

import app.dustbird.mail.core.agent.AgentBackend
import app.dustbird.mail.core.agent.AgentItem
import app.dustbird.mail.core.agent.AgentRequest
import app.dustbird.mail.core.agent.ToolInvocation
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import org.json.JSONObject

class ScriptedBackend(private val turns: List<(AgentRequest) -> List<AgentItem.Model>>) : AgentBackend {
    val requests = CopyOnWriteArrayList<AgentRequest>()

    override suspend fun respond(request: AgentRequest, onTextDelta: (String) -> Unit): List<AgentItem.Model> {
        requests += request
        return turns[minOf(requests.size, turns.size) - 1](request)
    }
}

fun callItem(id: String, name: String, arguments: JSONObject): AgentItem.Model {
    val raw = JSONObject().put("type", "function_call").put("call_id", id).put("name", name).put("arguments", arguments.toString())
    return AgentItem.Model(raw.toString(), call = ToolInvocation(id, name, arguments.toString()))
}

fun textItem(text: String): AgentItem.Model = AgentItem.Model(
    JSONObject().put("type", "message").put("role", "assistant").put("content", org.json.JSONArray().put(JSONObject().put("type", "output_text").put("text", text))).toString(),
    text = text,
)

fun AgentRequest.lastOutput(): JSONObject = JSONObject(items.filterIsInstance<AgentItem.ToolOutput>().last().output)

class MockHttp(private val contentType: String = "text/event-stream", private val respond: (path: String, body: String) -> Pair<Int, String>) : Closeable {
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val requests = CopyOnWriteArrayList<String>()
    val headers = CopyOnWriteArrayList<String>()
    val baseUrl: String get() = "http://127.0.0.1:${server.localPort}"

    init {
        thread(isDaemon = true, name = "mock-http") {
            while (!server.isClosed) {
                val client = try {
                    server.accept()
                } catch (_: Exception) {
                    break
                }
                client.use { socket ->
                    val input = socket.getInputStream()
                    val head = StringBuilder()
                    while (!head.endsWith("\r\n\r\n")) {
                        val next = input.read()
                        if (next < 0) break
                        head.append(next.toChar())
                    }
                    val lines = head.toString().split("\r\n")
                    val length = lines.firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
                    val body = ByteArray(length)
                    var read = 0
                    while (read < length) {
                        val count = input.read(body, read, length - read)
                        if (count < 0) break
                        read += count
                    }
                    val text = String(body, Charsets.UTF_8)
                    requests += text
                    headers += head.toString()
                    val (status, answer) = respond(lines.first().split(' ').getOrElse(1) { "" }, text)
                    val bytes = answer.toByteArray(Charsets.UTF_8)
                    socket.getOutputStream().apply {
                        val type = if (contentType.isEmpty()) "" else "Content-Type: $contentType\r\n"
                        write("HTTP/1.1 $status X\r\n${type}Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                        write(bytes)
                        flush()
                    }
                }
            }
        }
    }

    override fun close() = server.close()
}

fun sse(vararg events: JSONObject): String = events.joinToString("") { "event: ${it.getString("type")}\ndata: $it\n\n" }
