package com.puresnap.app.data.preferences

import android.content.Context
import android.content.SharedPreferences
import com.puresnap.app.model.ExportFormatMode
import com.puresnap.app.model.LivePhotoExportMode
import com.puresnap.app.model.PlatformType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class UserPreferencesState(
    val disclaimerAccepted: Boolean = false,
    val smartClipboardEnabled: Boolean = true,
    val exportFormatMode: ExportFormatMode = ExportFormatMode.COMPATIBLE_JPEG_100,
    val livePhotoExportMode: LivePhotoExportMode = LivePhotoExportMode.BOTH_IMAGE_AND_MP4,
    val modifyMd5Enabled: Boolean = false,
    val duplicateReminderEnabled: Boolean = true,
    val dynamicColorEnabled: Boolean = true,
    val darkThemeMode: Int = 0, // 0: 跟随系统, 1: 浅色, 2: 深色
    val cloudApiEndpoint: String = "",
    val xhsCookie: String = "",
    val douyinCookie: String = "",
    val zuiyouCookie: String = "",
    val dynamicRuleVersion: String = "2026.10.25-r3",
    val xhsCdnHostRule: String = "https://ci.xiaohongshu.com/"
) {
    fun cookieForPlatform(platform: PlatformType): String? {
        return when (platform) {
            PlatformType.XIAOHONGSHU -> xhsCookie.takeIf { it.isNotBlank() }
            PlatformType.DOUYIN -> douyinCookie.takeIf { it.isNotBlank() }
            PlatformType.ZUIYOU -> zuiyouCookie.takeIf { it.isNotBlank() }
            else -> null
        }
    }
}

class AppPreferencesRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("puresnap_prefs", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(loadState())
    val state: StateFlow<UserPreferencesState> = _state.asStateFlow()

    private fun loadState(): UserPreferencesState {
        val formatName = prefs.getString("export_format_mode", ExportFormatMode.COMPATIBLE_JPEG_100.name)
            ?: ExportFormatMode.COMPATIBLE_JPEG_100.name
        val liveModeName = prefs.getString("live_photo_export_mode", LivePhotoExportMode.BOTH_IMAGE_AND_MP4.name)
            ?: LivePhotoExportMode.BOTH_IMAGE_AND_MP4.name

        return UserPreferencesState(
            disclaimerAccepted = prefs.getBoolean("disclaimer_accepted", false),
            smartClipboardEnabled = prefs.getBoolean("smart_clipboard_enabled", true),
            exportFormatMode = runCatching { ExportFormatMode.valueOf(formatName) }
                .getOrDefault(ExportFormatMode.COMPATIBLE_JPEG_100),
            livePhotoExportMode = runCatching { LivePhotoExportMode.valueOf(liveModeName) }
                .getOrDefault(LivePhotoExportMode.BOTH_IMAGE_AND_MP4),
            modifyMd5Enabled = prefs.getBoolean("modify_md5_enabled", false),
            duplicateReminderEnabled = prefs.getBoolean("duplicate_reminder_enabled", true),
            dynamicColorEnabled = prefs.getBoolean("dynamic_color_enabled", true),
            darkThemeMode = prefs.getInt("dark_theme_mode", 0),
            cloudApiEndpoint = prefs.getString("cloud_api_endpoint", "") ?: "",
            xhsCookie = prefs.getString("xhs_cookie", "") ?: "",
            douyinCookie = prefs.getString("douyin_cookie", "") ?: "",
            zuiyouCookie = prefs.getString("zuiyou_cookie", "") ?: "",
            dynamicRuleVersion = prefs.getString("dynamic_rule_version", "2026.10.25-r3") ?: "2026.10.25-r3",
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
        prefs.edit().putString("export_format_mode", mode.name).apply()
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
        prefs.edit().putBoolean("dynamic_color_enabled", enabled).apply()
        _state.value = _state.value.copy(dynamicColorEnabled = enabled)
    }

    fun setDarkThemeMode(mode: Int) {
        prefs.edit().putInt("dark_theme_mode", mode).apply()
        _state.value = _state.value.copy(darkThemeMode = mode)
    }

    fun updateAdvancedConfig(
        cloudApiEndpoint: String,
        xhsCookie: String,
        douyinCookie: String,
        zuiyouCookie: String,
        xhsCdnHostRule: String
    ) {
        prefs.edit()
            .putString("cloud_api_endpoint", cloudApiEndpoint.trim())
            .putString("xhs_cookie", xhsCookie.trim())
            .putString("douyin_cookie", douyinCookie.trim())
            .putString("zuiyou_cookie", zuiyouCookie.trim())
            .putString("xhs_cdn_host_rule", xhsCdnHostRule.trim().ifBlank { "https://ci.xiaohongshu.com/" })
            .apply()
        _state.value = loadState()
    }
}
