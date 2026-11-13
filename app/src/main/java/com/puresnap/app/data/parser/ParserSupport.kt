package com.puresnap.app.data.parser

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.puresnap.app.data.local.ParseHistoryEntity
import com.puresnap.app.model.ParsedPost
import com.puresnap.app.model.PlatformType
import com.puresnap.app.util.DynamicMediaRenderer
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern

internal data class HttpPageResponse(
    val finalUrl: String,
    val html: String,
    val statusCode: Int
)

/**
 * 解析引擎共享底层工具：HTTP 追踪请求、安全 JSON 扩展读取、HTML Meta/JSON 平衡括号提取、URL 标准化。
 * 全纯 JVM 标准库 + Gson 实现，支持在单元测试中零依赖执行。
 */
internal object ParserSupport {

    const val MOBILE_UA =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1"

    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

    fun followRedirectsAndFetch(
        startUrl: String,
        platform: PlatformType,
        customCookie: String? = null,
        userAgent: String = MOBILE_UA,
        extraHeaders: Map<String, String> = emptyMap(),
        timeoutMs: Int = 6500
    ): HttpPageResponse? {
        var currentUrl = startUrl
        var redirects = 0
        while (redirects < 6) {
            var conn: HttpURLConnection? = null
            try {
                val url = URL(currentUrl)
                conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    instanceFollowRedirects = false
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", userAgent)
                    setRequestProperty(
                        "Accept",
                        "text/html,application/xhtml+xml,application/json,application/xml;q=0.9,*/*;q=0.8"
                    )
                    setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7")
                    setRequestProperty("Referer", DynamicMediaRenderer.refererForPlatform(platform, currentUrl))
                    if (!customCookie.isNullOrBlank()) {
                        setRequestProperty("Cookie", customCookie)
                    }
                    extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
                }

                val code = conn.responseCode
                if (code in listOf(301, 302, 303, 307, 308)) {
                    val loc = conn.getHeaderField("Location") ?: break
                    currentUrl = URL(url, loc).toString()
                    redirects++
                    continue
                }

                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.let { s ->
                    BufferedReader(InputStreamReader(s, Charsets.UTF_8)).use { it.readText() }
                } ?: ""

                return HttpPageResponse(
                    finalUrl = currentUrl,
                    html = body,
                    statusCode = code
                )
            } catch (_: Exception) {
                return null
            } finally {
                conn?.disconnect()
            }
        }
        return null
    }

    fun httpPostJson(
        targetUrl: String,
        jsonBody: String,
        platform: PlatformType,
        customCookie: String? = null,
        userAgent: String = MOBILE_UA,
        extraHeaders: Map<String, String> = emptyMap(),
        timeoutMs: Int = 6500
    ): HttpPageResponse? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Accept", "application/json, text/plain, */*")
                setRequestProperty("Referer", DynamicMediaRenderer.refererForPlatform(platform, targetUrl))
                if (!customCookie.isNullOrBlank()) {
                    setRequestProperty("Cookie", customCookie)
                }
                extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(jsonBody)
                writer.flush()
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.let { s ->
                BufferedReader(InputStreamReader(s, Charsets.UTF_8)).use { it.readText() }
            } ?: ""
            HttpPageResponse(finalUrl = targetUrl, html = body, statusCode = code)
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    fun normalizeMediaUrl(raw: String): String {
        var s = raw.trim()
            .replace("\\u002F", "/")
            .replace("\\/", "/")
            .replace("&amp;", "&")
        if (s.startsWith("//")) {
            s = "https:$s"
        } else if (s.startsWith("http://")) {
            s = "https://" + s.removePrefix("http://")
        }
        return s
    }

    fun detectImageFormat(url: String, defaultFormat: String = "JPG"): String {
        val cleanPath = url.substringBefore('?').lowercase()
        return when {
            cleanPath.endsWith(".png") || url.contains("format=png", ignoreCase = true) || url.contains("format/png", ignoreCase = true) -> "PNG"
            cleanPath.endsWith(".webp") || url.contains("format=webp", ignoreCase = true) -> "WEBP"
            cleanPath.endsWith(".heic") || cleanPath.endsWith(".heif") -> "HEIC"
            cleanPath.endsWith(".gif") || url.contains("format=gif", ignoreCase = true) -> "GIF"
            cleanPath.endsWith(".jpg") || cleanPath.endsWith(".jpeg") || url.contains("format=jpg", ignoreCase = true) -> "JPG"
            else -> defaultFormat
        }
    }

    fun extractBalancedJsonObject(html: String, marker: String): String? {
        val markerIdx = html.indexOf(marker)
        if (markerIdx == -1) return null

        val startBraceIdx = html.indexOf('{', markerIdx + marker.length)
        if (startBraceIdx == -1) return null

        var depth = 0
        var inString = false
        var escaped = false

        for (i in startBraceIdx until html.length) {
            val ch = html[i]
            if (escaped) {
                escaped = false
                continue
            }
            if (ch == '\\') {
                if (inString) escaped = true
                continue
            }
            if (ch == '"') {
                inString = !inString
                continue
            }
            if (!inString) {
                if (ch == '{') {
                    depth++
                } else if (ch == '}') {
                    depth--
                    if (depth == 0) {
                        return html.substring(startBraceIdx, i + 1)
                    }
                }
            }
        }
        return null
    }

    fun extractMetaTags(html: String, propertyName: String): List<String> {
        val results = mutableListOf<String>()
        val quoted = Pattern.quote(propertyName)
        val p1 = Pattern.compile(
            """<meta[^>]+(?:property|name)=["']$quoted["'][^>]+content=["']([^"']+)["']""",
            Pattern.CASE_INSENSITIVE
        ).matcher(html)
        while (p1.find()) {
            p1.group(1)?.trim()?.takeIf { it.isNotBlank() }?.let { results.add(decodeHtmlEntities(it)) }
        }

        val p2 = Pattern.compile(
            """<meta[^>]+content=["']([^"']+)["'][^>]+(?:property|name)=["']$quoted["']""",
            Pattern.CASE_INSENSITIVE
        ).matcher(html)
        while (p2.find()) {
            p2.group(1)?.trim()?.takeIf { it.isNotBlank() }?.let { results.add(decodeHtmlEntities(it)) }
        }
        return results.distinct()
    }

    fun extractHtmlTitle(html: String): String {
        val m = Pattern.compile("<title>(.*?)</title>", Pattern.DOTALL or Pattern.CASE_INSENSITIVE).matcher(html)
        return if (m.find()) decodeHtmlEntities(m.group(1)?.trim() ?: "") else ""
    }

    fun decodeHtmlEntities(input: String): String {
        return input
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
    }

    fun stripHtmlTags(input: String): String {
        return decodeHtmlEntities(
            input.replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
                .replace(Regex("""<[^>]+>"""), "")
        ).trim()
    }

    fun extractHashtagsFromText(text: String): List<String> {
        val tags = linkedSetOf<String>()
        val matcher = Pattern.compile("""#([^\s#，。！？、“”‘’【】（）<>]{1,28})""").matcher(text)
        while (matcher.find()) {
            matcher.group(1)?.let { tags.add("#$it") }
        }
        return tags.toList()
    }

    fun applyDuplicateInfo(post: ParsedPost, previousHistory: ParseHistoryEntity?): ParsedPost {
        return if (previousHistory != null && previousHistory.downloadedOnce) {
            post.copy(
                isDuplicateDownload = true,
                previousDownloadTimeMillis = previousHistory.parsedAtMillis
            )
        } else {
            post
        }
    }
}

internal fun JsonObject.optStr(key: String, default: String = ""): String {
    val el = get(key) ?: return default
    return if (el.isJsonPrimitive) el.asString else default
}

internal fun JsonObject.optInt(key: String, default: Int = 0): Int {
    val el = get(key) ?: return default
    return if (el.isJsonPrimitive) runCatching { el.asInt }.getOrDefault(default) else default
}

internal fun JsonObject.optLong(key: String, default: Long = 0L): Long {
    val el = get(key) ?: return default
    return if (el.isJsonPrimitive) runCatching { el.asLong }.getOrDefault(default) else default
}

internal fun JsonObject.optBool(key: String, default: Boolean = false): Boolean {
    val el = get(key) ?: return default
    return if (el.isJsonPrimitive) runCatching { el.asBoolean }.getOrDefault(default) else default
}

internal fun JsonObject.optObj(key: String): JsonObject? {
    val el = get(key) ?: return null
    return if (el.isJsonObject) el.asJsonObject else null
}

internal fun JsonObject.optArr(key: String): JsonArray? {
    val el = get(key) ?: return null
    return if (el.isJsonArray) el.asJsonArray else null
}
