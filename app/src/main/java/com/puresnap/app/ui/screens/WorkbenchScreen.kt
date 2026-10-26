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
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FilterCenterFocus
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.puresnap.app.data.preferences.UserPreferencesState
import com.puresnap.app.model.LivePhotoExportMode
import com.puresnap.app.model.MediaItem
import com.puresnap.app.model.ParseErrorType
import com.puresnap.app.model.ParsedPost
import com.puresnap.app.model.PlatformType
import com.puresnap.app.ui.MorphDownloadState
import com.puresnap.app.ui.SmartPasteBannerState
import com.puresnap.app.ui.components.MediaGridPreview
import com.puresnap.app.ui.components.MorphingDownloadButton
import com.puresnap.app.ui.components.ShimmerSkeletonCard
import com.puresnap.app.ui.components.SmartPasteBanner
import com.puresnap.app.util.DynamicMediaRenderer

/**
 * Tab 1：工作台（PureSnap Workbench）
 * 采用皇家宝石蓝与冰川蓝（Sapphire & Glacier Blue）高质感多层次卡片视觉体系：
 * 1. 顶栏左侧「PureSnap」品牌徽标与右侧等大胶囊按钮（历史 / 设置，74dp × 34dp）严格处于同一水平中心线
 * 2. 核心输入卡采用「外层高光白卡 + 内层冰蓝凹槽输入区 + 宝石蓝渐变主按钮」三层立体结构
 * 3. 四大主力平台胶囊采用 4 列等宽均分（weight(1f) × 34dp），整洁对称不折行
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
    onInputTextChange: (String) -> Unit,
    onClearInput: () -> Unit,
    onPasteAndParse: () -> Unit,
    onAcceptSmartBanner: () -> Unit,
    onDismissSmartBanner: () -> Unit,
    onToggleSelectItem: (Int) -> Unit,
    onDragSelectItem: (Int, Boolean) -> Unit,
    onToggleSelectAll: () -> Unit,
    onInvertSelection: () -> Unit,
    onDownloadSelected: () -> Unit,
    onExportSingleLivePhoto: (MediaItem, LivePhotoExportMode) -> Unit,
    onSaveBgm: () -> Unit,
    onSaveCompanionImage: (isAvatar: Boolean) -> Unit,
    onCopyText: (label: String, content: String) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenWebViewAssist: () -> Unit,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current
    val scrollState = rememberScrollState()
    var isInputFocused by remember { mutableStateOf(false) }

    val infiniteTransition = rememberInfiniteTransition(label = "breathing_border")
    val breathingAlpha by infiniteTransition.animateFloat(
        initialValue = 0.40f,
        targetValue = 0.92f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "border_alpha"
    )

    val inputCardBorderColor by animateColorAsState(
        targetValue = if (isInputFocused) {
            MaterialTheme.colorScheme.primary.copy(alpha = breathingAlpha)
        } else {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
        },
        label = "input_border_color"
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp)
            .padding(top = 10.dp, bottom = 116.dp)
    ) {
        // 1. 极简高质感顶栏：左侧 PureSnap 品牌标识与右侧等宽胶囊严格水平对齐
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
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
                Text(
                    text = "PureSnap",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontSize = 21.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = (-0.4).sp
                    ),
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    softWrap = false
                )
            }

            // 右侧：[历史] 与 [设置] 胶囊尺寸完全一致 (74dp × 34dp)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                UniformTopCapsuleButton(
                    icon = Icons.Default.History,
                    label = "历史",
                    hasBadgeDot = historyCount > 0,
                    onClick = onOpenHistory
                )
                UniformTopCapsuleButton(
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
                // 卡片顶部状态条
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
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
                            text = "智能口令与短链提取引擎",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            softWrap = false
                        )
                    }

                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
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
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = "CDN 原画直连",
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
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
                        .height(74.dp)
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
                            text = "粘贴小红书、抖音、最右、快手分享口令或链接，自动剥离杂乱文案与水印参数...",
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
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

                // 底部按钮行：左 [清空] 冰蓝胶囊 + 右 [粘贴并解析] 宝石蓝渐变胶囊 (统一高度 40.dp)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
                        modifier = Modifier
                            .width(88.dp)
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
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxSize()
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
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
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
                                onPasteAndParse()
                            }
                            .padding(horizontal = 22.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.ContentPaste,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "粘贴并解析",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.5.sp,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 4. 平台徽标栏：4 枚等宽等高胶囊均分整行 (weight(1f) × 32.dp)，视觉对称饱满
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            listOf(
                PlatformType.XIAOHONGSHU,
                PlatformType.DOUYIN,
                PlatformType.ZUIYOU,
                PlatformType.KUAISHOU
            ).forEach { platform ->
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 1.dp,
                    modifier = Modifier
                        .weight(1f)
                        .height(32.dp)
                        .border(
                            width = 0.8.dp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                            shape = CircleShape
                        )
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.5.dp)
                                .clip(CircleShape)
                                .background(platform.brandColor)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = platform.shortTag,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            softWrap = false
                        )
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
                    onExportSingleLivePhoto = onExportSingleLivePhoto,
                    onSaveBgm = onSaveBgm,
                    onSaveCompanionImage = onSaveCompanionImage,
                    onCopyText = onCopyText,
                    onOpenWebViewAssist = onOpenWebViewAssist
                )
            }
        }

        // 8. 尚未解析时的蓝调 Bento 特性指引卡片
        if (!isParsing && currentPost == null && parseError == ParseErrorType.NONE) {
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
                            text = "PureSnap 核心无感提取特性",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    FeatureGuideRow(
                        icon = Icons.Default.ContentPaste,
                        title = "剪贴板灵动感知",
                        subtitle = "在小红书、抖音或最右复制分享口令后切回 PureSnap，顶部自动弹出灵动胶囊一键解析。"
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    FeatureGuideRow(
                        icon = Icons.Default.Share,
                        title = "系统分享半屏抽屉",
                        subtitle = "在社交软件点击「分享 → 更多 → PureSnap · 无损提取」，底部半屏即存即走。"
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    FeatureGuideRow(
                        icon = Icons.Default.HighQuality,
                        title = "真·CDN 原画与 Live 实况直出",
                        subtitle = "剥离平台压缩水印后缀，支持 4K 原图、小红书实况图双模导出与 100% 质量转码。"
                    )
                }
            }
        }
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
 * 顶栏右侧统一规格胶囊按钮（固定 74dp × 34dp，确保「历史」与「设置」大小 100% 一致且水平对齐）
 */
@Composable
private fun UniformTopCapsuleButton(
    icon: ImageVector,
    label: String,
    hasBadgeDot: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier
            .width(74.dp)
            .height(34.dp)
            .border(
                width = 0.8.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                shape = CircleShape
            )
            .clip(CircleShape)
            .clickable { onClick() }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(15.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false
            )
            if (hasBadgeDot) {
                Spacer(modifier = Modifier.width(3.dp))
                Box(
                    modifier = Modifier
                        .size(5.dp)
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
    onExportSingleLivePhoto: (MediaItem, LivePhotoExportMode) -> Unit,
    onSaveBgm: () -> Unit,
    onSaveCompanionImage: (isAvatar: Boolean) -> Unit,
    onCopyText: (label: String, content: String) -> Unit,
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

            // 1. 作者栏与平台标签、复制文案胶囊（左侧作者名自适应占满剩余空间）
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

                Text(
                    text = "@${post.authorName}",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(6.dp))

                Surface(
                    color = post.platform.brandColor.copy(alpha = 0.12f),
                    shape = CircleShape,
                    modifier = Modifier.height(28.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.padding(horizontal = 10.dp)
                    ) {
                        Text(
                            text = post.postTypeLabel,
                            color = post.platform.brandColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier
                        .height(30.dp)
                        .clip(CircleShape)
                        .clickable { onCopyText("完整文案", post.fullCopywritingText) }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(horizontal = 11.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "复制文案",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
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
                            text = "复制标题",
                            onClick = { onCopyText("标题", post.title.ifBlank { post.content }) }
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
                val mediaUnit = if (post.mediaItems.all { it.isVideo }) "个视频" else "张原图"
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
                        text = "共 ${post.totalCount} $mediaUnit",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        softWrap = false
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

            // 4. 圆角网格图集/视频预览
            MediaGridPreview(
                items = post.mediaItems,
                platform = post.platform,
                onToggleSelect = onToggleSelectItem,
                onDragSetSelect = onDragSelectItem,
                onExportSingleLivePhoto = onExportSingleLivePhoto
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
