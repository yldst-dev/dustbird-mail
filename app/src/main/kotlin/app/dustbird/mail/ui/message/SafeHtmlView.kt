package app.dustbird.mail.ui.message

import android.content.res.Resources
import android.os.Handler
import android.os.Looper
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.viewinterop.AndroidView
import app.dustbird.mail.R
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.math.roundToInt

data class LinkTarget(val url: String, val shownText: String? = null)

internal class SafeWebViewClient(
    private val imagesAllowed: Boolean,
    private val openFont: () -> InputStream?,
    private val onLink: (LinkTarget) -> Unit,
) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url.toString()
        onLink(LinkTarget(url))
        val reply = Handler(Looper.getMainLooper()) { message ->
            val data = message.data
            if (data.getString("url") == url) onLink(LinkTarget(url, data.getString("title")?.trim()?.take(200)?.ifEmpty { null }))
            true
        }
        view.requestFocusNodeHref(reply.obtainMessage())
        return true
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
        when (ResourcePolicy.verdict(request.url.toString(), request.isForMainFrame, imagesAllowed)) {
            RequestVerdict.PASS -> null
            RequestVerdict.FONT -> font()
            RequestVerdict.BLOCK -> blocked()
        }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean = true

    private fun font(): WebResourceResponse {
        val stream = try { openFont() } catch (_: Resources.NotFoundException) { null } ?: return blocked()
        return WebResourceResponse("font/ttf", null, 200, "OK", mapOf("Access-Control-Allow-Origin" to "*"), stream)
    }

    private fun blocked() = WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
}

@Suppress("DEPRECATION")
internal fun WebView.harden(imagesAllowed: Boolean, textZoom: Int) {
    with(settings) {
        javaScriptEnabled = false
        allowFileAccess = false
        allowContentAccess = false
        allowFileAccessFromFileURLs = false
        allowUniversalAccessFromFileURLs = false
        setGeolocationEnabled(false)
        domStorageEnabled = false
        setSupportMultipleWindows(false)
        javaScriptCanOpenWindowsAutomatically = false
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        safeBrowsingEnabled = true
        cacheMode = WebSettings.LOAD_NO_CACHE
        blockNetworkLoads = !imagesAllowed
        builtInZoomControls = true
        displayZoomControls = false
        defaultTextEncodingName = "UTF-8"
        this.textZoom = textZoom
    }
}

@Composable
fun SafeHtmlView(page: HtmlPage, imagesAllowed: Boolean, onLink: (LinkTarget) -> Unit, modifier: Modifier = Modifier) {
    val textZoom = (LocalConfiguration.current.fontScale * 100).roundToInt()
    val latestOnLink by rememberUpdatedState(onLink)
    key(page, imagesAllowed, textZoom) {
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    harden(imagesAllowed, textZoom)
                    setBackgroundColor(page.background)
                    webViewClient = SafeWebViewClient(
                        imagesAllowed = imagesAllowed,
                        openFont = { context.resources.openRawResource(R.font.pretendard_variable) },
                        onLink = { latestOnLink(it) },
                    )
                    loadDataWithBaseURL(null, page.html, "text/html", "UTF-8", null)
                }
            },
            modifier = modifier,
            onRelease = { it.destroy() },
        )
    }
}
