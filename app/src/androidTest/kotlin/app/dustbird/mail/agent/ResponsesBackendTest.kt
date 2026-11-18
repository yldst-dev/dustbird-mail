package app.dustbird.mail.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.dustbird.mail.core.agent.AgentBackendException
import app.dustbird.mail.core.agent.AgentItem
import app.dustbird.mail.core.agent.AgentRequest
import app.dustbird.mail.core.agent.BackendFailure
import app.dustbird.mail.core.agent.ToolInvocation
import app.dustbird.mail.core.agent.ToolRisk
import app.dustbird.mail.core.agent.ToolSpec
import java.io.BufferedReader
import java.io.StringReader
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ResponsesBackendTest {
    private val tools = listOf(ToolSpec("search_mail", "Search", "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}}}", ToolRisk.SAFE))
    private val call = JSONObject().put("type", "function_call").put("id", "fc_1").put("status", "completed").put("call_id", "call_1").put("name", "search_mail").put("arguments", "{\"query\":\"kim\"}")
    private val message = JSONObject().put("type", "message").put("id", "msg_1").put("role", "assistant")
        .put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", "찾았습니다")))

    private fun request() = AgentRequest(
        "system rules",
        listOf(
            AgentItem.User("김 대리 메일 찾아줘"),
            AgentItem.Model(call.toString(), call = ToolInvocation("call_1", "search_mail", "{\"query\":\"kim\"}")),
            AgentItem.ToolOutput("call_1", "{\"mail\":[]}"),
        ),
        tools,
    )

    @Test
    fun requestBodyReplaysItemsWithoutServerIdsAndKeepsTheStatelessFlags() {
        val body = JSONObject(ResponsesWire.requestBody(request(), BackendConfig("https://x/v1", "secret", "gpt-6.1-sol")))
        assertEquals("gpt-6.1-sol", body.getString("model"))
        assertEquals("system rules", body.getString("instructions"))
        assertFalse(body.getBoolean("store"))
        assertTrue(body.getBoolean("stream"))
        val input = body.getJSONArray("input")
        assertEquals("user", input.getJSONObject(0).getString("role"))
        assertEquals("function_call", input.getJSONObject(1).getString("type"))
        assertFalse(input.getJSONObject(1).has("id"))
        assertFalse(input.getJSONObject(1).has("status"))
        assertEquals("call_1", input.getJSONObject(1).getString("call_id"))
        assertEquals("function_call_output", input.getJSONObject(2).getString("type"))
        assertEquals("{\"mail\":[]}", input.getJSONObject(2).getString("output"))
        val tool = body.getJSONArray("tools").getJSONObject(0)
        assertEquals("function", tool.getString("type"))
        assertEquals("search_mail", tool.getString("name"))
        assertFalse(body.toString().contains("secret"))
        listOf("temperature", "max_output_tokens", "previous_response_id", "metadata", "user").forEach { assertFalse(body.has(it)) }
    }

    @Test
    fun encryptedReasoningIsRequestedAndReplayedOnlyWhenItCanBeRead() {
        val sealed = JSONObject().put("type", "reasoning").put("id", "rs_1").put("encrypted_content", "abc")
        val bare = JSONObject().put("type", "reasoning").put("id", "rs_2").put("summary", JSONArray())
        val turn = request().let { it.copy(items = listOf(AgentItem.Model(sealed.toString()), AgentItem.Model(bare.toString())) + it.items) }
        val body = JSONObject(ResponsesWire.requestBody(turn, BackendConfig("https://x/v1", "t", "m", encryptedReasoning = true)))
        assertEquals("reasoning.encrypted_content", body.getJSONArray("include").getString(0))
        val input = body.getJSONArray("input")
        assertEquals(4, input.length())
        assertEquals("abc", input.getJSONObject(0).getString("encrypted_content"))
        assertFalse(input.getJSONObject(0).has("id"))
        assertEquals("user", input.getJSONObject(1).getString("role"))
        assertFalse(JSONObject(ResponsesWire.requestBody(turn, BackendConfig("https://x/v1", "t", "m"))).has("include"))
        val prefixed = ResponsesWire.itemOf(JSONObject(call.toString()).put("name", "mail.search_mail"))
        assertEquals("search_mail", prefixed.call?.name)
    }

    @Test
    fun streamYieldsDeltasItemsAndRequiresCompletion() {
        val deltas = StringBuilder()
        val stream = sse(
            JSONObject().put("type", "response.created"),
            JSONObject().put("type", "response.output_item.done").put("item", JSONObject().put("type", "reasoning").put("id", "rs_1").put("encrypted_content", "abc")),
            JSONObject().put("type", "response.output_item.done").put("item", call),
            JSONObject().put("type", "response.output_text.delta").put("delta", "찾았"),
            JSONObject().put("type", "response.output_text.delta").put("delta", "습니다"),
            JSONObject().put("type", "response.output_item.done").put("item", message),
            JSONObject().put("type", "response.completed").put("response", JSONObject()),
        )
        val items = ResponsesWire.readStream(BufferedReader(StringReader(stream))) { deltas.append(it) }
        assertEquals("찾았습니다", deltas.toString())
        assertEquals(3, items.size)
        assertNull(items[0].text)
        assertNull(items[0].call)
        assertEquals(ToolInvocation("call_1", "search_mail", "{\"query\":\"kim\"}"), items[1].call)
        assertEquals("찾았습니다", items[2].text)

        val cut = sse(JSONObject().put("type", "response.output_item.done").put("item", message))
        assertFailure(BackendFailure.INCOMPLETE) { ResponsesWire.readStream(BufferedReader(StringReader(cut))) {} }
        val failed = sse(JSONObject().put("type", "response.failed").put("response", JSONObject().put("error", JSONObject().put("code", "subscription_sharing_usage_limit_exceeded"))))
        assertFailure(BackendFailure.USAGE_LIMIT) { ResponsesWire.readStream(BufferedReader(StringReader(failed))) {} }
    }

    @Test
    fun httpRoundTripSendsTheBearerTokenAndParsesTheStream() = runBlocking {
        MockHttp { _, _ ->
            200 to sse(
                JSONObject().put("type", "response.output_item.done").put("item", message),
                JSONObject().put("type", "response.completed").put("response", JSONObject()),
            )
        }.use { server ->
            val backend = ResponsesBackend { BackendConfig(server.baseUrl + "/v1", "test-token", "m", mapOf("ChatGPT-Account-ID" to "acct-1", "originator" to "dustbird")) }
            val items = backend.respond(request()) {}
            assertEquals("찾았습니다", items.single().text)
            assertTrue(server.headers.single().contains("Authorization: Bearer test-token"))
            assertTrue(server.headers.single().contains("ChatGPT-Account-ID: acct-1"))
            assertTrue(server.headers.single().contains("originator: dustbird"))
            assertTrue(server.headers.single().startsWith("POST /v1/responses "))
            assertEquals("m", JSONObject(server.requests.single()).getString("model"))
        }
    }

    @Test
    fun theAnswerIsReadByItsContentWhenTheServerSendsNoContentType() = runBlocking {
        val stream = sse(
            JSONObject().put("type", "response.output_item.done").put("item", message),
            JSONObject().put("type", "response.completed").put("response", JSONObject()),
        )
        MockHttp(contentType = "") { _, _ -> 200 to stream }.use { server ->
            val backend = ResponsesBackend { BackendConfig(server.baseUrl, "t", "m") }
            assertEquals("찾았습니다", backend.respond(request()) {}.single().text)
        }
        MockHttp(contentType = "text/plain") { _, _ -> 200 to "\n  " + JSONObject().put("output", org.json.JSONArray().put(message)) }.use { server ->
            val backend = ResponsesBackend { BackendConfig(server.baseUrl, "t", "m") }
            assertEquals("찾았습니다", backend.respond(request()) {}.single().text)
        }
        MockHttp(contentType = "") { _, _ -> 200 to "event: response.created\ndata: not json\n\n" }.use { server ->
            val backend = ResponsesBackend { BackendConfig(server.baseUrl, "t", "m") }
            try {
                backend.respond(request()) {}
                fail("Expected an unreadable answer")
            } catch (error: AgentBackendException) {
                assertEquals(BackendFailure.SERVER, error.failure)
                assertTrue(error.message.orEmpty().contains("response.created"))
            }
        }
    }

    @Test
    fun httpErrorsAreClassified() = runBlocking {
        MockHttp(contentType = "application/json") { _, _ -> 401 to "{\"error\":{\"message\":\"bad key\",\"code\":\"invalid_api_key\"}}" }.use { server ->
            val backend = ResponsesBackend { BackendConfig(server.baseUrl, "t", "m") }
            assertFailure(BackendFailure.AUTH) { backend.respond(request()) {} }
        }
        MockHttp(contentType = "application/json") { _, _ -> 429 to "{}" }.use { server ->
            assertFailure(BackendFailure.USAGE_LIMIT) { ResponsesBackend { BackendConfig(server.baseUrl, "t", "m") }.respond(request()) {} }
        }
        assertFailure(BackendFailure.NOT_CONFIGURED) { ResponsesBackend { null }.respond(request()) {} }
        assertFailure(BackendFailure.NETWORK) { ResponsesBackend { BackendConfig("http://127.0.0.1:9", "t", "m") }.respond(request()) {} }
    }

    private inline fun assertFailure(expected: BackendFailure, block: () -> Unit) {
        try {
            block()
            fail("Expected $expected")
        } catch (error: AgentBackendException) {
            assertEquals(expected, error.failure)
        }
    }
}
