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
import java.io.ByteArrayInputStream
import java.util.Collections
import kotlin.coroutines.resume

/**
 * 后台无感 Chromium WebView 动态 DOM 与状态树提取器（优化提速版）
 * - 自动屏蔽字体、埋点、广告统计脚本，大幅缩短页面加载与挑战完成耗时
 * - 支持国内外主流平台状态树：_ROUTER_DATA、__INITIAL_STATE__、INIT_STATE、
 *   __UNIVERSAL_DATA_FOR_REHYDRATION__、SIGI_STATE、__PWS_DATA__、ytInitialPlayerResponse 等
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
        val apiResponseJson: String?,
        val stateScriptText: String,
        val rawHtml: String,
        val sniffedMediaUrls: List<String>
    )

    private const val MOBILE_UA =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1"

    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

    private val EMPTY_RESPONSE by lazy {
        WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
    }

    private val INJECT_HOOK_JS = """
        (function() {
            if (window.__ps_hooked) return;
            window.__ps_hooked = true;
            window.__ps_api_res = window.__ps_api_res || [];

            function saveIfApi(txt) {
                if (!txt || typeof txt !== 'string') return;
                if (
                    txt.indexOf('item_list') !== -1 ||
                    txt.indexOf('aweme_detail') !== -1 ||
                    txt.indexOf('imageList') !== -1 ||
                    txt.indexOf('edge_sidecar_to_children') !== -1 ||
                    txt.indexOf('video_versions') !== -1 ||
                    txt.indexOf('image_versions2') !== -1 ||
                    txt.indexOf('itemStruct') !== -1
                ) {
                    window.__ps_api_res.push(txt);
                }
            }

            if (window.fetch) {
                var _fetch = window.fetch;
                window.fetch = function() {
                    return _fetch.apply(this, arguments).then(function(resp) {
                        try {
                            var c = resp.clone();
                            c.text().then(saveIfApi);
                        } catch(e) {}
                        return resp;
                    });
                };
            }

            if (window.XMLHttpRequest) {
                var _send = XMLHttpRequest.prototype.send;
                XMLHttpRequest.prototype.send = function() {
                    this.addEventListener('load', function() {
                        try { saveIfApi(this.responseText); } catch(e) {}
                    });
                    return _send.apply(this, arguments);
                };
            }
        })();
    """.trimIndent()

    private val EXTRACT_JS = """
        (function() {
            try {
                var ogImgs = Array.from(document.querySelectorAll('meta[property="og:image"],meta[name="og:image"],meta[name="twitter:image"],meta[property="twitter:image"]'))
                    .map(function(m){ return m.getAttribute('content') || ''; })
                    .filter(Boolean);
                var ogVid = '';
                var ogVidEl = document.querySelector('meta[property="og:video"],meta[name="og:video"],meta[property="og:video:url"],meta[property="og:video:secure_url"]');
                if (ogVidEl) ogVid = ogVidEl.getAttribute('content') || '';

                var vids = Array.from(document.querySelectorAll('video, video source'))
                    .map(function(v){ return v.src || v.currentSrc || v.getAttribute('src') || ''; })
                    .filter(function(s){ return s && s.indexOf('blob:') !== 0; });

                var imgs = Array.from(document.querySelectorAll('img'))
                    .map(function(img){
                        var w = img.naturalWidth || img.width || 0;
                        var h = img.naturalHeight || img.height || 0;
                        var srcset = img.getAttribute('srcset') || '';
                        var bestSrcset = '';
                        if (srcset) {
                            var parts = srcset.split(',').map(function(p){ return p.trim().split(/\s+/)[0]; }).filter(Boolean);
                            if (parts.length > 0) bestSrcset = parts[parts.length - 1];
                        }
                        var src = bestSrcset || img.currentSrc || img.src || img.getAttribute('data-src') || '';
                        var lower = src.toLowerCase();
                        var isAvatarOrIcon = lower.indexOf('avatar') !== -1 || lower.indexOf('icon') !== -1 || lower.indexOf('logo') !== -1 || lower.indexOf('emoji') !== -1 || (w > 0 && w < 140 && h > 0 && h < 140);
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
                    try { initStr = JSON.stringify(window.INIT_STATE); } catch(e) {}
                } else if (window.__UNIVERSAL_DATA_FOR_REHYDRATION__) {
                    try { initStr = JSON.stringify(window.__UNIVERSAL_DATA_FOR_REHYDRATION__); } catch(e) {}
                } else if (window.SIGI_STATE) {
                    try { initStr = JSON.stringify(window.SIGI_STATE); } catch(e) {}
                } else if (window.ytInitialPlayerResponse) {
                    try { initStr = JSON.stringify(window.ytInitialPlayerResponse); } catch(e) {}
                }

                var apiStr = null;
                if (window.__ps_api_res && window.__ps_api_res.length > 0) {
                    apiStr = window.__ps_api_res[window.__ps_api_res.length - 1];
                }

                var scriptDump = '';
                var scripts = document.querySelectorAll('script');
                for (var i = 0; i < scripts.length; i++) {
                    var txt = scripts[i].textContent || '';
                    if (
                        txt.indexOf('_ROUTER_DATA') !== -1 ||
                        txt.indexOf('__INITIAL_STATE__') !== -1 ||
                        txt.indexOf('INIT_STATE') !== -1 ||
                        txt.indexOf('__UNIVERSAL_DATA_FOR_REHYDRATION__') !== -1 ||
                        txt.indexOf('SIGI_STATE') !== -1 ||
                        txt.indexOf('__PWS_DATA__') !== -1 ||
                        txt.indexOf('ytInitialPlayerResponse') !== -1 ||
                        txt.indexOf('imageList') !== -1 ||
                        txt.indexOf('imagesList') !== -1 ||
                        txt.indexOf('item_list') !== -1 ||
                        txt.indexOf('image_post_info') !== -1 ||
                        txt.indexOf('livePhoto') !== -1 ||
                        txt.indexOf('live_photo') !== -1 ||
                        txt.indexOf('coverUrls') !== -1 ||
                        txt.indexOf('video_versions') !== -1 ||
                        txt.indexOf('image_versions2') !== -1 ||
                        txt.indexOf('edge_sidecar_to_children') !== -1 ||
                        txt.indexOf('urlList') !== -1 ||
                        txt.indexOf('awemeId') !== -1
                    ) {
                        scriptDump += txt + '\n';
                    }
                }

                var renderEl = document.getElementById('RENDER_DATA');
                var renderStr = renderEl ? (renderEl.textContent || renderEl.innerText || null) : null;

                var ogTitleEl = document.querySelector('meta[property="og:title"],meta[name="og:title"],meta[name="twitter:title"]');
                var ogDescEl = document.querySelector('meta[property="og:description"],meta[name="description"],meta[name="twitter:description"]');

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
                    apiResponse: apiStr,
                    stateScript: scriptDump.slice(0, 420000),
                    html: document.documentElement ? document.documentElement.outerHTML.slice(0, 240000) : ''
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
        timeoutMs: Long = 9000L,
        useDesktopUa: Boolean = false
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
                        userAgentString = if (useDesktopUa) DESKTOP_UA else MOBILE_UA
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
                                    (parsed.routerDataJson?.contains("image_post_info") == true) ||
                                    (parsed.apiResponseJson?.contains("item_list") == true) ||
                                    (parsed.apiResponseJson?.contains("aweme_detail") == true) ||
                                    (parsed.apiResponseJson?.contains("video_versions") == true) ||
                                    (parsed.initialStateJson?.contains("imageList") == true) ||
                                    (parsed.initialStateJson?.contains("imagesList") == true) ||
                                    (parsed.initialStateJson?.contains("livePhoto") == true) ||
                                    (parsed.initialStateJson?.contains("itemStruct") == true) ||
                                    (parsed.initialStateJson?.contains("photo") == true) ||
                                    parsed.stateScriptText.contains("item_list") ||
                                    parsed.stateScriptText.contains("image_post_info") ||
                                    parsed.stateScriptText.contains("imageList") ||
                                    parsed.stateScriptText.contains("imagesList") ||
                                    parsed.stateScriptText.contains("livePhoto") ||
                                    parsed.stateScriptText.contains("coverUrls") ||
                                    parsed.stateScriptText.contains("video_versions") ||
                                    parsed.stateScriptText.contains("image_versions2") ||
                                    (parsed.stateScriptText.contains("urlList") && parsed.stateScriptText.contains("awemeId")) ||
                                    (!parsed.renderDataEncoded.isNullOrBlank() && parsed.renderDataEncoded.contains("aweme")) ||
                                    parsed.ogImages.isNotEmpty() ||
                                    parsed.ogVideo.isNotBlank() ||
                                    parsed.domVideos.isNotEmpty() ||
                                    sniffedUrls.any {
                                        it.contains("aweme/v1/play") || it.contains("douyinvod.com") ||
                                            it.contains("bytevcloud") || it.contains("sns-video") ||
                                            it.contains("video.twimg.com") || it.contains("tiktokcdn") ||
                                            it.endsWith(".mp4")
                                    }
                                )

                            if (hasValidMediaState || attempt >= 11) {
                                finishWithResult(parsed)
                            } else {
                                val nextDelay = when {
                                    attempt <= 2 -> 240L
                                    attempt <= 5 -> 380L
                                    else -> 550L
                                }
                                wv.postDelayed({ probeDom(attempt + 1) }, nextDelay)
                            }
                        }
                    }

                    wv.webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            view?.evaluateJavascript(INJECT_HOOK_JS, null)
                        }

                        override fun onLoadResource(view: WebView?, url: String?) {
                            super.onLoadResource(view, url)
                            view?.evaluateJavascript(INJECT_HOOK_JS, null)
                        }

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

                            // 屏蔽字体与统计埋点脚本，加速后台解析
                            if (lower.endsWith(".woff") || lower.endsWith(".woff2") || lower.endsWith(".ttf") ||
                                lower.endsWith(".otf") || lower.contains("google-analytics") ||
                                lower.contains("doubleclick") || lower.contains("googlesyndication") ||
                                lower.contains("sentry.io")
                            ) {
                                return EMPTY_RESPONSE
                            }

                            if (
                                lower.contains("aweme/v1/play") ||
                                lower.contains("douyinvod.com") ||
                                lower.contains("bytevcloud") ||
                                lower.contains("amemv.com") ||
                                lower.contains("volces.com") ||
                                (lower.contains("snssdk.com") && lower.contains("play")) ||
                                lower.contains("sns-webpic") ||
                                lower.contains("sns-video") ||
                                lower.contains("ci.xiaohongshu.com") ||
                                lower.contains("douyinpic.com") ||
                                lower.contains("izuiyou.com/img") ||
                                lower.contains("kwaicdn.com") ||
                                lower.contains("yximgs.com") ||
                                lower.contains("kwimgs.com") ||
                                lower.contains("chenzhongtech.com") ||
                                lower.contains("sinaimg.cn") ||
                                lower.contains("bilivideo.com") ||
                                lower.contains("cdninstagram.com") ||
                                lower.contains("fbcdn.net") ||
                                lower.contains("pbs.twimg.com") ||
                                lower.contains("video.twimg.com") ||
                                lower.contains("tiktokcdn") ||
                                lower.contains("tiktokv.com") ||
                                lower.contains("pinimg.com") ||
                                lower.contains("i.redd.it") ||
                                lower.contains("v.redd.it") ||
                                lower.endsWith(".mp4")
                            ) {
                                if (!lower.contains("avatar") && !lower.contains("icon") &&
                                    !lower.contains(".js") && !lower.contains(".css") &&
                                    !lower.contains(".mp3") && !lower.contains(".m4a")
                                ) {
                                    sniffedUrls.add(u)
                                }
                            }
                            return super.shouldInterceptRequest(view, request)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            view?.evaluateJavascript(INJECT_HOOK_JS, null)
                            view?.postDelayed({ probeDom(1) }, 220L)
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
                runCatching { webView?.destroy() }
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
            WebSniffResult(
                finalUrl = obj.optStr("url"),
                title = obj.optStr("title"),
                ogTitle = obj.optStr("ogTitle"),
                ogDesc = obj.optStr("ogDesc"),
                ogImages = obj.optStringList("ogImages"),
                ogVideo = obj.optStr("ogVideo"),
                domVideos = obj.optStringList("videos"),
                domImages = obj.optStringList("imgs"),
                routerDataJson = obj.optStr("routerData").takeIf { it.isNotBlank() && it != "null" },
                initialStateJson = obj.optStr("initialState").takeIf { it.isNotBlank() && it != "null" },
                renderDataEncoded = obj.optStr("renderData").takeIf { it.isNotBlank() && it != "null" },
                apiResponseJson = obj.optStr("apiResponse").takeIf { it.isNotBlank() && it != "null" },
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
