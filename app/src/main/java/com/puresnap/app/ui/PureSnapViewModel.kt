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
import com.puresnap.app.model.ExportFormatMode
import com.puresnap.app.model.LivePhotoExportMode
import com.puresnap.app.model.MediaItem
import com.puresnap.app.model.ParseErrorType
import com.puresnap.app.model.ParsedPost
import com.puresnap.app.model.PlatformType
import com.puresnap.app.util.MediaStoreArchiver
import com.puresnap.app.util.ShareLinkExtractor
import kotlinx.coroutines.Job
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
    data class Completed(
        val count: Int,
        val folderPath: String,
        val summaryLabel: String = ""
    ) : MorphDownloadState()
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

enum class BatchItemStatus {
    PENDING,
    PARSING,
    SUCCESS,
    FAILED
}

data class BatchQueueEntry(
    val url: String,
    val platform: PlatformType,
    val status: BatchItemStatus = BatchItemStatus.PENDING,
    val statusLabel: String = "待解析",
    val post: ParsedPost? = null
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

    private val _batchQueue = MutableStateFlow<List<BatchQueueEntry>>(emptyList())
    val batchQueue: StateFlow<List<BatchQueueEntry>> = _batchQueue.asStateFlow()

    private val _isBatchRunning = MutableStateFlow(false)
    val isBatchRunning: StateFlow<Boolean> = _isBatchRunning.asStateFlow()

    private val dismissedClipboardHashes = mutableSetOf<Int>()

    private val _webViewAssistUrl = MutableStateFlow<String?>(null)
    val webViewAssistUrl: StateFlow<String?> = _webViewAssistUrl.asStateFlow()

    private var parseJob: Job? = null
    private var batchJob: Job? = null

    fun updateInputText(newText: String) {
        _inputText.value = newText
        if (_parseError.value != ParseErrorType.NONE) {
            _parseError.value = ParseErrorType.NONE
        }
    }

    fun clearInput() {
        parseJob?.cancel()
        _isParsing.value = false
        _inputText.value = ""
        _parseError.value = ParseErrorType.NONE
    }

    /**
     * 剪贴板智能感知（App 切回前台时自动检测国内外有效链接或直链并弹出灵动胶囊提示条）
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
            val isSupported = result.cleanUrl != null && (
                result.platform != PlatformType.UNKNOWN ||
                    ShareLinkExtractor.isDirectMediaUrl(result.cleanUrl)
                )
            if (isSupported && result.cleanUrl != null) {
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
     * 仅粘贴剪贴板文本至输入框（用于手动粘贴与批量链接编辑）
     */
    fun pasteFromClipboard(context: Context): Boolean {
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clipText = clipboard?.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.coerceToText(context)
                ?.toString()
                ?.trim()

            if (!clipText.isNullOrBlank()) {
                _inputText.value = clipText
                _parseError.value = ParseErrorType.NONE
                dismissedClipboardHashes.add(clipText.hashCode())
                _smartBanner.value = SmartPasteBannerState(visible = false)
                true
            } else {
                Toast.makeText(context, "剪贴板为空，请先复制链接", Toast.LENGTH_SHORT).show()
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 若输入框为空则自动读取剪贴板并解析；若已有内容则立即解析当前内容
     */
    fun pasteAndParse(context: Context) {
        var targetText = _inputText.value.trim()
        if (targetText.isBlank()) {
            try {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val clipText = clipboard?.primaryClip
                    ?.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)
                    ?.coerceToText(context)
                    ?.toString()
                    ?.trim()

                if (!clipText.isNullOrBlank()) {
                    targetText = clipText
                    _inputText.value = clipText
                    dismissedClipboardHashes.add(clipText.hashCode())
                    _smartBanner.value = SmartPasteBannerState(visible = false)
                }
            } catch (_: Exception) {
            }
        }
        triggerParse(targetText)
    }

    /**
     * 执行多平台无水印解析（自动取消未完成的旧请求，避免结果竞态覆盖）
     */
    fun triggerParse(
        rawShareText: String = _inputText.value,
        saveToHistory: Boolean = true
    ) {
        if (rawShareText.isBlank()) {
            _parseError.value = ParseErrorType.NO_VALID_LINK
            return
        }
        parseJob?.cancel()
        parseJob = viewModelScope.launch {
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

    /**
     * 批量解析队列：当输入框中一次性粘贴了多条分享链接时，依次解析并自动归档至历史记录，支持逐条切换保存
     */
    fun startBatchParse(rawText: String = _inputText.value) {
        val allUrls = ShareLinkExtractor.extractAllUrls(rawText)
        if (allUrls.size <= 1) {
            triggerParse(rawText)
            return
        }

        batchJob?.cancel()
        _batchQueue.value = allUrls.map { item ->
            BatchQueueEntry(
                url = item.cleanUrl ?: "",
                platform = item.platform
            )
        }.filter { it.url.isNotBlank() }

        batchJob = viewModelScope.launch {
            _isBatchRunning.value = true
            val entries = _batchQueue.value.toMutableList()
            for (i in entries.indices) {
                val url = entries[i].url
                entries[i] = entries[i].copy(status = BatchItemStatus.PARSING, statusLabel = "解析中…")
                _batchQueue.value = entries.toList()

                val prevHistory = dao.findLatestHistoryByUrl(url)
                when (val outcome = MultiPlatformParserEngine.parseShareInput(
                    context = app,
                    rawInput = url,
                    preferences = preferences.value,
                    previousHistory = prevHistory
                )) {
                    is MultiPlatformParserEngine.ParseOutcome.Success -> {
                        val post = outcome.post
                        entries[i] = entries[i].copy(
                            status = BatchItemStatus.SUCCESS,
                            statusLabel = "成功 (${post.totalCount} 项)",
                            post = post
                        )
                        _currentPost.value = post
                        _parseError.value = ParseErrorType.NONE
                        _downloadState.value = MorphDownloadState.Idle
                        dao.insertHistory(
                            MultiPlatformParserEngine.toHistoryEntity(
                                post = post,
                                downloadedOnce = prevHistory?.downloadedOnce == true
                            )
                        )
                    }

                    is MultiPlatformParserEngine.ParseOutcome.Failure -> {
                        entries[i] = entries[i].copy(
                            status = BatchItemStatus.FAILED,
                            statusLabel = "提取受限"
                        )
                    }
                }
                _batchQueue.value = entries.toList()
            }
            _isBatchRunning.value = false
        }
    }

    fun selectBatchEntry(entry: BatchQueueEntry) {
        entry.post?.let { post ->
            _currentPost.value = post
            _parseError.value = ParseErrorType.NONE
            _downloadState.value = MorphDownloadState.Idle
        } ?: triggerParse(entry.url)
    }

    fun dismissBatchQueue() {
        batchJob?.cancel()
        _isBatchRunning.value = false
        _batchQueue.value = emptyList()
    }

    fun refreshCurrentPost() {
        val post = _currentPost.value ?: return
        val target = post.extractedUrl.ifBlank { post.sourceShareText }
        if (target.isNotBlank()) {
            triggerParse(target, saveToHistory = true)
        }
    }

    fun refreshHistoryRecord(entity: ParseHistoryEntity) {
        val target = entity.extractedUrl.ifBlank { entity.sourceShareText }
        _inputText.value = entity.sourceShareText
        triggerParse(target, saveToHistory = true)
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

    fun syncCookieFromWebView(context: Context, url: String, cookieStr: String) {
        if (cookieStr.isBlank()) {
            Toast.makeText(context, "当前网页暂无可同步的 Cookie，请先完成登录", Toast.LENGTH_SHORT).show()
            return
        }
        val platform = ShareLinkExtractor.detectPlatform(url, url)
        prefsRepo.setPlatformCookie(platform, cookieStr.trim())
        Toast.makeText(context, "已同步 ${platform.displayName} 登录凭据 (Cookie)", Toast.LENGTH_SHORT).show()
    }

    fun setLivePhotoExportMode(mode: LivePhotoExportMode) {
        prefsRepo.setLivePhotoExportMode(mode)
    }

    fun setExportFormatMode(mode: ExportFormatMode) {
        prefsRepo.setExportFormatMode(mode)
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
            Toast.makeText(context, "请至少勾选 1 项要保存的原图、实况或视频", Toast.LENGTH_SHORT).show()
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
                val summary = buildSavedSummary(result.imageCount, result.livePhotoCount, result.videoCount, totalSaved)
                _downloadState.value = MorphDownloadState.Completed(
                    count = totalSaved,
                    folderPath = result.folderPathSummary,
                    summaryLabel = summary
                )
                onCompleteCallback?.invoke(totalSaved, result.folderPathSummary)
            } else {
                _downloadState.value = MorphDownloadState.Idle
                Toast.makeText(context, "原片下载失败，请检查网络或尝试 WebView 辅助提取", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun downloadSingleMediaItem(context: Context, item: MediaItem) {
        val post = _currentPost.value ?: return
        viewModelScope.launch {
            Toast.makeText(context, "正在无损保存当前媒体…", Toast.LENGTH_SHORT).show()
            val prefs = preferences.value
            val result = MediaStoreArchiver.saveSelectedMediaBatch(
                context = context,
                post = post,
                itemsToSave = listOf(item),
                exportFormatMode = prefs.exportFormatMode,
                livePhotoExportMode = prefs.livePhotoExportMode,
                modifyMd5 = prefs.modifyMd5Enabled,
                customCookie = prefs.cookieForPlatform(post.platform),
                onProgress = { _, _ -> }
            )
            if (result.savedEntities.isNotEmpty()) {
                dao.insertDownloadedMedia(result.savedEntities)
                dao.markHistoryDownloaded(post.extractedUrl)
                Toast.makeText(
                    context,
                    "✓ 已保存至 ${result.folderPathSummary}",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(context, "保存失败，请检查网络或 Cookie 权限", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun buildSavedSummary(img: Int, live: Int, vid: Int, total: Int): String {
        val parts = mutableListOf<String>()
        if (live > 0) parts.add("$live 实况")
        if (img > 0) parts.add("$img 原图")
        if (vid > 0) parts.add("$vid 视频")
        return if (parts.isEmpty()) "已存 $total 项" else "已存 ${parts.joinToString(" + ")}"
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

    fun insertDownloadedEntity(entity: DownloadedMediaEntity) {
        viewModelScope.launch {
            dao.insertSingleDownloadedMedia(entity)
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
        val hasStaleWatermarkedDouyin = restored.platform == PlatformType.DOUYIN &&
            restored.mediaItems.any {
                !it.isVideo && MultiPlatformParserEngine.isDouyinWatermarkedImageUrl(it.originUrl)
            }
        _inputText.value = entity.sourceShareText
        if (hasStaleWatermarkedDouyin) {
            val target = entity.extractedUrl.ifBlank { entity.sourceShareText }
            if (target.isNotBlank()) {
                triggerParse(target, saveToHistory = true)
                return
            }
        }
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

    fun deleteDownloadedMediaBatch(ids: List<Long>, deletePhysicalUris: List<String> = emptyList()) {
        viewModelScope.launch {
            if (deletePhysicalUris.isNotEmpty()) {
                MediaStoreArchiver.deleteMediaStoreFiles(app, deletePhysicalUris)
            }
            dao.deleteDownloadedMediaByIds(ids)
        }
    }
}
