package com.puresnap.app.data.parser

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections
import kotlin.coroutines.resume

/**
 * 后台无感 Chromium WebView 动态 DOM 与状态树提取器
 * 针对抖音 (v.douyin.com)、小红书 (xhslink.com)、最右、快手等平台的 WAF/JS 挑战页面，
 * 在后台真实 WebView 中完成重定向与 JS 挑战后，精准提取 _ROUTER_DATA、__INITIAL_STATE__、OpenGraph 与视频流。
 */
object HiddenWebViewExtractor {

    data class WebSniffResult(
        val finalUrl: String,
        val title: String,
        val ogTitle: String,
        val ogDesc: String,
        val ogImages: List<String>,
        val ogVideo: String,
        val domVideos: List<String>,
        val domImages: List<String>,
        val routerDataJson: String?,
        val initialStateJson: String?,
        val renderDataEncoded: String?,
        val stateScriptText: String,
        val rawHtml: String,
        val sniffedMediaUrls: List<String>
    )

    private const val MOBILE_UA =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1"

    private val EXTRACT_JS = """
        (function() {
            try {
                var ogImgs = Array.from(document.querySelectorAll('meta[property="og:image"],meta[name="og:image"]'))
                    .map(function(m){ return m.getAttribute('content') || ''; })
                    .filter(Boolean);
                var ogVid = '';
                var ogVidEl = document.querySelector('meta[property="og:video"],meta[name="og:video"],meta[property="og:video:url"]');
                if (ogVidEl) ogVid = ogVidEl.getAttribute('content') || '';

                var vids = Array.from(document.querySelectorAll('video, video source'))
                    .map(function(v){ return v.src || v.currentSrc || v.getAttribute('src') || ''; })
                    .filter(function(s){ return s && s.indexOf('blob:') !== 0; });

                var imgs = Array.from(document.querySelectorAll('img'))
                    .map(function(img){
                        var w = img.naturalWidth || img.width || 0;
                        var h = img.naturalHeight || img.height || 0;
                        var src = img.currentSrc || img.src || img.getAttribute('data-src') || '';
                        var lower = src.toLowerCase();
                        var isAvatarOrIcon = lower.indexOf('avatar') !== -1 || lower.indexOf('icon') !== -1 || lower.indexOf('logo') !== -1 || (w > 0 && w < 140 && h > 0 && h < 140);
                        if (!isAvatarOrIcon && src && src.indexOf('http') === 0) return src;
                        return '';
                    })
                    .filter(Boolean);

                var routerStr = null;
                if (window._ROUTER_DATA) {
                    try { routerStr = JSON.stringify(window._ROUTER_DATA); } catch(e) {}
                } else if (window._SSR_DATA) {
                    try { routerStr = JSON.stringify(window._SSR_DATA); } catch(e) {}
                }

                var initStr = null;
                if (window.__INITIAL_STATE__) {
                    try {
                        var st = window.__INITIAL_STATE__;
                        var target = (st.noteData && st.noteData.data) ? st.noteData : (st.note ? st.note : st);
                        initStr = JSON.stringify(target);
                    } catch(e) {}
                } else if (window.INIT_STATE) {
                    try {
                        initStr = JSON.stringify(window.INIT_STATE);
                    } catch(e) {}
                }

                var scriptDump = '';
                var scripts = document.querySelectorAll('script');
                for (var i = 0; i < scripts.length; i++) {
                    var txt = scripts[i].textContent || '';
                    if (txt.indexOf('_ROUTER_DATA') !== -1 || txt.indexOf('__INITIAL_STATE__') !== -1 || txt.indexOf('INIT_STATE') !== -1 || txt.indexOf('imageList') !== -1 || txt.indexOf('item_list') !== -1 || txt.indexOf('coverUrls') !== -1) {
                        scriptDump += txt + '\n';
                    }
                }

                var renderEl = document.getElementById('RENDER_DATA');
                var renderStr = renderEl ? (renderEl.textContent || renderEl.innerText || null) : null;

                var ogTitleEl = document.querySelector('meta[property="og:title"],meta[name="og:title"]');
                var ogDescEl = document.querySelector('meta[property="og:description"],meta[name="description"]');

                return JSON.stringify({
                    url: location.href || '',
                    title: document.title || '',
                    ogTitle: ogTitleEl ? (ogTitleEl.getAttribute('content') || '') : '',
                    ogDesc: ogDescEl ? (ogDescEl.getAttribute('content') || '') : '',
                    ogImages: ogImgs,
                    ogVideo: ogVid,
                    videos: vids,
                    imgs: imgs,
                    routerData: routerStr,
                    initialState: initStr,
                    renderData: renderStr,
                    stateScript: scriptDump.slice(0, 350000),
                    html: document.documentElement ? document.documentElement.outerHTML.slice(0, 200000) : ''
                });
            } catch (err) {
                return JSON.stringify({ error: err.toString(), url: location.href || '' });
            }
        })();
    """.trimIndent()

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun sniffUrl(
        context: Context,
        targetUrl: String,
        customCookie: String? = null,
        timeoutMs: Long = 9000L
    ): WebSniffResult? = withTimeoutOrNull(timeoutMs) {
        withContext(Dispatchers.Main) {
            var webView: WebView? = null
            try {
                suspendCancellableCoroutine { cont ->
                    val sniffedUrls = Collections.synchronizedSet(linkedSetOf<String>())
                    var completed = false

                    val wv = WebView(context.applicationContext)
                    webView = wv

                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
                    if (!customCookie.isNullOrBlank()) {
                        CookieManager.getInstance().setCookie(targetUrl, customCookie)
                    }

                    wv.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        loadsImagesAutomatically = true
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        userAgentString = MOBILE_UA
                    }

                    fun finishWithResult(res: WebSniffResult?) {
                        if (!completed) {
                            completed = true
                            try {
                                wv.stopLoading()
                                wv.destroy()
                            } catch (_: Exception) {
                            }
                            if (cont.isActive) {
                                cont.resume(res)
                            }
                        }
                    }

                    fun probeDom(attempt: Int) {
                        if (completed) return
                        wv.evaluateJavascript(EXTRACT_JS) { rawJsonLiteral ->
                            if (completed) return@evaluateJavascript
                            val parsed = parseEvaluatedJson(rawJsonLiteral, sniffedUrls.toList())
                            val hasValidMediaState = parsed != null && (
                                (parsed.routerDataJson?.contains("item_list") == true) ||
                                    (parsed.initialStateJson?.contains("imageList") == true) ||
                                    (parsed.initialStateJson?.contains("photo") == true) ||
                                    (parsed.stateScriptText.contains("item_list") || parsed.stateScriptText.contains("imageList") || parsed.stateScriptText.contains("photo") || parsed.stateScriptText.contains("coverUrls")) ||
                                    parsed.ogImages.isNotEmpty() ||
                                    parsed.ogVideo.isNotBlank() ||
                                    parsed.domVideos.isNotEmpty() ||
                                    sniffedUrls.any { it.contains("aweme/v1/play") || it.contains("douyinvod.com") || it.endsWith(".mp4") }
                                )

                            if (hasValidMediaState || attempt >= 11) {
                                finishWithResult(parsed)
                            } else {
                                wv.postDelayed({ probeDom(attempt + 1) }, 550L)
                            }
                        }
                    }

                    wv.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val reqUrl = request?.url?.toString() ?: return false
                            return !(reqUrl.startsWith("http://") || reqUrl.startsWith("https://"))
                        }

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val u = request?.url?.toString() ?: return null
                            val lower = u.lowercase()
                            if (
                                lower.contains("aweme/v1/play") ||
                                lower.contains("douyinvod.com") ||
                                lower.contains("sns-webpic") ||
                                lower.contains("sns-video") ||
                                lower.contains("ci.xiaohongshu.com") ||
                                lower.contains("douyinpic.com") ||
                                lower.contains("izuiyou.com/img") ||
                                lower.contains("kwaicdn.com") ||
                                lower.contains("yximgs.com") ||
                                lower.contains("kwimgs.com") ||
                                lower.contains("chenzhongtech.com") ||
                                lower.endsWith(".mp4")
                            ) {
                                if (!lower.contains("avatar") && !lower.contains("icon") && !lower.contains(".js") && !lower.contains(".css")) {
                                    sniffedUrls.add(u)
                                }
                            }
                            return super.shouldInterceptRequest(view, request)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            view?.postDelayed({ probeDom(1) }, 500L)
                        }
                    }

                    cont.invokeOnCancellation {
                        try {
                            wv.stopLoading()
                            wv.destroy()
                        } catch (_: Exception) {
                        }
                    }

                    wv.loadUrl(targetUrl)
                }
            } catch (_: Exception) {
                try {
                    webView?.destroy()
                } catch (_: Exception) {
                }
                null
            }
        }
    }

    private fun parseEvaluatedJson(
        rawJsResult: String?,
        sniffedUrls: List<String>
    ): WebSniffResult? {
        if (rawJsResult.isNullOrBlank() || rawJsResult == "null") return null
        return try {
            val element = JsonParser.parseString(rawJsResult)
            val unwrappedJson = if (element.isJsonPrimitive && element.asJsonPrimitive.isString) {
                element.asString
            } else {
                rawJsResult
            }
            val obj = JsonParser.parseString(unwrappedJson).asJsonObject
            val ogImages = obj.optStringList("ogImages")
            val domVideos = obj.optStringList("videos")
            val domImages = obj.optStringList("imgs")

            WebSniffResult(
                finalUrl = obj.optStr("url"),
                title = obj.optStr("title"),
                ogTitle = obj.optStr("ogTitle"),
                ogDesc = obj.optStr("ogDesc"),
                ogImages = ogImages,
                ogVideo = obj.optStr("ogVideo"),
                domVideos = domVideos,
                domImages = domImages,
                routerDataJson = obj.optStr("routerData").takeIf { it.isNotBlank() && it != "null" },
                initialStateJson = obj.optStr("initialState").takeIf { it.isNotBlank() && it != "null" },
                renderDataEncoded = obj.optStr("renderData").takeIf { it.isNotBlank() && it != "null" },
                stateScriptText = obj.optStr("stateScript"),
                rawHtml = obj.optStr("html"),
                sniffedMediaUrls = sniffedUrls
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun JsonObject.optStr(key: String, default: String = ""): String {
        val el = get(key) ?: return default
        return if (el.isJsonPrimitive) el.asString else default
    }

    private fun JsonObject.optStringList(key: String): List<String> {
        val el = get(key) ?: return emptyList()
        if (!el.isJsonArray) return emptyList()
        return el.asJsonArray.mapNotNull { item ->
            if (item.isJsonPrimitive) item.asString.takeIf { it.isNotBlank() } else null
        }
    }
}
