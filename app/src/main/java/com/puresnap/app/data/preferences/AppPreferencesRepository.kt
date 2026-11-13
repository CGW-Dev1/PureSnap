package com.puresnap.app.data.preferences

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.puresnap.app.model.ExportFormatMode
import com.puresnap.app.model.LivePhotoExportMode
import com.puresnap.app.model.PlatformType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class UserPreferencesState(
    val disclaimerAccepted: Boolean = false,
    val smartClipboardEnabled: Boolean = true,
    val exportFormatMode: ExportFormatMode = ExportFormatMode.KEEP_ORIGINAL,
    val livePhotoExportMode: LivePhotoExportMode = LivePhotoExportMode.MOTION_PHOTO_JPEG,
    val modifyMd5Enabled: Boolean = false,
    val duplicateReminderEnabled: Boolean = true,
    val dynamicColorEnabled: Boolean = false,
    val darkThemeMode: Int = 0, // 0: 跟随系统, 1: 浅色, 2: 深色
    val cloudApiEndpoint: String = "",
    val xhsCookie: String = "",
    val douyinCookie: String = "",
    val zuiyouCookie: String = "",
    val extraCookies: Map<String, String> = emptyMap(),
    val dynamicRuleVersion: String = "2026.11.13-v1.3.1",
    val xhsCdnHostRule: String = "https://ci.xiaohongshu.com/"
) {
    fun cookieForPlatform(platform: PlatformType): String? {
        return when (platform) {
            PlatformType.XIAOHONGSHU -> xhsCookie.takeIf { it.isNotBlank() }
            PlatformType.DOUYIN -> douyinCookie.takeIf { it.isNotBlank() }
            PlatformType.ZUIYOU -> zuiyouCookie.takeIf { it.isNotBlank() }
            else -> extraCookies[platform.name]?.takeIf { it.isNotBlank() }
        }
    }
}

class AppPreferencesRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("puresnap_prefs", Context.MODE_PRIVATE)

    private val gson = Gson()

    private val _state = MutableStateFlow(loadState())
    val state: StateFlow<UserPreferencesState> = _state.asStateFlow()

    private fun loadState(): UserPreferencesState {
        val formatName = prefs.getString("export_format_mode_v2", ExportFormatMode.KEEP_ORIGINAL.name)
            ?: ExportFormatMode.KEEP_ORIGINAL.name
        val liveModeName = prefs.getString("live_photo_export_mode", LivePhotoExportMode.MOTION_PHOTO_JPEG.name)
            ?: LivePhotoExportMode.MOTION_PHOTO_JPEG.name
        val extraCookiesJson = prefs.getString("extra_platform_cookies", "") ?: ""
        val extraCookies: Map<String, String> = if (extraCookiesJson.isNotBlank()) {
            runCatching {
                val type = object : TypeToken<Map<String, String>>() {}.type
                gson.fromJson<Map<String, String>>(extraCookiesJson, type) ?: emptyMap()
            }.getOrDefault(emptyMap())
        } else {
            emptyMap()
        }

        return UserPreferencesState(
            disclaimerAccepted = prefs.getBoolean("disclaimer_accepted", false),
            smartClipboardEnabled = prefs.getBoolean("smart_clipboard_enabled", true),
            exportFormatMode = runCatching { ExportFormatMode.valueOf(formatName) }
                .getOrDefault(ExportFormatMode.KEEP_ORIGINAL),
            livePhotoExportMode = runCatching { LivePhotoExportMode.valueOf(liveModeName) }
                .getOrDefault(LivePhotoExportMode.MOTION_PHOTO_JPEG),
            modifyMd5Enabled = prefs.getBoolean("modify_md5_enabled", false),
            duplicateReminderEnabled = prefs.getBoolean("duplicate_reminder_enabled", true),
            dynamicColorEnabled = prefs.getBoolean("dynamic_color_v2", false),
            darkThemeMode = prefs.getInt("dark_theme_mode", 0),
            cloudApiEndpoint = prefs.getString("cloud_api_endpoint", "") ?: "",
            xhsCookie = prefs.getString("xhs_cookie", "") ?: "",
            douyinCookie = prefs.getString("douyin_cookie", "") ?: "",
            zuiyouCookie = prefs.getString("zuiyou_cookie", "") ?: "",
            extraCookies = extraCookies,
            dynamicRuleVersion = prefs.getString("dynamic_rule_version", "2026.11.13-v1.3") ?: "2026.11.13-v1.3",
            xhsCdnHostRule = prefs.getString("xhs_cdn_host_rule", "https://ci.xiaohongshu.com/")
                ?: "https://ci.xiaohongshu.com/"
        )
    }

    fun acceptDisclaimer() {
        prefs.edit().putBoolean("disclaimer_accepted", true).apply()
        _state.value = _state.value.copy(disclaimerAccepted = true)
    }

    fun setSmartClipboardEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("smart_clipboard_enabled", enabled).apply()
        _state.value = _state.value.copy(smartClipboardEnabled = enabled)
    }

    fun setExportFormatMode(mode: ExportFormatMode) {
        prefs.edit().putString("export_format_mode_v2", mode.name).apply()
        _state.value = _state.value.copy(exportFormatMode = mode)
    }

    fun setLivePhotoExportMode(mode: LivePhotoExportMode) {
        prefs.edit().putString("live_photo_export_mode", mode.name).apply()
        _state.value = _state.value.copy(livePhotoExportMode = mode)
    }

    fun setModifyMd5Enabled(enabled: Boolean) {
        prefs.edit().putBoolean("modify_md5_enabled", enabled).apply()
        _state.value = _state.value.copy(modifyMd5Enabled = enabled)
    }

    fun setDuplicateReminderEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("duplicate_reminder_enabled", enabled).apply()
        _state.value = _state.value.copy(duplicateReminderEnabled = enabled)
    }

    fun setDynamicColorEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("dynamic_color_v2", enabled).apply()
        _state.value = _state.value.copy(dynamicColorEnabled = enabled)
    }

    fun setDarkThemeMode(mode: Int) {
        prefs.edit().putInt("dark_theme_mode", mode).apply()
        _state.value = _state.value.copy(darkThemeMode = mode)
    }

    fun setPlatformCookie(platform: PlatformType, cookie: String) {
        val clean = cookie.trim()
        val editor = prefs.edit()
        when (platform) {
            PlatformType.XIAOHONGSHU -> editor.putString("xhs_cookie", clean)
            PlatformType.DOUYIN -> editor.putString("douyin_cookie", clean)
            PlatformType.ZUIYOU -> editor.putString("zuiyou_cookie", clean)
            else -> {
                val nextMap = _state.value.extraCookies.toMutableMap()
                if (clean.isBlank()) nextMap.remove(platform.name) else nextMap[platform.name] = clean
                editor.putString("extra_platform_cookies", gson.toJson(nextMap))
            }
        }
        editor.apply()
        _state.value = loadState()
    }

    fun updateAdvancedConfig(
        cloudApiEndpoint: String,
        xhsCookie: String,
        douyinCookie: String,
        zuiyouCookie: String,
        xhsCdnHostRule: String,
        extraCookies: Map<String, String> = _state.value.extraCookies
    ) {
        val cleanedExtra = extraCookies
            .mapValues { it.value.trim() }
            .filterValues { it.isNotBlank() }

        prefs.edit()
            .putString("cloud_api_endpoint", cloudApiEndpoint.trim())
            .putString("xhs_cookie", xhsCookie.trim())
            .putString("douyin_cookie", douyinCookie.trim())
            .putString("zuiyou_cookie", zuiyouCookie.trim())
            .putString("extra_platform_cookies", gson.toJson(cleanedExtra))
            .putString("xhs_cdn_host_rule", xhsCdnHostRule.trim().ifBlank { "https://ci.xiaohongshu.com/" })
            .apply()
        _state.value = loadState()
    }
}
