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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
 * Tab 1：工作台（Home Workbench）
 * 视觉打磨重点：
 * 1. 顶栏左侧「净存 PureSnap」与右侧「历史」「设置」严格处于同一水平中心线，且「历史」与「设置」采用完全等宽等高 (72dp × 34dp) 的统一胶囊
 * 2. 「支持平台」栏的四个平台胶囊采用等权等高 (weight(1f) × 30dp) 布局，尺寸完全一致、绝不挤压换行
 * 3. 卡片采用微光描边与柔和层次阴影，作者名完整展示不省略，底部留足空间不与悬浮导航栏重叠
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
        initialValue = 0.35f,
        targetValue = 0.90f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "border_alpha"
    )

    val inputBorderColor by animateColorAsState(
        targetValue = if (isInputFocused) {
            MaterialTheme.colorScheme.primary.copy(alpha = breathingAlpha)
        } else {
            MaterialTheme.colorScheme.outline.copy(alpha = 0.20f)
        },
        label = "input_border_color"
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp)
            .padding(top = 10.dp, bottom = 112.dp)
    ) {
        // 1. 极简顶栏：左侧品牌与右侧等宽胶囊严格处于同一水平线
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(34.dp)
                        .shadow(elevation = 4.dp, shape = RoundedCornerShape(10.dp))
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.colorScheme.secondary
                                )
                            )
                        )
                ) {
                    Text(
                        text = "净",
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 16.sp
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "净存 PureSnap",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontSize = 19.sp,
                        fontWeight = FontWeight.ExtraBold
                    ),
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // 右侧：[历史] 与 [设置] 胶囊尺寸完全一致 (72dp × 34dp)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                UniformTopCapsuleButton(
                    icon = Icons.Default.History,
                    label = "历史",
                    onClick = onOpenHistory
                )
                UniformTopCapsuleButton(
                    icon = Icons.Default.Settings,
                    label = "设置",
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

        // 3. 24dp 大圆角高质感输入卡片
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = if (isInputFocused) 1.8.dp else 0.8.dp,
                    color = inputBorderColor,
                    shape = RoundedCornerShape(24.dp)
                )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .padding(top = 1.dp)
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Link,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(60.dp)
                    ) {
                        if (inputText.isEmpty()) {
                            Text(
                                text = "粘贴小红书、抖音、最右等分享口令或链接...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                            )
                        }
                        BasicTextField(
                            value = inputText,
                            onValueChange = onInputTextChange,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                                lineHeight = 20.sp
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier
                                .fillMaxSize()
                                .onFocusChanged { isInputFocused = it.isFocused }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 底部按钮行：左 [清空] 柔和胶囊 + 右 [粘贴并解析] 实心高亮胶囊，高度统一 38.dp
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                        modifier = Modifier
                            .height(38.dp)
                            .clip(CircleShape)
                            .clickable { onClearInput() }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteSweep,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
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

                    Button(
                        onClick = {
                            focusManager.clearFocus()
                            onPasteAndParse()
                        },
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 0.dp),
                        modifier = Modifier.height(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentPaste,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "粘贴并解析",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.5.sp,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 4. ⚡ 支持平台：四个胶囊采用等权等高 (weight(1f) × 30.dp)，尺寸完全一致，绝不换行变形
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "⚡ 支持平台",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false
            )
            Spacer(modifier = Modifier.width(2.dp))
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
                        .height(30.dp)
                        .border(
                            width = 0.8.dp,
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f),
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
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.88f)
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

        // 8. 尚未解析时的精致引导卡
        if (!isParsing && currentPost == null && parseError == ParseErrorType.NONE) {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 0.8.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(24.dp)
                    )
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "无损原画提取指南",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "• 剪贴板灵动感知：在小红书、抖音或最右复制分享链接，切回净存即可自动识别并解析无水印原图与视频。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "• 系统分享半屏直达：在社交软件点击「分享 → 更多 → 净存·无损提取」，即可在底部半屏抽屉一键保存。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * 顶栏右侧统一规格胶囊按钮（固定 72dp × 34dp，确保「历史」与「设置」大小 100% 一致且水平对齐）
 */
@Composable
private fun UniformTopCapsuleButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier
            .width(72.dp)
            .height(34.dp)
            .border(
                width = 0.8.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.20f),
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
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 0.8.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.16f),
                shape = RoundedCornerShape(24.dp)
            )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {

            // 1. 作者栏与平台标签、复制文案按钮（左侧作者名自适应占满剩余空间，绝不压成 ...）
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(post.platform.brandColor.copy(alpha = 0.14f))
                        .border(0.8.dp, post.platform.brandColor.copy(alpha = 0.25f), CircleShape)
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
                            tint = post.platform.brandColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = "@${post.authorName}",
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.5.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(6.dp))

                Surface(
                    color = post.platform.brandColor.copy(alpha = 0.12f),
                    shape = CircleShape,
                    modifier = Modifier.height(26.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.padding(horizontal = 9.dp)
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
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
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
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
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

            Spacer(modifier = Modifier.height(10.dp))

            // 2. 文案折叠卡片与配套信息提取
            val displayCaption = post.content.ifBlank { post.title }.ifBlank { "无附带正文文案" }
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { copyExpanded = !copyExpanded }
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = displayCaption,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = if (copyExpanded) 10 else 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = if (copyExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = "展开配套工具",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    AnimatedVisibility(visible = copyExpanded) {
                        Column(modifier = Modifier.padding(top = 8.dp)) {
                            if (post.tags.isNotEmpty()) {
                                Text(
                                    text = post.tags.joinToString(" "),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                            }

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
                                    text = "浏览器验证",
                                    onClick = onOpenWebViewAssist
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)
            )

            // 3. 选集控制栏：左侧计数与右侧等宽胶囊按钮严格水平对齐
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
                    Text(
                        text = "📷 共 ${post.totalCount} $mediaUnit (已选 ${post.selectedCount}/${post.totalCount})",
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        softWrap = false
                    )
                    if (post.isDuplicateDownload && preferences.duplicateReminderEnabled) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.75f),
                            shape = CircleShape
                        ) {
                            Text(
                                text = "已存过",
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
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
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f)
        },
        modifier = Modifier
            .width(56.dp)
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
                fontWeight = FontWeight.SemiBold,
                color = if (isPrimary) {
                    MaterialTheme.colorScheme.onPrimaryContainer
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
                width = 0.6.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f),
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
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                softWrap = false,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
