package com.puresnap.app.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.puresnap.app.data.local.DownloadedMediaEntity
import com.puresnap.app.model.MediaItem
import com.puresnap.app.model.MediaKind
import com.puresnap.app.model.PlatformType
import com.puresnap.app.model.QualityBadge
import com.puresnap.app.util.DynamicMediaRenderer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tab 2：媒体库（Gallery）
 * 负责本地已下载图集/视频/音频的分类瀑布流浏览（LazyVerticalStaggeredGrid）与批量管理。
 */
@Composable
fun GalleryScreen(
    downloadedMedia: List<DownloadedMediaEntity>,
    onDeleteSelectedIds: (List<Long>) -> Unit,
    onJumpToWorkbench: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var selectedPlatformFilter by remember { mutableStateOf("ALL") }
    var selectedKindFilter by remember { mutableStateOf("ALL") }
    var batchMode by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<Long>() }
    var inspectingEntity by remember { mutableStateOf<DownloadedMediaEntity?>(null) }

    val filteredList = remember(downloadedMedia, selectedPlatformFilter, selectedKindFilter) {
        downloadedMedia.filter { item ->
            val matchPlatform = selectedPlatformFilter == "ALL" || item.platform == selectedPlatformFilter
            val matchKind = selectedKindFilter == "ALL" || item.mediaKind == selectedKindFilter
            matchPlatform && matchKind
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .padding(top = 10.dp)
    ) {
        // 顶部栏：左侧标题与右侧等大胶囊按钮严格处在同一水平中心线
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 10.dp)
            ) {
                Text(
                    text = "无损媒体库",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold
                    ),
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    softWrap = false
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "已归档 ${downloadedMedia.size} 项原片 · 按平台自动分目录存储",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.5.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (batchMode) {
                    val allSelected = filteredList.isNotEmpty() && selectedIds.size == filteredList.size
                    // 「全选/取消」与「完成」采用完全一致的等宽等高胶囊规格 (68.dp × 34.dp)
                    GalleryTopActionCapsule(
                        label = if (allSelected) "取消" else "全选",
                        isPrimary = false,
                        onClick = {
                            if (allSelected) {
                                selectedIds.clear()
                            } else {
                                selectedIds.clear()
                                selectedIds.addAll(filteredList.map { it.id })
                            }
                        }
                    )
                    GalleryTopActionCapsule(
                        label = "完成",
                        isPrimary = true,
                        onClick = {
                            batchMode = false
                            selectedIds.clear()
                        }
                    )
                } else if (downloadedMedia.isNotEmpty()) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 2.dp,
                        modifier = Modifier
                            .height(34.dp)
                            .clip(CircleShape)
                            .border(
                                width = 0.8.dp,
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f),
                                shape = CircleShape
                            )
                            .clickable { batchMode = true }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(horizontal = 13.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Checklist,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = "批量管理",
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
        }

        Spacer(modifier = Modifier.height(6.dp))

        // 平台分类筛选栏（统一高度、精致无重边框胶囊）
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 3.dp)
        ) {
            UniformFilterCapsuleChip(
                label = "全部平台",
                selected = selectedPlatformFilter == "ALL",
                onClick = { selectedPlatformFilter = "ALL" }
            )
            PlatformType.entries.filter { it != PlatformType.UNKNOWN }.forEach { platform ->
                UniformFilterCapsuleChip(
                    label = platform.displayName,
                    selected = selectedPlatformFilter == platform.name,
                    accentColor = platform.brandColor,
                    onClick = { selectedPlatformFilter = platform.name }
                )
            }
        }

        // 媒体类型筛选栏（统一高度、精致胶囊）
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 3.dp)
        ) {
            val kindFilters = listOf(
                "ALL" to "全部类型",
                MediaKind.IMAGE.name to "无损原图",
                MediaKind.VIDEO.name to "高清视频",
                MediaKind.AUDIO.name to "提取音频"
            )
            kindFilters.forEach { (key, title) ->
                UniformFilterCapsuleChip(
                    label = title,
                    selected = selectedKindFilter == key,
                    onClick = { selectedKindFilter = key }
                )
            }
        }

        // 批量管理操作横幅
        if (batchMode && selectedIds.isNotEmpty()) {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "已选 ${selectedIds.size} 项原片",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1,
                        softWrap = false
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 等大胶囊：「分享」与「删除」
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier
                                .width(78.dp)
                                .height(32.dp)
                                .clip(CircleShape)
                                .border(
                                    width = 0.8.dp,
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                                    shape = CircleShape
                                )
                                .clickable {
                                    val uris = ArrayList<Uri>(
                                        filteredList.filter { selectedIds.contains(it.id) }
                                            .mapNotNull { runCatching { Uri.parse(it.mediaStoreUri) }.getOrNull() }
                                    )
                                    if (uris.isNotEmpty()) {
                                        val shareIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                                            type = "*/*"
                                            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        runCatching {
                                            context.startActivity(Intent.createChooser(shareIntent, "分享无损原片"))
                                        }
                                    }
                                }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "分享",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }

                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .width(78.dp)
                                .height(32.dp)
                                .clip(CircleShape)
                                .clickable {
                                    onDeleteSelectedIds(selectedIds.toList())
                                    selectedIds.clear()
                                    batchMode = false
                                }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DeleteOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onError,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "删除",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onError,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        if (filteredList.isEmpty()) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(bottom = 90.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 28.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.PhotoLibrary,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
                            modifier = Modifier.size(36.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "媒体库暂无对应归档原片",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "在「工作台」点击保存选中原图后，文件将按平台子目录展示在此处",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 12.5.sp,
                            lineHeight = 18.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(18.dp))
                    Button(
                        onClick = onJumpToWorkbench,
                        shape = CircleShape,
                        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 10.dp)
                    ) {
                        Text("前往工作台提取原片", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        } else {
            // 自适应双列瀑布流布局 (LazyVerticalStaggeredGrid)
            LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Fixed(2),
                verticalItemSpacing = 10.dp,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(top = 2.dp, bottom = 110.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                items(filteredList, key = { it.id }) { entity ->
                    val isSelected = selectedIds.contains(entity.id)
                    GalleryWaterfallCard(
                        entity = entity,
                        batchMode = batchMode,
                        isSelected = isSelected,
                        onClick = {
                            if (batchMode) {
                                if (isSelected) selectedIds.remove(entity.id)
                                else selectedIds.add(entity.id)
                            } else {
                                inspectingEntity = entity
                            }
                        },
                        onLongClick = {
                            if (!batchMode) {
                                batchMode = true
                                selectedIds.add(entity.id)
                            }
                        }
                    )
                }
            }
        }
    }

    // 媒体详情与 MD5 校验弹窗
    inspectingEntity?.let { entity ->
        GalleryItemDetailDialog(
            entity = entity,
            onDismiss = { inspectingEntity = null },
            onDelete = {
                onDeleteSelectedIds(listOf(entity.id))
                inspectingEntity = null
            }
        )
    }
}

/**
 * 媒体库顶栏统一尺寸胶囊按钮（全选 / 完成 保持完全一致的宽高 68.dp × 34.dp，绝不竖排换行）
 */
@Composable
private fun GalleryTopActionCapsule(
    label: String,
    isPrimary: Boolean,
    onClick: () -> Unit
) {
    val bgColor = if (isPrimary) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surface
    }
    val textColor = if (isPrimary) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Surface(
        shape = CircleShape,
        color = bgColor,
        tonalElevation = if (isPrimary) 0.dp else 2.dp,
        modifier = Modifier
            .width(68.dp)
            .height(34.dp)
            .clip(CircleShape)
            .then(
                if (!isPrimary) {
                    Modifier.border(
                        width = 0.8.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.24f),
                        shape = CircleShape
                    )
                } else {
                    Modifier
                }
            )
            .clickable(onClick = onClick)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            Text(
                text = label,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Bold,
                color = textColor,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

/**
 * 现代高质感筛选胶囊组件（替代生硬粗边框的默认 FilterChip，所有筛选胶囊等高 32.dp、圆润一致）
 */
@Composable
fun UniformFilterCapsuleChip(
    label: String,
    selected: Boolean,
    accentColor: Color? = null,
    onClick: () -> Unit
) {
    val activeColor = accentColor ?: MaterialTheme.colorScheme.primary
    val bgColor by animateColorAsState(
        targetValue = if (selected) activeColor else MaterialTheme.colorScheme.surface,
        label = "chip_bg"
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "chip_fg"
    )

    Surface(
        shape = CircleShape,
        color = bgColor,
        tonalElevation = if (selected) 2.dp else 1.dp,
        modifier = Modifier
            .height(32.dp)
            .widthIn(min = 72.dp)
            .clip(CircleShape)
            .border(
                width = if (selected) 0.dp else 0.8.dp,
                color = if (selected) Color.Transparent else MaterialTheme.colorScheme.outline.copy(alpha = 0.18f),
                shape = CircleShape
            )
            .clickable(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 12.dp)
        ) {
            if (selected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
            } else if (accentColor != null) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(accentColor)
                )
                Spacer(modifier = Modifier.width(5.dp))
            }
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = contentColor,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GalleryWaterfallCard(
    entity: DownloadedMediaEntity,
    batchMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val context = LocalContext.current
    val platform = remember(entity.platform) {
        runCatching { PlatformType.valueOf(entity.platform) }.getOrDefault(PlatformType.XIAOHONGSHU)
    }
    var previewBmp by remember(entity.id) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(entity.id, entity.mediaStoreUri) {
        if (entity.mediaKind != MediaKind.AUDIO.name) {
            previewBmp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val uri = Uri.parse(entity.mediaStoreUri)
                    if (entity.mediaKind == MediaKind.VIDEO.name) {
                        val retriever = android.media.MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(context, uri)
                            retriever.getFrameAtTime(0)
                        } finally {
                            runCatching { retriever.release() }
                        }
                    } else {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            android.graphics.BitmapFactory.decodeStream(input)
                        }
                    }
                } catch (_: Exception) {
                    null
                }
            } ?: run {
                val dummyItem = MediaItem(
                    id = "gallery_${entity.id}",
                    index = (entity.id % 10).toInt(),
                    kind = runCatching { MediaKind.valueOf(entity.mediaKind) }.getOrDefault(MediaKind.IMAGE),
                    badge = QualityBadge.RAW,
                    originUrl = entity.title,
                    previewUrl = entity.title,
                    width = entity.width.coerceAtLeast(1080),
                    height = entity.height.coerceAtLeast(1440),
                    format = entity.format
                )
                DynamicMediaRenderer.loadPreviewBitmap(
                    item = dummyItem,
                    platform = platform,
                    maxDimension = 640
                )
            }
        }
    }

    // 自适应瀑布流高宽比
    val cardAspect = remember(entity.id) {
        if (entity.mediaKind == MediaKind.AUDIO.name) 1.35f
        else if (entity.id % 3L == 0L) 0.72f
        else if (entity.id % 2L == 0L) 0.80f
        else 0.86f
    }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (isSelected) 2.2.dp else 0.6.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline.copy(alpha = 0.14f),
                shape = RoundedCornerShape(20.dp)
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(cardAspect)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            ) {
                if (entity.mediaKind == MediaKind.AUDIO.name) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Icon(
                            imageVector = Icons.Default.AudioFile,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(42.dp)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "无损提取原声 BGM",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    previewBmp?.let { bmp ->
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = entity.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                // 仅在底部保留轻量渐变遮罩，保持原片通透亮丽
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.65f)
                                )
                            )
                        )
                )

                // 左上角平台标签
                Surface(
                    color = platform.brandColor.copy(alpha = 0.92f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                ) {
                    Text(
                        text = platform.shortTag,
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                // 右上角勾选框或类型徽标
                if (batchMode) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .size(23.dp)
                            .clip(CircleShape)
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primary
                                else Color.Black.copy(alpha = 0.45f)
                            )
                            .border(1.5.dp, Color.White, CircleShape)
                    ) {
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                } else if (entity.mediaKind == MediaKind.VIDEO.name) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.55f),
                        shape = CircleShape,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Videocam,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier
                                .padding(5.dp)
                                .size(14.dp)
                        )
                    }
                }

                // 底部格式与大小信息
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = if (entity.width > 0) "${entity.width}×${entity.height}" else "Audio",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false
                    )
                    Text(
                        text = "${entity.format} · ${entity.formattedFileSize}",
                        color = Color.White.copy(alpha = 0.92f),
                        fontSize = 9.5.sp,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp)) {
                Text(
                    text = if (entity.title.startsWith("http") || entity.title.isBlank()) entity.fileName else entity.title,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.5.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(3.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "@${entity.authorName}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (entity.md5Modified) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "MD5已改",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GalleryItemDetailDialog(
    entity: DownloadedMediaEntity,
    onDismiss: () -> Unit,
    onDelete: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "原片归档与 MD5 详情",
                        style = MaterialTheme.typography.titleMedium
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "关闭")
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(12.dp)
                    ) {
                        DetailRow("原作者", "@${entity.authorName}")
                        DetailRow("分辨率/格式", "${entity.width}×${entity.height} · ${entity.format}")
                        DetailRow("文件大小", entity.formattedFileSize)
                        DetailRow("保存时间", dateFormat.format(Date(entity.downloadedAtMillis)))

                        Row(verticalAlignment = Alignment.Top) {
                            Icon(
                                imageVector = Icons.Default.FolderOpen,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = entity.relativePath,
                                fontSize = 11.5.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Fingerprint,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "MD5: ${entity.md5Hash} ${if (entity.md5Modified) "(已去重微调)" else "(原始校验)"}",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedButton(
                        onClick = onDelete,
                        shape = CircleShape,
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp)
                    ) {
                        Icon(imageVector = Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("移除记录", maxLines = 1, softWrap = false)
                    }
                    Button(
                        onClick = onDismiss,
                        shape = CircleShape,
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp)
                    ) {
                        Text("确定", maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
