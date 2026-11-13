package com.puresnap.app.ui.components

import android.graphics.Bitmap
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MotionPhotosAuto
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.puresnap.app.model.MediaItem
import com.puresnap.app.model.PlatformType
import com.puresnap.app.model.QualityBadge
import com.puresnap.app.util.DynamicMediaRenderer
import java.io.File

/**
 * 自适应圆角网格预览组件：
 * - 单视频/实况/单图：大尺寸卡片呈现
 * - 双图：2 列均衡排布；多图：3 列九宫格，修复精准局部坐标长按滑选 (Drag-to-Select)
 * - 点击进入全屏左右滑动灯箱 (HorizontalPager)，支持双指缩放、双击放大、实况一键动态播放与单张极速保存
 */
@Composable
fun MediaGridPreview(
    items: List<MediaItem>,
    platform: PlatformType,
    onToggleSelect: (Int) -> Unit,
    onDragSetSelect: (Int, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    customCookie: String? = null,
    onSaveSingleItem: ((MediaItem) -> Unit)? = null,
    onCopyUrl: ((String) -> Unit)? = null
) {
    val haptic = LocalHapticFeedback.current
    val cellBoundsMap = remember { mutableStateMapOf<Int, Rect>() }
    var gridOriginInRoot by remember { mutableStateOf(Offset.Zero) }
    var dragTargetSelectedState by remember { mutableStateOf(true) }
    var previewIndex by remember { mutableStateOf<Int?>(null) }

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
            .onGloballyPositioned { coords ->
                gridOriginInRoot = coords.positionInRoot()
            }
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
                        1 -> if (item.width > item.height) 1.48f else 1.18f
                        2 -> 0.84f
                        else -> 0.80f
                    }
                    MediaGridCell(
                        item = item,
                        platform = platform,
                        customCookie = customCookie,
                        aspectRatio = cellAspect,
                        thumbMaxDim = if (columns == 1) 1080 else 640,
                        onToggleCheck = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onToggleSelect(item.index)
                        },
                        onClickPreview = {
                            val posInList = items.indexOfFirst { it.index == item.index }.coerceAtLeast(0)
                            previewIndex = posInList
                        },
                        modifier = Modifier
                            .weight(1f)
                            .onGloballyPositioned { coords ->
                                val rootRect = coords.boundsInRoot()
                                cellBoundsMap[item.index] = Rect(
                                    left = rootRect.left - gridOriginInRoot.x,
                                    top = rootRect.top - gridOriginInRoot.y,
                                    right = rootRect.right - gridOriginInRoot.x,
                                    bottom = rootRect.bottom - gridOriginInRoot.y
                                )
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

    previewIndex?.let { initialPos ->
        MediaLightboxPagerDialog(
            items = items,
            initialPage = initialPos.coerceIn(0, (items.size - 1).coerceAtLeast(0)),
            platform = platform,
            customCookie = customCookie,
            onDismiss = { previewIndex = null },
            onToggleSelect = onToggleSelect,
            onSaveSingleItem = onSaveSingleItem,
            onCopyUrl = onCopyUrl
        )
    }
}

@Composable
private fun MediaGridCell(
    item: MediaItem,
    platform: PlatformType,
    customCookie: String?,
    aspectRatio: Float,
    thumbMaxDim: Int,
    onToggleCheck: () -> Unit,
    onClickPreview: () -> Unit,
    modifier: Modifier = Modifier
) {
    var bitmap by remember(item.id, item.previewUrl) { mutableStateOf<Bitmap?>(null) }
    var isLoading by remember(item.id, item.previewUrl) { mutableStateOf(true) }

    LaunchedEffect(item.id, item.previewUrl, customCookie) {
        isLoading = true
        bitmap = DynamicMediaRenderer.loadPreviewBitmap(
            item = item,
            platform = platform,
            customCookie = customCookie,
            maxDimension = thumbMaxDim
        )
        isLoading = false
    }

    Box(
        modifier = modifier
            .aspectRatio(aspectRatio)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
            .border(
                width = if (item.selected) 2.dp else 0.6.dp,
                color = if (item.selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                shape = RoundedCornerShape(14.dp)
            )
            .clickable { onClickPreview() }
    ) {
        when {
            bitmap != null -> {
                Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(if (item.selected) 1f else 0.56f)
                )
            }

            isLoading -> {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.65f),
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

        // 底部轻梯度渐变护眼垫底
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.66f))
                    )
                )
        )

        // 左上角：画质/实况角标
        val badgeBgColor = when (item.badge) {
            QualityBadge.LIVE -> Color(0xFF059669)
            QualityBadge.FOUR_K -> Color(0xFF4F46E5)
            QualityBadge.VIDEO_HD -> Color(0xFF0284C7)
            else -> Color(0xFF2563EB)
        }

        Surface(
            color = badgeBgColor.copy(alpha = 0.92f),
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
            ) {
                if (item.isLivePhoto) {
                    Icon(
                        imageVector = Icons.Default.MotionPhotosAuto,
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

        // 右上角：扩大触控热区的勾选框
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .clickable { onToggleCheck() }
                .padding(6.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(23.dp)
                    .clip(CircleShape)
                    .background(
                        if (item.selected) MaterialTheme.colorScheme.primary
                        else Color.Black.copy(alpha = 0.45f)
                    )
                    .border(
                        width = 1.2.dp,
                        color = Color.White.copy(alpha = 0.92f),
                        shape = CircleShape
                    )
            ) {
                if (item.selected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "已选中",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }

        // 中央视频半透明播放图标
        if (item.isVideo) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.50f))
                    .border(1.dp, Color.White.copy(alpha = 0.85f), CircleShape)
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
                text = if (item.isLivePhoto) "LIVE" else item.format,
                color = Color.White.copy(alpha = 0.88f),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * 全屏多图左右滑动无损预览灯箱（支持双指/双击缩放、视频缓冲播放、实况动图预览、单张保存与直链复制）
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaLightboxPagerDialog(
    items: List<MediaItem>,
    initialPage: Int,
    platform: PlatformType,
    customCookie: String?,
    onDismiss: () -> Unit,
    onToggleSelect: (Int) -> Unit,
    onSaveSingleItem: ((MediaItem) -> Unit)?,
    onCopyUrl: ((String) -> Unit)?
) {
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { items.size })
    val currentItem = items.getOrNull(pagerState.currentPage) ?: return
    var currentScale by remember(pagerState.currentPage) { mutableFloatStateOf(1f) }
    var playingLiveId by remember { mutableStateOf<String?>(null) }

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
                .background(Color.Black.copy(alpha = 0.96f))
        ) {
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = currentScale <= 1.05f && playingLiveId == null,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val pageItem = items[page]
                val isPlayingLive = pageItem.isLivePhoto && playingLiveId == pageItem.id
                LightboxPageContent(
                    item = pageItem,
                    platform = platform,
                    customCookie = customCookie,
                    isPlayingLive = isPlayingLive,
                    onScaleChanged = { newScale ->
                        if (page == pagerState.currentPage) currentScale = newScale
                    },
                    onStopLive = { playingLiveId = null }
                )
            }

            // 顶部信息与关闭栏
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
                            imageVector = when {
                                currentItem.isVideo -> Icons.Default.Videocam
                                currentItem.isLivePhoto -> Icons.Default.MotionPhotosAuto
                                else -> Icons.Default.ZoomIn
                            },
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = buildString {
                                append("${pagerState.currentPage + 1}/${items.size}")
                                append(" · ")
                                when {
                                    currentItem.isVideo -> append("无水印视频")
                                    currentItem.isLivePhoto -> append("实况 Live")
                                    else -> append("缩放 ${String.format("%.1fx", currentScale)}")
                                }
                                append(" · ${currentItem.resolutionLabel}")
                            },
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

            // 底部操作卡片
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF0F172A).copy(alpha = 0.94f)
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 22.dp, top = 8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp)
                        ) {
                            Text(
                                text = "第 ${currentItem.index + 1} 项 · ${
                                    if (currentItem.isLivePhoto) "实况动态照片" else "${currentItem.format} 无损原片"
                                }",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "${currentItem.resolutionLabel} · ${currentItem.colorSpace}",
                                color = Color.White.copy(alpha = 0.72f),
                                fontSize = 11.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // 若为实况图片，提供动图播放切换按钮
                        if (currentItem.isLivePhoto && !currentItem.liveVideoUrl.isNullOrBlank()) {
                            val isPlaying = playingLiveId == currentItem.id
                            FilledTonalIconButton(
                                onClick = {
                                    playingLiveId = if (isPlaying) null else currentItem.id
                                },
                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = if (isPlaying) Color(0xFF059669) else Color.White.copy(alpha = 0.16f),
                                    contentColor = Color.White
                                )
                            ) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Default.StopCircle else Icons.Default.MotionPhotosAuto,
                                    contentDescription = "播放实况"
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                        }

                        if (onCopyUrl != null) {
                            FilledTonalIconButton(
                                onClick = { onCopyUrl(currentItem.originUrl) },
                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = Color.White.copy(alpha = 0.15f),
                                    contentColor = Color.White
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "复制原链",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                        }

                        if (onSaveSingleItem != null) {
                            FilledTonalIconButton(
                                onClick = { onSaveSingleItem(currentItem) },
                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = Color(0xFF2563EB),
                                    contentColor = Color.White
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Download,
                                    contentDescription = "单独保存此项",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                        }

                        Button(
                            onClick = { onToggleSelect(currentItem.index) },
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (currentItem.selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    Color.White.copy(alpha = 0.22f)
                                }
                            ),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                            modifier = Modifier.height(38.dp)
                        ) {
                            Text(
                                text = if (currentItem.selected) "已选 ✓" else "勾选",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.5.sp,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LightboxPageContent(
    item: MediaItem,
    platform: PlatformType,
    customCookie: String?,
    isPlayingLive: Boolean,
    onScaleChanged: (Float) -> Unit,
    onStopLive: () -> Unit
) {
    val context = LocalContext.current
    var scale by remember(item.id) { mutableFloatStateOf(1f) }
    var offset by remember(item.id) { mutableStateOf(Offset.Zero) }
    var highResBitmap by remember(item.id) { mutableStateOf<Bitmap?>(null) }
    var localVideoFile by remember(item.id, isPlayingLive) { mutableStateOf<File?>(null) }
    var videoError by remember(item.id, isPlayingLive) { mutableStateOf(false) }

    val targetVideoUrl = when {
        item.isVideo -> item.originUrl
        isPlayingLive -> item.liveVideoUrl
        else -> null
    }

    LaunchedEffect(item.id) {
        if (!item.isVideo) {
            highResBitmap = DynamicMediaRenderer.loadPreviewBitmap(
                item = item,
                platform = platform,
                customCookie = customCookie,
                maxDimension = 2048
            )
        }
    }

    LaunchedEffect(targetVideoUrl) {
        localVideoFile = null
        videoError = false
        if (!targetVideoUrl.isNullOrBlank()) {
            val cached = DynamicMediaRenderer.downloadToCacheFile(
                context = context,
                urlStr = targetVideoUrl,
                platform = platform,
                customCookie = customCookie,
                extension = "mp4",
                timeoutMs = 25_000
            )
            if (cached != null) {
                localVideoFile = cached
            } else {
                videoError = true
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (targetVideoUrl != null) {
            val file = localVideoFile
            when {
                file != null -> {
                    AndroidView(
                        factory = { ctx ->
                            VideoView(ctx).apply {
                                val controller = MediaController(ctx)
                                controller.setAnchorView(this)
                                if (!isPlayingLive) {
                                    setMediaController(controller)
                                }
                                setVideoURI(Uri.fromFile(file))
                                setOnPreparedListener { mp ->
                                    mp.isLooping = true
                                    start()
                                }
                                setOnErrorListener { _, _, _ ->
                                    videoError = true
                                    true
                                }
                                if (isPlayingLive) {
                                    setOnCompletionListener { onStopLive() }
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.Center)
                    )
                }

                videoError -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.align(Alignment.Center)
                    ) {
                        Icon(
                            imageVector = Icons.Default.BrokenImage,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.65f),
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "在线视频预览受限，请直接点击保存或复制直链",
                            color = Color.White.copy(alpha = 0.78f),
                            fontSize = 12.5.sp
                        )
                    }
                }

                else -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.align(Alignment.Center)
                    ) {
                        CircularProgressIndicator(color = Color.White)
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = if (isPlayingLive) "正在缓冲实况动态轨…" else "正在缓冲无水印视频流…",
                            color = Color.White.copy(alpha = 0.78f),
                            fontSize = 12.sp
                        )
                    }
                }
            }
        } else {
            highResBitmap?.let { bmp ->
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(item.id) {
                            detectTapGestures(
                                onDoubleTap = {
                                    if (scale > 1.1f) {
                                        scale = 1f
                                        offset = Offset.Zero
                                    } else {
                                        scale = 2.4f
                                    }
                                    onScaleChanged(scale)
                                }
                            )
                        }
                        .pointerInput(item.id) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                val nextScale = (scale * zoom).coerceIn(1f, 4.5f)
                                scale = nextScale
                                onScaleChanged(nextScale)
                                offset = if (nextScale > 1.05f) {
                                    Offset(
                                        x = (offset.x + pan.x).coerceIn(-800f, 800f),
                                        y = (offset.y + pan.y).coerceIn(-1000f, 1000f)
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
    }
}
