package com.puresnap.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.HomeWork
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.puresnap.app.ui.PureSnapViewModel
import com.puresnap.app.ui.components.ComplianceDisclaimerDialog
import com.puresnap.app.ui.components.WebViewAssistDialog
import com.puresnap.app.ui.screens.GalleryScreen
import com.puresnap.app.ui.screens.HistoryBottomSheet
import com.puresnap.app.ui.screens.SettingsBottomSheet
import com.puresnap.app.ui.screens.WorkbenchScreen
import com.puresnap.app.ui.theme.PureSnapTheme

class MainActivity : ComponentActivity() {

    private val viewModel: PureSnapViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIncomingIntent(intent)

        setContent {
            val preferences by viewModel.preferences.collectAsState()
            val inputText by viewModel.inputText.collectAsState()
            val isParsing by viewModel.isParsing.collectAsState()
            val currentPost by viewModel.currentPost.collectAsState()
            val parseError by viewModel.parseError.collectAsState()
            val downloadState by viewModel.downloadState.collectAsState()
            val bgmSaving by viewModel.bgmSaving.collectAsState()
            val smartBanner by viewModel.smartBanner.collectAsState()
            val historyList by viewModel.historyList.collectAsState()
            val downloadedMedia by viewModel.downloadedMediaList.collectAsState()
            val webViewAssistUrl by viewModel.webViewAssistUrl.collectAsState()

            val context = LocalContext.current
            val lifecycleOwner = LocalLifecycleOwner.current

            // 切回前台时自动感知剪贴板中的小红书/抖音/最右口令
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        window.decorView.postDelayed({
                            viewModel.inspectClipboardOnForeground(context)
                        }, 260)
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                }
            }

            PureSnapTheme(
                darkThemeMode = preferences.darkThemeMode,
                dynamicColor = preferences.dynamicColorEnabled
            ) {
                var selectedTab by rememberSaveable { mutableIntStateOf(0) } // 0: 工作台, 1: 媒体库
                var showHistorySheet by remember { mutableStateOf(false) }
                var showSettingsSheet by remember { mutableStateOf(false) }

                Surface(
                    color = MaterialTheme.colorScheme.background,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                androidx.compose.ui.graphics.Brush.verticalGradient(
                                    colors = listOf(
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.055f),
                                        MaterialTheme.colorScheme.background,
                                        MaterialTheme.colorScheme.background
                                    )
                                )
                            )
                            .padding(WindowInsets.statusBars.asPaddingValues())
                    ) {
                        when (selectedTab) {
                            0 -> WorkbenchScreen(
                                inputText = inputText,
                                isParsing = isParsing,
                                currentPost = currentPost,
                                parseError = parseError,
                                downloadState = downloadState,
                                bgmSaving = bgmSaving,
                                smartBanner = smartBanner,
                                preferences = preferences,
                                historyCount = historyList.size,
                                onInputTextChange = viewModel::updateInputText,
                                onClearInput = viewModel::clearInput,
                                onPasteAndParse = { viewModel.pasteAndParse(context) },
                                onAcceptSmartBanner = viewModel::acceptSmartPasteBanner,
                                onDismissSmartBanner = viewModel::dismissSmartPasteBanner,
                                onToggleSelectItem = viewModel::toggleItemSelection,
                                onDragSelectItem = viewModel::setItemSelection,
                                onToggleSelectAll = viewModel::toggleSelectAllOrInvert,
                                onInvertSelection = viewModel::invertSelection,
                                onDownloadSelected = {
                                    viewModel.downloadSelectedMedia(context)
                                },
                                onExportSingleLivePhoto = { item, mode ->
                                    viewModel.exportSingleLivePhotoItem(context, item, mode)
                                },
                                onSaveBgm = { viewModel.saveBgmAudio(context) },
                                onSaveCompanionImage = { isAvatar ->
                                    viewModel.saveCompanionImage(context, isAvatar)
                                },
                                onCopyText = { label, text ->
                                    viewModel.copyTextToClipboard(context, label, text)
                                },
                                onOpenHistory = { showHistorySheet = true },
                                onOpenSettings = { showSettingsSheet = true },
                                onOpenWebViewAssist = { viewModel.openWebViewAssist() }
                            )

                            1 -> GalleryScreen(
                                downloadedMedia = downloadedMedia,
                                onDeleteSelectedIds = viewModel::deleteDownloadedMediaBatch,
                                onJumpToWorkbench = { selectedTab = 0 }
                            )
                        }

                        // 底部毛玻璃悬浮双栏胶囊导航栏：(  [ 工作台 ]    [ 媒体库 ]  )
                        FloatingCapsuleBottomBar(
                            selectedTab = selectedTab,
                            galleryCount = downloadedMedia.size,
                            onSelectTab = { selectedTab = it },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(WindowInsets.navigationBars.asPaddingValues())
                                .padding(bottom = 16.dp)
                        )
                    }

                    // 首次启动免责协议弹窗（第六章合规机制）
                    if (!preferences.disclaimerAccepted) {
                        ComplianceDisclaimerDialog(
                            onAccept = { viewModel.prefsRepo.acceptDisclaimer() },
                            onExitApp = { finish() }
                        )
                    }

                    // 本地解析历史库抽屉
                    if (showHistorySheet) {
                        HistoryBottomSheet(
                            historyList = historyList,
                            onDismiss = { showHistorySheet = false },
                            onRestoreRecord = { record ->
                                viewModel.restoreFromHistory(record)
                                selectedTab = 0
                            },
                            onDeleteRecord = viewModel::deleteHistoryItem,
                            onClearAll = viewModel::clearAllHistory
                        )
                    }

                    // 设置抽屉
                    if (showSettingsSheet) {
                        SettingsBottomSheet(
                            preferences = preferences,
                            prefsRepo = viewModel.prefsRepo,
                            onDismiss = { showSettingsSheet = false },
                            onOpenWebViewAssist = { viewModel.openWebViewAssist() }
                        )
                    }

                    // 内置 WebView 辅助验证弹窗
                    webViewAssistUrl?.let { targetUrl ->
                        WebViewAssistDialog(
                            targetUrl = targetUrl,
                            onDismiss = viewModel::closeWebViewAssist,
                            onHtmlExtracted = viewModel::onWebViewHtmlExtracted
                        )
                    }
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            viewModel.inspectClipboardOnForeground(this)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val dataStr = intent?.dataString
        if (!dataStr.isNullOrBlank()) {
            viewModel.triggerParse(dataStr)
        }
    }
}

/**
 * 极简双栏胶囊悬浮底部栏（Floating Bottom Bar）
 * (       [ 工作台 ]    [ 媒体库 ]       )
 */
@Composable
private fun FloatingCapsuleBottomBar(
    selectedTab: Int,
    galleryCount: Int,
    onSelectTab: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        tonalElevation = 6.dp,
        modifier = modifier
            .shadow(elevation = 8.dp, shape = CircleShape)
            .border(
                width = 0.8.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f),
                shape = CircleShape
            )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp)
        ) {
            CapsuleNavTabItem(
                selected = selectedTab == 0,
                icon = Icons.Default.HomeWork,
                title = "工作台",
                badgeCount = 0,
                onClick = { onSelectTab(0) }
            )
            CapsuleNavTabItem(
                selected = selectedTab == 1,
                icon = Icons.Default.Collections,
                title = "媒体库",
                badgeCount = galleryCount,
                onClick = { onSelectTab(1) }
            )
        }
    }
}

@Composable
private fun CapsuleNavTabItem(
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    badgeCount: Int,
    onClick: () -> Unit
) {
    val bgColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        label = "tab_bg"
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "tab_content"
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier
            .clip(CircleShape)
            .background(bgColor)
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 9.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            tint = contentColor,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = title,
            color = contentColor,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            fontSize = 13.5.sp,
            maxLines = 1,
            softWrap = false
        )
        if (badgeCount > 0) {
            Spacer(modifier = Modifier.width(6.dp))
            Badge(
                containerColor = if (selected) {
                    MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.22f)
                } else {
                    MaterialTheme.colorScheme.primaryContainer
                },
                contentColor = if (selected) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onPrimaryContainer
                }
            ) {
                Text(
                    text = badgeCount.toString(),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(horizontal = 3.dp)
                )
            }
        }
    }
}
