package app.dustbird.mail.ui.message

import android.webkit.WebSettings
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.dustbird.mail.ui.theme.DustbirdTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class SafeHtmlViewTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val themed = PageColors(background = 0xFFFFFFFF.toInt(), text = 0xFF000000.toInt(), link = 0xFF0B57D0.toInt())
    private val links = mutableListOf<LinkTarget>()

    private fun show(body: String, imagesAllowed: Boolean) {
        val page = SafeHtml.page(body, imagesAllowed, themed)
        compose.setContent {
            DustbirdTheme { SafeHtmlView(page, imagesAllowed, { links += it }, Modifier.fillMaxSize()) }
        }
    }

    @Test
    fun rawHostileMarkupStaysInertInTheHardenedView() {
        show(HOSTILE_HTML, imagesAllowed = false)
        val web = compose.awaitWebView()
        compose.waitUntil(10_000) { compose.runOnUiThread { web.title == "safe" || web.title == "pwned" } }
        assertEquals("safe", compose.runOnUiThread { web.title })
        compose.runOnUiThread {
            val settings = web.settings
            assertFalse(settings.javaScriptEnabled)
            assertFalse(settings.allowFileAccess)
            assertFalse(settings.allowContentAccess)
            assertFalse(settings.domStorageEnabled)
            assertFalse(settings.supportMultipleWindows())
            assertFalse(settings.javaScriptCanOpenWindowsAutomatically)
            assertTrue(settings.safeBrowsingEnabled)
            assertTrue(settings.blockNetworkLoads)
            assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW, settings.mixedContentMode)
            assertEquals(WebSettings.LOAD_NO_CACHE, settings.cacheMode)
            assertEquals((compose.activity.resources.configuration.fontScale * 100).roundToInt(), settings.textZoom)
        }
    }

    @Test
    fun linkTapIsCancelledAndReportedWithTheFullTarget() {
        show("<a href=\"https://target.example/path?a=1&b=2\">visible text</a>", imagesAllowed = false)
        val web = compose.awaitWebView()
        val urlBefore = compose.runOnUiThread { web.url }
        val target = "https://target.example/path?a=1&b=2"
        val consumed = compose.runOnUiThread { web.webViewClient.shouldOverrideUrlLoading(web, FakeRequest(target)) }
        assertTrue(consumed)
        compose.waitUntil(5_000) { links.isNotEmpty() }
        assertEquals(target, links.first().url)
        assertEquals(urlBefore, compose.runOnUiThread { web.url })
    }

    @Test
    fun everyRequestIsBlockedWhileImagesAreNotAllowed() {
        show(HOSTILE_HTML, imagesAllowed = false)
        val web = compose.awaitWebView()
        val client = web.webViewClient
        listOf("https://tracker.example/pixel.gif", "http://tracker.example/pixel.gif", "data:image/png;base64,AAAA", "file:///etc/passwd", "ftp://example.com/x").forEach {
            assertEquals(403, client.shouldInterceptRequest(web, FakeRequest(it))?.statusCode, it)
        }
        assertEquals(403, client.shouldInterceptRequest(web, FakeRequest("https://example.com/", mainFrame = true))?.statusCode)
        assertNull(client.shouldInterceptRequest(web, FakeRequest("data:text/html;charset=utf-8,x", mainFrame = true)))
    }

    @Test
    fun onlyHttpsSubResourcesPassWhenImagesAreAllowed() {
        show(HOSTILE_HTML, imagesAllowed = true)
        val web = compose.awaitWebView()
        val client = web.webViewClient
        assertFalse(compose.runOnUiThread { web.settings.blockNetworkLoads })
        assertNull(client.shouldInterceptRequest(web, FakeRequest("https://cdn.example/a.png")))
        listOf("http://cdn.example/a.png", "data:image/png;base64,AAAA", "javascript:alert(1)", "ftp://cdn.example/a.png").forEach {
            assertEquals(403, client.shouldInterceptRequest(web, FakeRequest(it))?.statusCode, it)
        }
    }

    @Test
    fun theBundledFontIsServedOnlyFromTheInternalAddress() {
        show("<p>text</p>", imagesAllowed = false)
        val web = compose.awaitWebView()
        val response = web.webViewClient.shouldInterceptRequest(web, FakeRequest(ResourcePolicy.FONT_URL))
        assertNotNull(response)
        assertEquals(200, response.statusCode)
        assertEquals("font/ttf", response.mimeType)
        assertEquals("*", response.responseHeaders["Access-Control-Allow-Origin"])
        assertTrue(response.data.use { it.read() } >= 0)
        assertEquals(403, web.webViewClient.shouldInterceptRequest(web, FakeRequest(ResourcePolicy.FONT_URL + "?x=1"))?.statusCode)
    }
}
