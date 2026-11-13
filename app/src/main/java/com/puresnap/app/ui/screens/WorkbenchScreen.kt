package com.puresnap.app.ui.screens

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FilterCenterFocus
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MotionPhotosAuto
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import com.puresnap.app.BuildConfig
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.puresnap.app.data.local.ParseHistoryEntity
import com.puresnap.app.data.preferences.UserPreferencesState
import com.puresnap.app.model.LivePhotoExportMode
import com.puresnap.app.model.MediaItem
import com.puresnap.app.model.ParseErrorType
import com.puresnap.app.model.ParsedPost
import com.puresnap.app.model.PlatformType
import com.puresnap.app.ui.BatchItemStatus
import com.puresnap.app.ui.BatchQueueEntry
import com.puresnap.app.ui.MorphDownloadState
import com.puresnap.app.ui.SmartPasteBannerState
import com.puresnap.app.ui.components.MediaGridPreview
import com.puresnap.app.ui.components.MorphingDownloadButton
import com.puresnap.app.ui.components.ShimmerSkeletonCard
import com.puresnap.app.ui.components.SmartPasteBanner
import com.puresnap.app.util.DynamicMediaRenderer
import com.puresnap.app.util.ShareLinkExtractor

/**
 * Tab 1：工作台（PureSnap Workbench）
 * 采用皇家宝石蓝与冰川蓝（Sapphire & Glacier Blue）高质感多层次卡片视觉体系：
 * - 支持国内外 14+ 平台与直链实时识别、批量多链接队列解析
 * - 聚焦驱动呼吸边框动画（失焦零刷新开销）
 */
@Composable
fun WorkbenchScreen(
    inputText: String,
    isParsing: Boolean,
    currentPost: ParsedPost?,
    parseError: ParseErrorType,
    downloadState: MorphDownloadState,
    bgmSaving: Boolean,
    smartBanner: SmartPasteBannerState,
    preferences: UserPreferencesState,
    historyCount: Int,
    recentHistory: List<ParseHistoryEntity> = emptyList(),
    batchQueue: List<BatchQueueEntry> = emptyList(),
    isBatchRunning: Boolean = false,
    onInputTextChange: (String) -> Unit,
    onClearInput: () -> Unit,
    onPasteFromClipboard: () -> Unit = {},
    onPasteAndParse: () -> Unit,
    onBatchParse: () -> Unit = {},
    onSelectBatchEntry: (BatchQueueEntry) -> Unit = {},
    onDismissBatchQueue: () -> Unit = {},
    onAcceptSmartBanner: () -> Unit,
    onDismissSmartBanner: () -> Unit,
    onToggleSelectItem: (Int) -> Unit,
    onDragSelectItem: (Int, Boolean) -> Unit,
    onToggleSelectAll: () -> Unit,
    onInvertSelection: () -> Unit,
    onDownloadSelected: () -> Unit,
    onDownloadSingleItem: (MediaItem) -> Unit = {},
    onSaveBgm: () -> Unit,
    onSaveCompanionImage: (isAvatar: Boolean) -> Unit,
    onCopyText: (label: String, content: String) -> Unit,
    onRefreshCurrentPost: () -> Unit = {},
    onRestoreHistoryItem: (ParseHistoryEntity) -> Unit = {},
    onSelectLivePhotoMode: (LivePhotoExportMode) -> Unit = {},
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTools: () -> Unit = {},
    onOpenPlatforms: () -> Unit = {},
    onOpenWebViewAssist: () -> Unit,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current
    val scrollState = rememberScrollState()
    var isInputFocused by remember { mutableStateOf(false) }

    val extractedPreview = remember(inputText) {
        if (inputText.isBlank()) emptyList() else ShareLinkExtractor.extractAllUrls(inputText)
    }
    val singleDetected = extractedPreview.firstOrNull()

    val breathingAlpha = if (isInputFocused) {
        val infiniteTransition = rememberInfiniteTransition(label = "breathing_border")
        val anim by infiniteTransition.animateFloat(
            initialValue = 0.40f,
            targetValue = 0.92f,
            animationSpec = infiniteRepeatable(
                animation = tween(1200),
                repeatMode = RepeatMode.Reverse
            ),
            label = "border_alpha"
        )
        anim
    } else {
        0.16f
    }

    val inputCardBorderColor by animateColorAsState(
        targetValue = MaterialTheme.colorScheme.primary.copy(alpha = breathingAlpha),
        label = "input_border_color"
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp)
            .padding(top = 10.dp, bottom = 116.dp)
    ) {
        // 1. 极简高质感顶栏：左侧 PureSnap 品牌标识（自适应宽度防遮挡）与右侧图标工具栏
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 10.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(36.dp)
                        .shadow(
                            elevation = 6.dp,
                            shape = RoundedCornerShape(11.dp),
                            spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                        )
                        .clip(RoundedCornerShape(11.dp))
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.colorScheme.secondary
                                )
                            )
                        )
                ) {
                    Icon(
                        imageVector = Icons.Default.FilterCenterFocus,
                        contentDescription = "PureSnap",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "PureSnap",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontSize = 18.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = (-0.3).sp
                            ),
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)
                        ) {
                            Text(
                                text = "v${BuildConfig.VERSION_NAME}",
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.5.dp)
                            )
                        }
                    }
                    Text(
                        text = "国内外原图 · 实况 · 视频无损直出",
                        fontSize = 10.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                UniformTopIconButton(
                    icon = Icons.Default.Build,
                    label = "实用工具",
                    hasBadgeDot = false,
                    onClick = onOpenTools
                )
                UniformTopIconButton(
                    icon = Icons.Default.History,
                    label = "历史记录",
                    hasBadgeDot = historyCount > 0,
                    onClick = onOpenHistory
                )
                UniformTopIconButton(
                    icon = Icons.Default.Settings,
                    label = "设置",
                    hasBadgeDot = false,
                    onClick = onOpenSettings
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 2. 剪贴板灵动提示条（Smart Paste Banner）
        SmartPasteBanner(
            bannerState = smartBanner,
            onConfirmParse = onAcceptSmartBanner,
            onDismiss = onDismissSmartBanner
        )

        // 3. 24dp 大圆角高质感三层结构输入卡片
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = if (isInputFocused) 1.8.dp else 1.dp,
                    color = inputCardBorderColor,
                    shape = RoundedCornerShape(24.dp)
                )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Link,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "全网原图直链引擎",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    val pillColor = when {
                        extractedPreview.size > 1 -> Color(0xFF7C3AED)
                        singleDetected != null -> singleDetected.platform.brandColor
                        else -> MaterialTheme.colorScheme.primary
                    }
                    val pillText = when {
                        extractedPreview.size > 1 -> "检测到 ${extractedPreview.size} 条链接"
                        singleDetected != null -> "${singleDetected.platform.shortTag} · ${singleDetected.platform.capabilityHint.substringBefore(" ")}"
                        else -> "14+ 平台零损直出"
                    }
                    Surface(
                        color = pillColor.copy(alpha = 0.14f),
                        shape = CircleShape
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(pillColor)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = pillText,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = pillColor,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 内层冰蓝凹槽文本输入区
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(78.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f))
                        .border(
                            width = 0.8.dp,
                            color = if (isInputFocused) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                            } else {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                            },
                            shape = RoundedCornerShape(16.dp)
                        )
                        .padding(horizontal = 13.dp, vertical = 10.dp)
                ) {
                    if (inputText.isEmpty()) {
                        Text(
                            text = "粘贴小红书、抖音、微博、B站、IG、TikTok、X、Pinterest 等链接/口令或原图直链，支持多条链接批量解析…",
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.5.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.62f)
                        )
                    }
                    BasicTextField(
                        value = inputText,
                        onValueChange = onInputTextChange,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 13.5.sp,
                            lineHeight = 20.sp
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier
                            .fillMaxSize()
                            .onFocusChanged { isInputFocused = it.isFocused }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 底部按钮行：左 [粘贴 / 清空] + 右 [立即解析 / 批量解析 / 粘贴并解析]
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (inputText.isNotBlank()) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
                                modifier = Modifier
                                    .height(40.dp)
                                    .clip(CircleShape)
                                    .border(
                                        width = 0.8.dp,
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                                        shape = CircleShape
                                    )
                                    .clickable { onClearInput() }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 13.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DeleteSweep,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "清空",
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 12.5.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
                            modifier = Modifier
                                .height(40.dp)
                                .clip(CircleShape)
                                .border(
                                    width = 0.8.dp,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                                    shape = CircleShape
                                )
                                .clickable { onPasteFromClipboard() }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 13.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentPaste,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "粘贴",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 12.5.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }

                    val isMulti = extractedPreview.size > 1
                    val mainLabel = when {
                        isMulti -> "批量解析 (${extractedPreview.size})"
                        inputText.isNotBlank() -> "立即解析"
                        else -> "粘贴并解析"
                    }
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .height(40.dp)
                            .shadow(
                                elevation = 4.dp,
                                shape = CircleShape,
                                spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                            )
                            .clip(CircleShape)
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(
                                        MaterialTheme.colorScheme.primary,
                                        MaterialTheme.colorScheme.secondary
                                    )
                                )
                            )
                            .clickable {
                                focusManager.clearFocus()
                                if (isMulti) onBatchParse() else onPasteAndParse()
                            }
                            .padding(horizontal = 20.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = mainLabel,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 4. 国内外平台快捷胶囊栏 + 「14+ 全平台」矩阵入口
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f),
                modifier = Modifier
                    .height(31.dp)
                    .clip(CircleShape)
                    .clickable { onOpenTools() }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 11.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Build,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(13.5.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "实用工具",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.78f),
                modifier = Modifier
                    .height(31.dp)
                    .clip(CircleShape)
                    .clickable { onOpenPlatforms() }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 11.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Public,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "14+ 全平台",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            listOf(
                PlatformType.XIAOHONGSHU,
                PlatformType.DOUYIN,
                PlatformType.WEIBO,
                PlatformType.BILIBILI,
                PlatformType.INSTAGRAM,
                PlatformType.TIKTOK,
                PlatformType.TWITTER,
                PlatformType.PINTEREST
            ).forEach { platform ->
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 1.dp,
                    modifier = Modifier
                        .height(31.dp)
                        .clip(CircleShape)
                        .border(
                            width = 0.8.dp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                            shape = CircleShape
                        )
                        .clickable { onOpenPlatforms() }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(platform.brandColor)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = platform.shortTag,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }

        // 批量多链接解析进度队列卡片
        AnimatedVisibility(
            visible = batchQueue.isNotEmpty(),
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (isBatchRunning) "批量链接解析队列运行中…" else "批量解析队列完成（点击可切换作品）",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        IconButton(
                            onClick = onDismissBatchQueue,
                            modifier = Modifier.size(22.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "关闭队列",
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    ) {
                        batchQueue.forEachIndexed { idx, entry ->
                            val chipColor = when (entry.status) {
                                BatchItemStatus.SUCCESS -> Color(0xFF059669)
                                BatchItemStatus.FAILED -> MaterialTheme.colorScheme.error
                                BatchItemStatus.PARSING -> MaterialTheme.colorScheme.primary
                                BatchItemStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = chipColor.copy(alpha = 0.12f),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .border(0.8.dp, chipColor.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                                    .clickable { onSelectBatchEntry(entry) }
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                                    Text(
                                        text = "#${idx + 1} ${entry.platform.shortTag}",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = chipColor
                                    )
                                    Text(
                                        text = entry.statusLabel,
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 5. 异常状态提示卡片
        AnimatedVisibility(
            visible = parseError != ParseErrorType.NONE,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.90f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 14.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = parseError.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = parseError.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onOpenWebViewAssist,
                            shape = CircleShape,
                            modifier = Modifier.height(36.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.VerifiedUser,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text("WebView 验证提取", fontSize = 12.sp, maxLines = 1, softWrap = false)
                        }
                        OutlinedButton(
                            onClick = onOpenSettings,
                            shape = CircleShape,
                            modifier = Modifier.height(36.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp)
                        ) {
                            Text("配置 Cookie", fontSize = 12.sp, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        }

        // 6. 解析加载中的流光骨架屏
        AnimatedVisibility(
            visible = isParsing,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            ShimmerSkeletonCard(modifier = Modifier.padding(bottom = 14.dp))
        }

        // 7. 解析结果展示区
        AnimatedVisibility(
            visible = !isParsing && currentPost != null && parseError == ParseErrorType.NONE,
            enter = expandVertically(animationSpec = tween(280)) + fadeIn(animationSpec = tween(280)),
            exit = shrinkVertically() + fadeOut()
        ) {
            currentPost?.let { post ->
                ParsedResultCard(
                    post = post,
                    downloadState = downloadState,
                    bgmSaving = bgmSaving,
                    preferences = preferences,
                    onToggleSelectItem = onToggleSelectItem,
                    onDragSelectItem = onDragSelectItem,
                    onToggleSelectAll = onToggleSelectAll,
                    onInvertSelection = onInvertSelection,
                    onDownloadSelected = onDownloadSelected,
                    onDownloadSingleItem = onDownloadSingleItem,
                    onSaveBgm = onSaveBgm,
                    onSaveCompanionImage = onSaveCompanionImage,
                    onCopyText = onCopyText,
                    onRefreshPost = onRefreshCurrentPost,
                    onSelectLivePhotoMode = onSelectLivePhotoMode,
                    onOpenWebViewAssist = onOpenWebViewAssist
                )
            }
        }

        // 8. 尚未解析时的快捷入口：近期解析恢复 + 离线工具箱 + Bento 核心特性卡片
        if (!isParsing && currentPost == null && parseError == ParseErrorType.NONE) {
            // 实用离线影像工具快捷入口条
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 0.8.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(22.dp)
                    )
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "本地无损影像实用工具",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "打开工具箱 ›",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable { onOpenTools() }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        QuickUtilityTile(
                            icon = Icons.Default.Fingerprint,
                            title = "MD5 改写",
                            tint = Color(0xFF2563EB),
                            onClick = onOpenTools,
                            modifier = Modifier.weight(1f)
                        )
                        QuickUtilityTile(
                            icon = Icons.Default.PrivacyTip,
                            title = "隐私去EXIF",
                            tint = Color(0xFF0D9488),
                            onClick = onOpenTools,
                            modifier = Modifier.weight(1f)
                        )
                        QuickUtilityTile(
                            icon = Icons.Default.MotionPhotosAuto,
                            title = "实况合成",
                            tint = Color(0xFF7C3AED),
                            onClick = onOpenTools,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            if (recentHistory.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            width = 0.8.dp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                            shape = RoundedCornerShape(22.dp)
                        )
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "最近解析 · 快速接续",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "全部历史 (${historyCount}) ›",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .clickable { onOpenHistory() }
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        recentHistory.take(3).forEach { entity ->
                            val plat = runCatching { PlatformType.valueOf(entity.platform) }
                                .getOrDefault(PlatformType.UNKNOWN)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                                    .clickable { onRestoreHistoryItem(entity) }
                                    .padding(horizontal = 10.dp, vertical = 8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(plat.brandColor)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = entity.title.ifBlank { entity.authorName },
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "${plat.shortTag} · ${entity.mediaCount}项",
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = plat.brandColor
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 0.8.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(24.dp)
                    )
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(9.dp))
                        Text(
                            text = "PureSnap 无损直出与全网特性",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    FeatureGuideRow(
                        icon = Icons.Default.HighQuality,
                        title = "原图直出 · 零损直存",
                        subtitle = "默认保存 CDN 原文件原始字节流（JPG/PNG/HEIC/MP4），完整保留 Display P3 色域与 EXIF，零二次压缩。"
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    FeatureGuideRow(
                        icon = Icons.Default.MotionPhotosAuto,
                        title = "实况照片 (.MP.jpg) 原生合成",
                        subtitle = "支持小红书、抖音、微博 Live 实况解析，在头部兼容写入标准 XMP 并保留原 JPEG 码流，系统相册原生动图识别。"
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    FeatureGuideRow(
                        icon = Icons.Default.Share,
                        title = "系统分享抽屉与批量队列",
                        subtitle = "在任意国内外 App 分享至「PureSnap · 无损提取」，或一次性粘贴多条链接启动全自动批量解析。"
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickUtilityTile(
    icon: ImageVector,
    title: String,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(tint.copy(alpha = 0.08f))
            .clickable { onClick() }
            .padding(vertical = 10.dp, horizontal = 6.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.height(5.dp))
        Text(
            text = title,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
    }
}

@Composable
private fun FeatureGuideRow(
    icon: ImageVector,
    title: String,
    subtitle: String
) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(28.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(15.dp)
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 顶栏右侧统一规格图标胶囊按钮（36dp × 36dp 圆润方形，彻底避免与左侧软件名挤压重叠）
 */
@Composable
private fun UniformTopIconButton(
    icon: ImageVector,
    label: String,
    hasBadgeDot: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier
            .size(36.dp)
            .border(
                width = 0.8.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                shape = RoundedCornerShape(12.dp)
            )
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            if (hasBadgeDot) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 6.dp, end = 6.dp)
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
    }
}

@Composable
private fun ParsedResultCard(
    post: ParsedPost,
    downloadState: MorphDownloadState,
    bgmSaving: Boolean,
    preferences: UserPreferencesState,
    onToggleSelectItem: (Int) -> Unit,
    onDragSelectItem: (Int, Boolean) -> Unit,
    onToggleSelectAll: () -> Unit,
    onInvertSelection: () -> Unit,
    onDownloadSelected: () -> Unit,
    onDownloadSingleItem: (MediaItem) -> Unit,
    onSaveBgm: () -> Unit,
    onSaveCompanionImage: (isAvatar: Boolean) -> Unit,
    onCopyText: (label: String, content: String) -> Unit,
    onRefreshPost: () -> Unit,
    onSelectLivePhotoMode: (LivePhotoExportMode) -> Unit,
    onOpenWebViewAssist: () -> Unit
) {
    var copyExpanded by remember(post.id) { mutableStateOf(false) }
    var avatarBitmap by remember(post.authorAvatarUrl) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(post.authorAvatarUrl) {
        if (post.authorAvatarUrl.startsWith("http")) {
            avatarBitmap = DynamicMediaRenderer.loadAvatarBitmap(post.authorAvatarUrl, post.platform)
        }
    }

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                shape = RoundedCornerShape(24.dp)
            )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {

            // 1. 作者栏与平台标签、刷新/复制文案胶囊
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .border(1.2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), CircleShape)
                ) {
                    if (avatarBitmap != null) {
                        Image(
                            bitmap = avatarBitmap!!.asImageBitmap(),
                            contentDescription = post.authorName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(9.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "@${post.authorName}",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = post.parseEngineSource,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                IconButton(
                    onClick = onRefreshPost,
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "重新在线拉取直链",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                Surface(
                    color = post.platform.brandColor.copy(alpha = 0.12f),
                    shape = CircleShape,
                    modifier = Modifier.height(28.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.padding(horizontal = 9.dp)
                    ) {
                        Text(
                            text = post.postTypeLabel,
                            color = post.platform.brandColor,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 2. 文案展示与快捷配套提取胶囊栏（常驻快捷胶囊，点击文案可展开全部）
            val displayCaption = post.content.ifBlank { post.title }.ifBlank { "无附带正文文案" }
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .border(
                        width = 0.8.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                        shape = RoundedCornerShape(16.dp)
                    )
                    .clickable { copyExpanded = !copyExpanded }
            ) {
                Column(modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = displayCaption,
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = if (copyExpanded) 12 else 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = if (copyExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = "展开全文",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    AnimatedVisibility(visible = copyExpanded && post.tags.isNotEmpty()) {
                        Text(
                            text = post.tags.joinToString(" "),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // 快捷提取胶囊常驻一行，操作直观无需多步展开
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                    ) {
                        MiniActionPill(
                            icon = Icons.Default.ContentCopy,
                            text = "复制文案",
                            onClick = { onCopyText("完整文案", post.fullCopywritingText) }
                        )
                        MiniActionPill(
                            icon = Icons.Default.Link,
                            text = "复制直链",
                            onClick = {
                                val firstDirect = post.mediaItems.firstOrNull()?.originUrl ?: post.extractedUrl
                                onCopyText("原片直链", firstDirect)
                            }
                        )
                        if (post.tags.isNotEmpty()) {
                            MiniActionPill(
                                icon = Icons.Default.ContentCopy,
                                text = "复制标签",
                                onClick = { onCopyText("话题标签", post.tags.joinToString(" ")) }
                            )
                        }
                        if (post.coverUrl.isNotBlank()) {
                            MiniActionPill(
                                icon = Icons.Default.Image,
                                text = "保存封面",
                                onClick = { onSaveCompanionImage(false) }
                            )
                        }
                        if (post.authorAvatarUrl.isNotBlank()) {
                            MiniActionPill(
                                icon = Icons.Default.AccountCircle,
                                text = "保存头像",
                                onClick = { onSaveCompanionImage(true) }
                            )
                        }
                        MiniActionPill(
                            icon = Icons.Default.VerifiedUser,
                            text = "网页验证",
                            onClick = onOpenWebViewAssist
                        )
                    }
                }
            }

            if (post.hasLivePhotos) {
                Spacer(modifier = Modifier.height(10.dp))
                val nextMode = when (preferences.livePhotoExportMode) {
                    LivePhotoExportMode.MOTION_PHOTO_JPEG -> LivePhotoExportMode.DUAL_IMAGE_AND_MP4
                    LivePhotoExportMode.DUAL_IMAGE_AND_MP4 -> LivePhotoExportMode.STATIC_IMAGE_ONLY
                    LivePhotoExportMode.STATIC_IMAGE_ONLY -> LivePhotoExportMode.MOTION_PHOTO_JPEG
                }
                val modeShortName = when (preferences.livePhotoExportMode) {
                    LivePhotoExportMode.MOTION_PHOTO_JPEG -> "Android 动态照片 (.MP.jpg)"
                    LivePhotoExportMode.DUAL_IMAGE_AND_MP4 -> "原图 + 独立 MP4 双轨"
                    LivePhotoExportMode.STATIC_IMAGE_ONLY -> "仅保存静态原图"
                }
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0xFF059669).copy(alpha = 0.10f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .border(0.8.dp, Color(0xFF059669).copy(alpha = 0.28f), RoundedCornerShape(14.dp))
                        .clickable { onSelectLivePhotoMode(nextMode) }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.MotionPhotosAuto,
                                contentDescription = null,
                                tint = Color(0xFF059669),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "实况模式：$modeShortName",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF047857)
                            )
                        }
                        Text(
                            text = "轻触切换",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF059669)
                        )
                    }
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            )

            // 3. 选集控制栏：左侧计数胶囊与右侧等宽胶囊按钮严格水平对齐
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                val mediaSummaryText = buildString {
                    append("共 ${post.totalCount} 项")
                    if (post.livePhotoCount > 0) append(" · ${post.livePhotoCount} 实况")
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(24.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Collections,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(7.dp))
                    Text(
                        text = mediaSummaryText,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
                        shape = CircleShape
                    ) {
                        Text(
                            text = "已选 ${post.selectedCount}/${post.totalCount}",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                    if (post.isDuplicateDownload && preferences.duplicateReminderEnabled) {
                        Spacer(modifier = Modifier.width(5.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = CircleShape
                        ) {
                            Text(
                                text = "已存过",
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                if (post.totalCount > 1) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        UniformSmallCapsuleButton(
                            text = "反选",
                            isPrimary = false,
                            onClick = onInvertSelection
                        )
                        UniformSmallCapsuleButton(
                            text = if (post.allSelected) "取消" else "全选",
                            isPrimary = true,
                            onClick = onToggleSelectAll
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 4. 圆角网格图集/实况/视频预览
            MediaGridPreview(
                items = post.mediaItems,
                platform = post.platform,
                customCookie = preferences.cookieForPlatform(post.platform),
                onToggleSelect = onToggleSelectItem,
                onDragSetSelect = onDragSelectItem,
                onSaveSingleItem = onDownloadSingleItem,
                onCopyUrl = { url -> onCopyText("原片直链", url) }
            )

            Spacer(modifier = Modifier.height(14.dp))

            // 5. 底部操作栏：[🎵 提取音乐]        [📥 保存选中原图 (X)]
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (post.bgmAudio != null) {
                    OutlinedButton(
                        onClick = onSaveBgm,
                        shape = CircleShape,
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        modifier = Modifier
                            .weight(0.38f)
                            .height(46.dp)
                    ) {
                        if (bgmSaving) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(15.dp)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.MusicNote,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (bgmSaving) "提取中" else "提取音乐",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }

                MorphingDownloadButton(
                    downloadState = downloadState,
                    selectedCount = post.selectedCount,
                    onClick = onDownloadSelected,
                    modifier = Modifier.weight(if (post.bgmAudio != null) 0.62f else 1f)
                )
            }
        }
    }
}

@Composable
private fun UniformSmallCapsuleButton(
    text: String,
    isPrimary: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = CircleShape,
        color = if (isPrimary) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        modifier = Modifier
            .width(58.dp)
            .height(28.dp)
            .clip(CircleShape)
            .clickable { onClick() }
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            Text(
                text = text,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = if (isPrimary) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

@Composable
private fun MiniActionPill(
    icon: ImageVector,
    text: String,
    onClick: () -> Unit
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier
            .height(28.dp)
            .border(
                width = 0.8.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                shape = CircleShape
            )
            .clip(CircleShape)
            .clickable { onClick() }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(13.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = text,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
