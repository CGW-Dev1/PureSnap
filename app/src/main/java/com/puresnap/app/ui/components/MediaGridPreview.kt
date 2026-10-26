package com.puresnap.app.ui.components

import android.graphics.Bitmap
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MotionPhotosOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.puresnap.app.model.LivePhotoExportMode
import com.puresnap.app.model.MediaItem
import com.puresnap.app.model.PlatformType
import com.puresnap.app.model.QualityBadge
import com.puresnap.app.util.DynamicMediaRenderer

/**
 * 自适应圆角网格预览组件（P0）
 * - 单视频/单图：采用大尺寸精致卡片展示，不再留出空白列
 * - 双图：2 列均衡排布
 * - 多图集 (3~30+ 张)：3 列九宫格排布，支持长按滑动连续多选 (Drag-to-Select)
 * - 真实加载目标平台 CDN 原图/封面，支持点击全屏双指缩放或视频在线播放
 */
@Composable
fun MediaGridPreview(
    items: List<MediaItem>,
    platform: PlatformType,
    onToggleSelect: (Int) -> Unit,
    onDragSetSelect: (Int, Boolean) -> Unit,
    onExportSingleLivePhoto: (MediaItem, LivePhotoExportMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val cellBoundsMap = remember { mutableStateMapOf<Int, Rect>() }
    var dragTargetSelectedState by remember { mutableStateOf(true) }
    var previewingItem by remember { mutableStateOf<MediaItem?>(null) }
    var liveModalItem by remember { mutableStateOf<MediaItem?>(null) }

    val columns = when (items.size) {
        1 -> 1
        2 -> 2
        else -> 3
    }
    val rows = remember(items, columns) { items.chunked(columns) }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(items.size) {
                if (items.size > 1) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { startOffset ->
                            val hitEntry = cellBoundsMap.entries.firstOrNull { (_, rect) ->
                                rect.contains(startOffset)
                            }
                            if (hitEntry != null) {
                                val idx = hitEntry.key
                                val currentSelected = items.getOrNull(idx)?.selected ?: true
                                dragTargetSelectedState = !currentSelected
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onDragSetSelect(idx, dragTargetSelectedState)
                            }
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val pos = change.position
                            val hitEntry = cellBoundsMap.entries.firstOrNull { (_, rect) ->
                                rect.contains(pos)
                            }
                            if (hitEntry != null) {
                                onDragSetSelect(hitEntry.key, dragTargetSelectedState)
                            }
                        }
                    )
                }
            }
    ) {
        rows.forEach { rowItems ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                rowItems.forEach { item ->
                    val cellAspect = when (columns) {
                        1 -> if (item.width > item.height) 1.50f else 1.22f
                        2 -> 0.84f
                        else -> 0.80f
                    }
                    MediaGridCell(
                        item = item,
                        platform = platform,
                        aspectRatio = cellAspect,
                        onToggleCheck = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onToggleSelect(item.index)
                        },
                        onClickPreview = {
                            if (item.isLivePhoto && !item.liveVideoUrl.isNullOrBlank()) {
                                liveModalItem = item
                            } else {
                                previewingItem = item
                            }
                        },
                        onLongPressLive = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            if (item.isLivePhoto) {
                                liveModalItem = item
                            } else {
                                previewingItem = item
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .onGloballyPositioned { coords ->
                                cellBoundsMap[item.index] = coords.boundsInRoot()
                            }
                    )
                }
                if (columns > 1 && rowItems.size < columns) {
                    repeat(columns - rowItems.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }

    previewingItem?.let { item ->
        ZoomableMediaPreviewDialog(
            item = item,
            platform = platform,
            onDismiss = { previewingItem = null },
            onToggleSelect = { onToggleSelect(item.index) }
        )
    }

    liveModalItem?.let { item ->
        LivePhotoDualExportDialog(
            item = item,
            platform = platform,
            onDismiss = { liveModalItem = null },
            onExportStaticOnly = {
                onExportSingleLivePhoto(item, LivePhotoExportMode.STATIC_IMAGE_ONLY)
                liveModalItem = null
            },
            onExportBothImageAndMp4 = {
                onExportSingleLivePhoto(item, LivePhotoExportMode.BOTH_IMAGE_AND_MP4)
                liveModalItem = null
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaGridCell(
    item: MediaItem,
    platform: PlatformType,
    aspectRatio: Float,
    onToggleCheck: () -> Unit,
    onClickPreview: () -> Unit,
    onLongPressLive: () -> Unit,
    modifier: Modifier = Modifier
) {
    var bitmap by remember(item.id, item.previewUrl) { mutableStateOf<Bitmap?>(null) }
    var isLoading by remember(item.id, item.previewUrl) { mutableStateOf(true) }

    LaunchedEffect(item.id, item.previewUrl) {
        isLoading = true
        bitmap = DynamicMediaRenderer.loadPreviewBitmap(
            item = item,
            platform = platform,
            maxDimension = 720
        )
        isLoading = false
    }

    val infiniteTransition = rememberInfiniteTransition(label = "live_badge_pulse")
    val livePulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(750),
            repeatMode = RepeatMode.Reverse
        ),
        label = "live_scale"
    )

    Box(
        modifier = modifier
            .aspectRatio(aspectRatio)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
            .border(
                width = if (item.selected) 2.dp else 0.5.dp,
                color = if (item.selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                shape = RoundedCornerShape(14.dp)
            )
            .combinedClickable(
                onClick = onClickPreview,
                onLongClick = onLongPressLive
            )
    ) {
        when {
            bitmap != null -> {
                Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(if (item.selected) 1f else 0.58f)
                )
            }
            isLoading -> {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                    modifier = Modifier
                        .size(22.dp)
                        .align(Alignment.Center)
                )
            }
            else -> {
                Icon(
                    imageVector = if (item.isVideo) Icons.Default.Videocam else Icons.Default.BrokenImage,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                    modifier = Modifier
                        .size(28.dp)
                        .align(Alignment.Center)
                )
            }
        }

        // 仅在底部保留轻量渐变遮罩以衬托分辨率文字，不污染原图主体色彩
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.62f)
                        )
                    )
                )
        )

        // 左上角：画质/实况角标 ([4K], [LIVE], [原图], [1080P])
        val badgeBgColor = when (item.badge) {
            QualityBadge.LIVE -> Color(0xFFE11D48)
            QualityBadge.FOUR_K -> Color(0xFF4F46E5)
            QualityBadge.VIDEO_HD -> Color(0xFF0284C7)
            else -> Color(0xFF2563EB)
        }

        Surface(
            color = badgeBgColor.copy(alpha = 0.90f),
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(6.dp)
                .scale(if (item.isLivePhoto) livePulseScale else 1f)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
            ) {
                if (item.isLivePhoto) {
                    Icon(
                        imageVector = Icons.Default.MotionPhotosOn,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(10.dp)
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                }
                Text(
                    text = item.badge.text,
                    color = Color.White,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // 右上角：圆润勾选框
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .size(21.dp)
                .clip(CircleShape)
                .background(
                    if (item.selected) MaterialTheme.colorScheme.primary
                    else Color.Black.copy(alpha = 0.42f)
                )
                .border(
                    width = 1.2.dp,
                    color = Color.White.copy(alpha = 0.9f),
                    shape = CircleShape
                )
                .clickable { onToggleCheck() }
        ) {
            if (item.selected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "已选中",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(13.dp)
                )
            }
        }

        // 中央视频半透明播放按钮
        if (item.isVideo) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.48f))
                    .border(1.dp, Color.White.copy(alpha = 0.8f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "播放视频",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        // 底部：分辨率与格式标签
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 7.dp, vertical = 4.dp)
        ) {
            Text(
                text = item.resolutionLabel,
                color = Color.White.copy(alpha = 0.95f),
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Clip
            )
            Text(
                text = item.format,
                color = Color.White.copy(alpha = 0.88f),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * 全屏无损预览弹窗（图片支持双指缩放，视频支持直接在线播放）
 */
@Composable
private fun ZoomableMediaPreviewDialog(
    item: MediaItem,
    platform: PlatformType,
    onDismiss: () -> Unit,
    onToggleSelect: () -> Unit
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var highResBitmap by remember(item.id) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(item.id) {
        if (!item.isVideo) {
            highResBitmap = DynamicMediaRenderer.loadPreviewBitmap(
                item = item,
                platform = platform,
                maxDimension = 2048
            )
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.95f))
        ) {
            if (item.isVideo) {
                AndroidView(
                    factory = { ctx ->
                        VideoView(ctx).apply {
                            val controller = MediaController(ctx)
                            controller.setAnchorView(this)
                            setMediaController(controller)
                            val headers = mapOf(
                                "User-Agent" to "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1",
                                "Referer" to DynamicMediaRenderer.refererForPlatform(platform, item.originUrl)
                            )
                            setVideoURI(Uri.parse(item.originUrl), headers)
                            setOnPreparedListener { mp ->
                                mp.isLooping = true
                                start()
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.Center)
                )
            } else {
                highResBitmap?.let { bmp ->
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    scale = (scale * zoom).coerceIn(1f, 4.5f)
                                    offset = if (scale > 1f) {
                                        Offset(
                                            x = (offset.x + pan.x).coerceIn(-700f, 700f),
                                            y = (offset.y + pan.y).coerceIn(-900f, 900f)
                                        )
                                    } else {
                                        Offset.Zero
                                    }
                                }
                            }
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offset.x,
                                translationY = offset.y
                            )
                    )
                } ?: CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            // 顶部信息与关闭栏（安全避让状态栏与刘海屏）
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Surface(
                    color = Color.White.copy(alpha = 0.16f),
                    shape = CircleShape
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = if (item.isVideo) Icons.Default.Videocam else Icons.Default.ZoomIn,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (item.isVideo) "无水印视频预览 · ${item.resolutionLabel}"
                            else "双指缩放 (${String.format("%.1fx", scale)}) · ${item.resolutionLabel}",
                            color = Color.White,
                            fontSize = 12.sp
                        )
                    }
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.18f))
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "关闭预览",
                        tint = Color.White
                    )
                }
            }

            // 底部操作卡片（安全避让系统底部手势小白条/导航键，彻底解决文字和卡片截断问题）
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF1E293B).copy(alpha = 0.94f)
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 26.dp, top = 8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp)
                    ) {
                        Text(
                            text = "第 ${item.index + 1} 项 · ${item.format} 无水印原片",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "分辨率 ${item.resolutionLabel} · ${item.colorSpace}",
                            color = Color.White.copy(alpha = 0.75f),
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Button(
                        onClick = onToggleSelect,
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (item.selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.22f)
                        ),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                        modifier = Modifier.height(40.dp)
                    ) {
                        Text(
                            text = if (item.selected) "已选中 ✓" else "勾选此项",
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
}

@Composable
private fun LivePhotoDualExportDialog(
    item: MediaItem,
    platform: PlatformType,
    onDismiss: () -> Unit,
    onExportStaticOnly: () -> Unit,
    onExportBothImageAndMp4: () -> Unit
) {
    var previewBmp by remember(item.id) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(item.id) {
        previewBmp = DynamicMediaRenderer.loadPreviewBitmap(
            item = item,
            platform = platform,
            maxDimension = 1080
        )
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Surface(
                        color = Color(0xFFE11D48),
                        shape = CircleShape
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.MotionPhotosOn,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "LIVE 实况图双模导出",
                                color = Color.White,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "关闭"
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.85f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Black)
                ) {
                    previewBmp?.let { bmp ->
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "实况预览",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Button(
                    onClick = onExportBothImageAndMp4,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.MotionPhotosOn,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "同时导出动态视频 (MP4) + 静态原图",
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = onExportStaticOnly,
                    shape = CircleShape,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.PhotoLibrary,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "仅保存为静态高清原图 (${item.resolutionLabel})",
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}
