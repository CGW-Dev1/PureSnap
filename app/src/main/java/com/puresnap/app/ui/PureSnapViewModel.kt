package com.puresnap.app.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.puresnap.app.PureSnapApplication
import com.puresnap.app.data.local.DownloadedMediaEntity
import com.puresnap.app.data.local.ParseHistoryEntity
import com.puresnap.app.data.parser.MultiPlatformParserEngine
import com.puresnap.app.data.preferences.UserPreferencesState
import com.puresnap.app.model.LivePhotoExportMode
import com.puresnap.app.model.MediaItem
import com.puresnap.app.model.ParseErrorType
import com.puresnap.app.model.ParsedPost
import com.puresnap.app.model.PlatformType
import com.puresnap.app.util.MediaStoreArchiver
import com.puresnap.app.util.ShareLinkExtractor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 下载按钮变形状态机（Morphing Button State）
 */
sealed class MorphDownloadState {
    data object Idle : MorphDownloadState()
    data class Downloading(val completed: Int, val total: Int) : MorphDownloadState()
    data class Completed(val count: Int, val folderPath: String) : MorphDownloadState()
}

/**
 * 剪贴板灵动提示条数据（Smart Paste Banner）
 */
data class SmartPasteBannerState(
    val visible: Boolean = false,
    val rawClipboardText: String = "",
    val extractedUrl: String = "",
    val platform: PlatformType = PlatformType.XIAOHONGSHU
)

class PureSnapViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as PureSnapApplication
    private val dao = app.database.pureSnapDao()
    val prefsRepo = app.preferencesRepository

    val preferences: StateFlow<UserPreferencesState> = prefsRepo.state

    val historyList: StateFlow<List<ParseHistoryEntity>> = dao.observeAllHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val downloadedMediaList: StateFlow<List<DownloadedMediaEntity>> = dao.observeAllDownloadedMedia()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private val _isParsing = MutableStateFlow(false)
    val isParsing: StateFlow<Boolean> = _isParsing.asStateFlow()

    private val _currentPost = MutableStateFlow<ParsedPost?>(null)
    val currentPost: StateFlow<ParsedPost?> = _currentPost.asStateFlow()

    private val _parseError = MutableStateFlow(ParseErrorType.NONE)
    val parseError: StateFlow<ParseErrorType> = _parseError.asStateFlow()

    private val _downloadState = MutableStateFlow<MorphDownloadState>(MorphDownloadState.Idle)
    val downloadState: StateFlow<MorphDownloadState> = _downloadState.asStateFlow()

    private val _bgmSaving = MutableStateFlow(false)
    val bgmSaving: StateFlow<Boolean> = _bgmSaving.asStateFlow()

    private val _smartBanner = MutableStateFlow(SmartPasteBannerState())
    val smartBanner: StateFlow<SmartPasteBannerState> = _smartBanner.asStateFlow()

    private val dismissedClipboardHashes = mutableSetOf<Int>()

    private val _webViewAssistUrl = MutableStateFlow<String?>(null)
    val webViewAssistUrl: StateFlow<String?> = _webViewAssistUrl.asStateFlow()

    fun updateInputText(newText: String) {
        _inputText.value = newText
        if (_parseError.value != ParseErrorType.NONE) {
            _parseError.value = ParseErrorType.NONE
        }
    }

    fun clearInput() {
        _inputText.value = ""
        _parseError.value = ParseErrorType.NONE
    }

    /**
     * 剪贴板智能感知（App 切回前台时自动检测有效平台链接并弹出灵动胶囊提示条）
     */
    fun inspectClipboardOnForeground(context: Context): Boolean {
        if (!preferences.value.smartClipboardEnabled) return false
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clipText = clipboard?.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.coerceToText(context)
                ?.toString()
                ?.trim()

            if (clipText.isNullOrBlank()) return false
            val hash = clipText.hashCode()
            if (dismissedClipboardHashes.contains(hash)) return false
            if (clipText == _inputText.value.trim()) return false

            val result = ShareLinkExtractor.extract(clipText)
            if (result.cleanUrl != null && result.platform != PlatformType.UNKNOWN) {
                _smartBanner.value = SmartPasteBannerState(
                    visible = true,
                    rawClipboardText = clipText,
                    extractedUrl = result.cleanUrl,
                    platform = result.platform
                )
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    fun acceptSmartPasteBanner() {
        val banner = _smartBanner.value
        if (!banner.visible) return
        dismissedClipboardHashes.add(banner.rawClipboardText.hashCode())
        _smartBanner.value = SmartPasteBannerState(visible = false)
        _inputText.value = banner.rawClipboardText
        triggerParse(banner.rawClipboardText)
    }

    fun dismissSmartPasteBanner() {
        val banner = _smartBanner.value
        if (banner.rawClipboardText.isNotBlank()) {
            dismissedClipboardHashes.add(banner.rawClipboardText.hashCode())
        }
        _smartBanner.value = SmartPasteBannerState(visible = false)
    }

    /**
     * 一键粘贴剪贴板（若输入框为空自动读取剪贴板；若输入框已有内容但剪贴板有新链接则优先粘贴新链接）并触发解析
     */
    fun pasteAndParse(context: Context) {
        var targetText = _inputText.value.trim()
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clipText = clipboard?.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.coerceToText(context)
                ?.toString()
                ?.trim()

            if (targetText.isBlank() && !clipText.isNullOrBlank()) {
                targetText = clipText
                _inputText.value = clipText
            } else if (!clipText.isNullOrBlank() && ShareLinkExtractor.isValidClipboardShareText(clipText) && targetText.isBlank()) {
                targetText = clipText
                _inputText.value = clipText
            }
            if (!clipText.isNullOrBlank()) {
                dismissedClipboardHashes.add(clipText.hashCode())
                _smartBanner.value = SmartPasteBannerState(visible = false)
            }
        } catch (_: Exception) {
        }
        triggerParse(targetText)
    }

    /**
     * 执行多平台无水印解析
     */
    fun triggerParse(
        rawShareText: String = _inputText.value,
        saveToHistory: Boolean = true
    ) {
        if (rawShareText.isBlank()) {
            _parseError.value = ParseErrorType.NO_VALID_LINK
            return
        }
        viewModelScope.launch {
            _inputText.value = rawShareText
            _parseError.value = ParseErrorType.NONE
            _downloadState.value = MorphDownloadState.Idle
            _isParsing.value = true

            val extraction = ShareLinkExtractor.extract(rawShareText)
            val prevHistory = extraction.cleanUrl?.let { dao.findLatestHistoryByUrl(it) }

            when (val outcome = MultiPlatformParserEngine.parseShareInput(
                context = app,
                rawInput = rawShareText,
                preferences = preferences.value,
                previousHistory = prevHistory
            )) {
                is MultiPlatformParserEngine.ParseOutcome.Success -> {
                    _currentPost.value = outcome.post
                    _isParsing.value = false
                    if (saveToHistory) {
                        dao.insertHistory(
                            MultiPlatformParserEngine.toHistoryEntity(
                                post = outcome.post,
                                downloadedOnce = prevHistory?.downloadedOnce == true
                            )
                        )
                    }
                }
                is MultiPlatformParserEngine.ParseOutcome.Failure -> {
                    _isParsing.value = false
                    _parseError.value = outcome.errorType
                }
            }
        }
    }

    fun openWebViewAssist(url: String? = null) {
        val target = url
            ?: ShareLinkExtractor.extract(_inputText.value).cleanUrl
            ?: _currentPost.value?.extractedUrl
            ?: "https://www.xiaohongshu.com"
        _webViewAssistUrl.value = target
    }

    fun closeWebViewAssist() {
        _webViewAssistUrl.value = null
    }

    fun onWebViewHtmlExtracted(url: String, html: String) {
        viewModelScope.launch {
            _webViewAssistUrl.value = null
            _isParsing.value = true
            val parsed = MultiPlatformParserEngine.parseFromWebViewHtml(
                url = url,
                html = html,
                preferences = preferences.value
            )
            _isParsing.value = false
            if (parsed != null && parsed.mediaItems.isNotEmpty()) {
                _currentPost.value = parsed
                _parseError.value = ParseErrorType.NONE
                dao.insertHistory(MultiPlatformParserEngine.toHistoryEntity(parsed))
            } else {
                _parseError.value = ParseErrorType.ANTI_SCRAPE_TIMEOUT
            }
        }
    }

    fun toggleItemSelection(index: Int) {
        val post = _currentPost.value ?: return
        val updated = post.mediaItems.map { item ->
            if (item.index == index) item.copy(selected = !item.selected) else item
        }
        _currentPost.value = post.copy(mediaItems = updated)
        resetDownloadButtonIfCompleted()
    }

    fun setItemSelection(index: Int, selected: Boolean) {
        val post = _currentPost.value ?: return
        val current = post.mediaItems.getOrNull(index) ?: return
        if (current.selected == selected) return
        val updated = post.mediaItems.map { item ->
            if (item.index == index) item.copy(selected = selected) else item
        }
        _currentPost.value = post.copy(mediaItems = updated)
        resetDownloadButtonIfCompleted()
    }

    fun toggleSelectAllOrInvert() {
        val post = _currentPost.value ?: return
        val updated = if (post.allSelected) {
            post.mediaItems.map { it.copy(selected = false) }
        } else {
            post.mediaItems.map { it.copy(selected = true) }
        }
        _currentPost.value = post.copy(mediaItems = updated)
        resetDownloadButtonIfCompleted()
    }

    fun invertSelection() {
        val post = _currentPost.value ?: return
        val updated = post.mediaItems.map { it.copy(selected = !it.selected) }
        _currentPost.value = post.copy(mediaItems = updated)
        resetDownloadButtonIfCompleted()
    }

    private fun resetDownloadButtonIfCompleted() {
        if (_downloadState.value is MorphDownloadState.Completed) {
            _downloadState.value = MorphDownloadState.Idle
        }
    }

    fun downloadSelectedMedia(
        context: Context,
        onCompleteCallback: ((savedCount: Int, folderPath: String) -> Unit)? = null
    ) {
        val post = _currentPost.value ?: return
        val selected = post.selectedItems
        if (selected.isEmpty()) {
            Toast.makeText(context, "请至少勾选 1 项要保存的原图或视频", Toast.LENGTH_SHORT).show()
            return
        }
        if (_downloadState.value is MorphDownloadState.Downloading) return

        viewModelScope.launch {
            val prefs = preferences.value
            _downloadState.value = MorphDownloadState.Downloading(0, selected.size)

            val result = MediaStoreArchiver.saveSelectedMediaBatch(
                context = context,
                post = post,
                itemsToSave = selected,
                exportFormatMode = prefs.exportFormatMode,
                livePhotoExportMode = prefs.livePhotoExportMode,
                modifyMd5 = prefs.modifyMd5Enabled,
                customCookie = prefs.cookieForPlatform(post.platform),
                onProgress = { done, total ->
                    _downloadState.value = MorphDownloadState.Downloading(done, total)
                }
            )

            if (result.savedEntities.isNotEmpty()) {
                dao.insertDownloadedMedia(result.savedEntities)
                dao.markHistoryDownloaded(post.extractedUrl)
                _currentPost.value = post.copy(
                    isDuplicateDownload = true,
                    previousDownloadTimeMillis = System.currentTimeMillis()
                )
                val totalSaved = result.savedEntities.size
                _downloadState.value = MorphDownloadState.Completed(
                    count = totalSaved,
                    folderPath = result.folderPathSummary
                )
                onCompleteCallback?.invoke(totalSaved, result.folderPathSummary)
            } else {
                _downloadState.value = MorphDownloadState.Idle
                Toast.makeText(context, "原片下载失败，请检查网络或尝试 WebView 辅助提取", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun exportSingleLivePhotoItem(
        context: Context,
        item: MediaItem,
        mode: LivePhotoExportMode
    ) {
        val post = _currentPost.value ?: return
        viewModelScope.launch {
            val prefs = preferences.value
            val result = MediaStoreArchiver.saveSelectedMediaBatch(
                context = context,
                post = post,
                itemsToSave = listOf(item),
                exportFormatMode = prefs.exportFormatMode,
                livePhotoExportMode = mode,
                modifyMd5 = prefs.modifyMd5Enabled,
                customCookie = prefs.cookieForPlatform(post.platform),
                onProgress = { _, _ -> }
            )
            if (result.savedEntities.isNotEmpty()) {
                dao.insertDownloadedMedia(result.savedEntities)
                val desc = when (mode) {
                    LivePhotoExportMode.MOTION_PHOTO_JPEG -> "已保存原生实况照片至系统相册（长按播放动效）"
                    LivePhotoExportMode.STATIC_IMAGE_ONLY -> "已保存静态高清原图至相册"
                    LivePhotoExportMode.LIVE_VIDEO_ONLY -> "已导出实况动态视频 (MP4) 至相册"
                    LivePhotoExportMode.BOTH_IMAGE_AND_MP4 -> "已分别导出静态原图与动态 MP4 至相册"
                }
                Toast.makeText(context, desc, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "导出失败，请稍后重试", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun saveBgmAudio(context: Context) {
        val post = _currentPost.value ?: return
        val bgm = post.bgmAudio ?: return
        if (_bgmSaving.value) return

        viewModelScope.launch {
            _bgmSaving.value = true
            val entity = MediaStoreArchiver.saveBgmAudioToMediaStore(
                context = context,
                post = post,
                bgm = bgm,
                modifyMd5 = preferences.value.modifyMd5Enabled
            )
            _bgmSaving.value = false
            if (entity != null) {
                dao.insertSingleDownloadedMedia(entity)
                Toast.makeText(
                    context,
                    "🎵 已保存原声音乐至 Music/PureSnap/${post.platform.folderName}",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(context, "该作品暂无可独立下载的音频流", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun saveCompanionImage(context: Context, isAvatar: Boolean) {
        val post = _currentPost.value ?: return
        viewModelScope.launch {
            val label = if (isAvatar) "作者高清头像" else "无水印封面图"
            val url = if (isAvatar) post.authorAvatarUrl else post.coverUrl
            if (url.isBlank()) {
                Toast.makeText(context, "未获取到$label", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val seed = if (isAvatar) post.authorName.hashCode() else post.noteId.hashCode()

            val entity = MediaStoreArchiver.saveCompanionImageToMediaStore(
                context = context,
                post = post,
                label = label,
                imageUrl = url,
                visualSeed = seed,
                exportFormatMode = preferences.value.exportFormatMode,
                modifyMd5 = preferences.value.modifyMd5Enabled
            )
            if (entity != null) {
                dao.insertSingleDownloadedMedia(entity)
                Toast.makeText(context, "已保存${label}至系统相册", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun copyTextToClipboard(context: Context, label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText(label, text))
        dismissedClipboardHashes.add(text.trim().hashCode())
        Toast.makeText(context, "已复制$label", Toast.LENGTH_SHORT).show()
    }

    fun restoreFromHistory(entity: ParseHistoryEntity) {
        val restored = MultiPlatformParserEngine.fromHistoryEntity(entity)
        _inputText.value = entity.sourceShareText
        _currentPost.value = restored
        _parseError.value = ParseErrorType.NONE
        _downloadState.value = MorphDownloadState.Idle
    }

    fun deleteHistoryItem(id: Long) {
        viewModelScope.launch {
            dao.deleteHistoryById(id)
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            dao.clearAllHistory()
        }
    }

    fun deleteDownloadedMediaBatch(ids: List<Long>) {
        viewModelScope.launch {
            dao.deleteDownloadedMediaByIds(ids)
        }
    }
}
