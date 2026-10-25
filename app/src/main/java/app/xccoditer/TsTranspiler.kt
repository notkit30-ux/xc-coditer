package app.xccoditer

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Transpiles TypeScript to JavaScript with the real TypeScript compiler (typescript.js, Apache-2.0)
 * running in a hidden, network-blocked WebView. Syntax only: it does not type-check.
 * The compiler file is fetched into assets at build time (see the CI workflow); if it is absent we say so.
 */
object TsTranspiler {
    private const val HOST = "appassets.androidplatform.net"
    private const val PAGE = "https://$HOST/assets/xc/ts-runner.html"
    private const val TIMEOUT_MS = 30_000L

    fun available(ctx: Context): Boolean =
        runCatching { ctx.assets.open("xc/typescript.js").use { true } }.getOrDefault(false)

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun transpile(ctx: Context, source: String, fileName: String): TsOutcome {
        if (!available(ctx)) {
            return TsOutcome.Failed("The TypeScript compiler is not bundled in this build (assets/xc/typescript.js is missing).")
        }
        val raw = withContext(Dispatchers.Main) {
            withTimeoutOrNull(TIMEOUT_MS) {
                suspendCancellableCoroutine<String?> { cont ->
                    val loader = WebViewAssetLoader.Builder()
                        .setDomain(HOST)
                        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(ctx))
                        .build()
                    val web = WebView(ctx)
                    web.settings.javaScriptEnabled = true
                    web.settings.allowFileAccess = false
                    web.settings.allowContentAccess = false
                    web.settings.blockNetworkLoads = true
                    web.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                            loader.shouldInterceptRequest(request.url)

                        override fun onPageFinished(view: WebView, url: String?) {
                            val js = "xcTranspile(" + MiniJson.quote(source) + "," + MiniJson.quote(fileName) + ")"
                            view.evaluateJavascript(js) { r ->
                                // evaluateJavascript returns the string as a JSON string literal; decode it once.
                                val decoded = runCatching { MiniJson.parse(r ?: "null") as? String }.getOrNull()
                                if (cont.isActive) cont.resume(decoded)
                                view.destroy()
                            }
                        }
                    }
                    cont.invokeOnCancellation { web.post { web.destroy() } }
                    web.loadUrl(PAGE)
                }
            }
        }
        return if (raw == null) TsOutcome.Failed("The TypeScript compiler did not respond (timed out or failed to load).")
        else TsResult.parse(raw)
    }
}
