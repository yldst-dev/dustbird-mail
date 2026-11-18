package app.dustbird.mail.ui.agent

import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.dustbird.mail.AppContainer
import app.dustbird.mail.R
import app.dustbird.mail.agent.AgentService
import app.dustbird.mail.agent.ChatGptCredential
import app.dustbird.mail.agent.CodexAuth
import app.dustbird.mail.agent.MockHttp
import app.dustbird.mail.agent.ModelLists
import app.dustbird.mail.core.net.ImapConnector
import app.dustbird.mail.data.AgentMode
import app.dustbird.mail.data.AgentSettings
import app.dustbird.mail.data.db.MailDatabase
import app.dustbird.mail.ui.mailbox.MemoryCredentials
import app.dustbird.mail.ui.theme.DustbirdTheme
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModelPickerTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val db = Room.inMemoryDatabaseBuilder(context, MailDatabase::class.java).build()
    private val secrets = MemoryCredentials()
    private val container = AppContainer(context, connector = ImapConnector { _, _ -> throw IOException("no network in tests") }, credentials = secrets, db = db)
    private val paths = CopyOnWriteArrayList<String>()

    private val catalog = JSONObject().put(
        "models",
        JSONArray()
            .put(JSONObject().put("slug", "gpt-new-astra").put("display_name", "GPT New Astra").put("description", "Most capable.").put("visibility", "list").put("priority", 2))
            .put(JSONObject().put("slug", "gpt-new-sol").put("display_name", "GPT New Sol").put("description", "Fast.").put("visibility", "list").put("priority", 1)),
    ).toString()

    private fun string(id: Int, vararg args: Any): String = context.getString(id, *args)

    private fun service(server: MockHttp): AgentService = runBlocking {
        container.settings.clearAll()
        secrets.put(AgentService.CHATGPT_ID, ChatGptCredential("access-1", "refresh-1", System.currentTimeMillis() + 3_600_000, "acct-1", "user@example.com").toJson())
        AgentService(
            db, container.settings, secrets, container.accounts, container.toolbox, container.scope,
            userAgent = "Dustbird/test", codexBase = server.baseUrl + "/codex", codexRelease = server.baseUrl + "/release",
        ).also { it.update { settings -> settings.copy(mode = AgentMode.CHATGPT, consent = true) } }
    }

    private fun show(service: AgentService, mode: AgentMode = AgentMode.CHATGPT) = compose.setContent {
        DustbirdTheme {
            val settings by service.current.collectAsState(AgentSettings())
            ModelPicker(mode, settings.model, service)
        }
    }

    private fun waitForText(text: String) = compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    @After
    fun tearDown() {
        runBlocking { container.settings.clearAll() }
        container.scope.cancel()
        db.close()
    }

    @Test
    fun theLatestListIsShownAndAChoiceIsSaved() {
        MockHttp(contentType = "application/json") { path, _ ->
            paths += path
            when {
                path == "/release" -> 200 to "{\"tag_name\":\"rust-v0.175.1\",\"prerelease\":false}"
                path.startsWith("/codex/models?") -> 200 to catalog
                else -> 404 to "{}"
            }
        }.use { server ->
            val service = service(server)
            show(service)
            waitForText(string(R.string.agent_model_auto_value, "GPT New Sol"))
            assertTrue(paths.contains("/codex/models?client_version=0.175.1"))

            compose.onNodeWithTag(ModelPickerTag).performClick()
            compose.onNodeWithText(string(R.string.agent_model_sheet_title)).assertExists()
            compose.onNodeWithText(string(R.string.agent_model_auto_text, "GPT New Sol")).assertExists()
            compose.onNodeWithText("Most capable.").assertExists()
            compose.onNodeWithText("GPT New Astra").performClick()

            compose.waitUntil(10_000) { runBlocking { service.current.first().model } == "gpt-new-astra" }
            compose.waitUntil(10_000) { compose.onAllNodesWithText(string(R.string.agent_model_sheet_title)).fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithText("GPT New Astra").assertExists()
            assertEquals("gpt-new-astra", runBlocking { service.config() }?.model)
        }
    }

    @Test
    fun aRefusedListSaysSoAndOffersARetryWithTheFallbackVersion() {
        MockHttp(contentType = "application/json") { path, _ ->
            paths += path
            when {
                path == "/release" -> 503 to "{}"
                else -> 401 to "{\"error\":{\"code\":\"token_expired\"}}"
            }
        }.use { server ->
            val service = service(server)
            show(service)
            compose.onNodeWithTag(ModelPickerTag).performClick()
            waitForText(string(R.string.agent_error_auth))
            assertTrue(paths.contains("/codex/models?client_version=${CodexAuth.FALLBACK_VERSION}"))
            compose.onNodeWithText(string(R.string.agent_model_auto_text_plain)).assertExists()

            val asked = paths.size
            compose.onNodeWithText(string(R.string.agent_model_retry)).performClick()
            compose.waitUntil(10_000) { paths.size > asked }
            waitForText(string(R.string.agent_error_auth))
        }
    }

    @Test
    fun anApiKeyServerListsItsOwnModelsAndAChoiceIsSaved() {
        MockHttp(contentType = "application/json") { path, _ ->
            paths += path
            if (path == "/v1/models") {
                200 to "{\"object\":\"list\",\"data\":[{\"id\":\"local-small\",\"created\":1},{\"id\":\"local-large\",\"created\":3},{\"id\":\"../bad\",\"created\":2}]}"
            } else {
                404 to "{}"
            }
        }.use { server ->
            val service = service(server)
            runBlocking {
                service.update { it.copy(model = "gpt-new-sol") }
                service.update { it.copy(mode = AgentMode.API_KEY) }
                assertEquals("", service.current.first().model)
                service.saveConnection(AgentMode.API_KEY, server.baseUrl + "/v1", "", "sk-test-1")
            }
            show(service, AgentMode.API_KEY)
            waitForText(string(R.string.agent_model_auto_value, AgentService.DEFAULT_MODEL))
            compose.waitUntil(10_000) { runBlocking { service.models(AgentMode.API_KEY).first() }.size == 2 }
            assertTrue(server.headers.any { it.startsWith("GET /v1/models ") && "Authorization: Bearer sk-test-1" in it })

            compose.onNodeWithTag(ModelPickerTag).performClick()
            compose.onNodeWithText(string(R.string.agent_model_sheet_body_server)).assertExists()
            compose.onNodeWithText(string(R.string.agent_model_auto_text_api, AgentService.DEFAULT_MODEL)).assertExists()
            compose.onNodeWithText("local-large").performClick()
            compose.waitUntil(10_000) { runBlocking { service.current.first().model } == "local-large" }
            assertEquals("local-large", runBlocking { service.config() }?.model)
            assertTrue(runBlocking { service.models(AgentMode.CHATGPT).first() }.isEmpty())
        }
    }

    @Test
    fun theOfficialOpenAiListKeepsOnlyConversationModelsNewestFirst() {
        val json = JSONObject(
            "{\"data\":[" +
                "{\"id\":\"gpt-old\",\"created\":10}," +
                "{\"id\":\"text-embedding-3-large\",\"created\":50}," +
                "{\"id\":\"gpt-new\",\"created\":40}," +
                "{\"id\":\"gpt-new-realtime\",\"created\":45}," +
                "{\"id\":\"o4-mini\",\"created\":30}," +
                "{\"id\":\"whisper-1\",\"created\":20}," +
                "{\"id\":\"gpt-image-2\",\"created\":35}]}",
        )
        assertEquals(listOf("gpt-new", "o4-mini", "gpt-old"), ModelLists.compatibleOf(json, official = true).map { it.slug })
        assertEquals(7, ModelLists.compatibleOf(json, official = false).size)
        assertEquals(listOf("gpt-new-sol", "gpt-new-astra"), ModelLists.compatibleOf(JSONObject(catalog), official = true).map { it.slug })
    }
}
