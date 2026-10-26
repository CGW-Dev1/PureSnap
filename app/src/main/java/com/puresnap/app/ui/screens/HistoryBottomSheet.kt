package com.puresnap.app.ui.screens

import android.graphics.Bitmap
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.puresnap.app.data.local.ParseHistoryEntity
import com.puresnap.app.model.PlatformType
import com.puresnap.app.util.DynamicMediaRenderer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * P1：本地解析历史库抽屉（Local Parse History Database）
 * 自动保存历史解析记录（含原链接、文案、图集元数据缓存），支持按平台标签筛选与关键词搜索，防止原帖被删后无法找回。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryBottomSheet(
    historyList: List<ParseHistoryEntity>,
    onDismiss: () -> Unit,
    onRestoreRecord: (ParseHistoryEntity) -> Unit,
    onDeleteRecord: (Long) -> Unit,
    onClearAll: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var searchQuery by remember { mutableStateOf("") }
    var selectedPlatform by remember { mutableStateOf("ALL") }
    val dateFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

    val filteredHistory = remember(historyList, searchQuery, selectedPlatform) {
        historyList.filter { item ->
            val matchPlatform = selectedPlatform == "ALL" || item.platform == selectedPlatform
            val q = searchQuery.trim().lowercase()
            val matchQuery = q.isEmpty() ||
                item.title.lowercase().contains(q) ||
                item.content.lowercase().contains(q) ||
                item.authorName.lowercase().contains(q) ||
                item.tagsJson.lowercase().contains(q) ||
                item.extractedUrl.lowercase().contains(q)
            matchPlatform && matchQuery
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.84f)
                .padding(horizontal = 18.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "本地解析历史库 (${historyList.size})",
                            style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.5.sp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "已缓存原帖文案与无损直链，原帖被删亦可一键找回",
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (historyList.isNotEmpty()) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.75f),
                        modifier = Modifier
                            .width(74.dp)
                            .height(34.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onClearAll)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxHeight()
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteSweep,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "清空",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 关键词搜索框
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("搜索标题、正文文案、作者或话题标签...", fontSize = 13.sp) },
                leadingIcon = {
                    Icon(imageVector = Icons.Default.Search, contentDescription = null)
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(imageVector = Icons.Default.Close, contentDescription = "清除")
                        }
                    }
                },
                singleLine = true,
                shape = CircleShape,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 平台标签筛选（统一使用精致等高胶囊）
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 2.dp)
            ) {
                UniformFilterCapsuleChip(
                    label = "全部 (${historyList.size})",
                    selected = selectedPlatform == "ALL",
                    onClick = { selectedPlatform = "ALL" }
                )
                PlatformType.entries.filter { it != PlatformType.UNKNOWN }.forEach { platform ->
                    UniformFilterCapsuleChip(
                        label = platform.displayName,
                        selected = selectedPlatform == platform.name,
                        accentColor = platform.brandColor,
                        onClick = { selectedPlatform = platform.name }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (filteredHistory.isEmpty()) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    Text(
                        text = "未找到匹配的本地解析历史记录",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 32.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    items(filteredHistory, key = { it.id }) { record ->
                        HistoryItemCard(
                            record = record,
                            dateFormat = dateFormat,
                            onRestoreRecord = onRestoreRecord,
                            onDeleteRecord = onDeleteRecord,
                            onDismiss = onDismiss
                        )
                    }
                }
            }
        }
    }
}

/**
 * 历史卡片设计：完全对齐主页提取卡片的高级视觉风格
 * - 纯净高质感 Card（surface 浅白背景 + 细腻微浮雕边框，彻底告别灰白色水泥块）
 * - 顶部显示作者主页圆形头像、创作者名称 @作者、平台品牌高亮胶囊与删除按钮
 * - 中部微凹槽内嵌文案卡片 + 媒体封面原图缩略图
 * - 底部清晰展示时间与一键载入工作台灵动胶囊
 */
@Composable
private fun HistoryItemCard(
    record: ParseHistoryEntity,
    dateFormat: SimpleDateFormat,
    onRestoreRecord: (ParseHistoryEntity) -> Unit,
    onDeleteRecord: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val platform = runCatching { PlatformType.valueOf(record.platform) }
        .getOrDefault(PlatformType.XIAOHONGSHU)

    var avatarBitmap by remember(record.authorAvatarUrl) { mutableStateOf<Bitmap?>(null) }
    var coverBitmap by remember(record.coverUrl) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(record.authorAvatarUrl) {
        if (record.authorAvatarUrl.isNotBlank()) {
            avatarBitmap = DynamicMediaRenderer.loadAvatarBitmap(record.authorAvatarUrl, platform)
        }
    }

    val effectiveCoverUrl = remember(record.coverUrl, record.mediaItemsJson) {
        if (record.coverUrl.isNotBlank()) record.coverUrl
        else {
            runCatching {
                val array = com.google.gson.JsonParser.parseString(record.mediaItemsJson).asJsonArray
                if (array.size() > 0) {
                    val obj = array[0].asJsonObject
                    obj.get("previewUrl")?.asString?.ifBlank { null }
                        ?: obj.get("originUrl")?.asString?.ifBlank { null }
                } else null
            }.getOrNull().orEmpty()
        }
    }

    LaunchedEffect(effectiveCoverUrl) {
        if (effectiveCoverUrl.isNotBlank()) {
            coverBitmap = DynamicMediaRenderer.loadThumbnailBitmap(effectiveCoverUrl, platform)
        }
    }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                shape = RoundedCornerShape(20.dp)
            )
            .clickable {
                onRestoreRecord(record)
                onDismiss()
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            // 1. 顶部作者栏与平台标签（仿主页解析卡片设计）
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // 作者主页头像
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .border(
                            width = 1.2.dp,
                            color = platform.brandColor.copy(alpha = 0.45f),
                            shape = CircleShape
                        )
                ) {
                    if (avatarBitmap != null) {
                        Image(
                            bitmap = avatarBitmap!!.asImageBitmap(),
                            contentDescription = record.authorName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            tint = platform.brandColor,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(9.dp))

                // 作者名称
                Text(
                    text = if (record.authorName.isNotBlank()) "@${record.authorName}" else "@创作者",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(6.dp))

                // 平台品牌药丸胶囊
                Surface(
                    color = platform.brandColor.copy(alpha = 0.12f),
                    shape = CircleShape,
                    modifier = Modifier.height(26.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.padding(horizontal = 9.dp)
                    ) {
                        Text(
                            text = record.postTypeLabel.ifBlank { platform.displayName },
                            color = platform.brandColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                // 删除小图标胶囊
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .clickable { onDeleteRecord(record.id) }
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "删除记录",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 2. 中部微凹槽内嵌文案卡片 + 媒体封面原图缩略图
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp)
                ) {
                    // 若有封面或预览图，展示精致小圆角封面
                    if (effectiveCoverUrl.isNotBlank() || coverBitmap != null) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(54.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .border(
                                    width = 0.6.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                    shape = RoundedCornerShape(10.dp)
                                )
                        ) {
                            if (coverBitmap != null) {
                                Image(
                                    bitmap = coverBitmap!!.asImageBitmap(),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Image,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        val displayCaption = record.title.ifBlank { record.content }.ifBlank { "无标题作品" }
                        Text(
                            text = displayCaption,
                            fontSize = 12.5.sp,
                            lineHeight = 17.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = record.extractedUrl,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 3. 底部栏：左侧解析时间 + 右侧共 X 项与载入胶囊
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Schedule,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = dateFormat.format(Date(record.parsedAtMillis)),
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // 项数胶囊
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                        shape = CircleShape
                    ) {
                        Text(
                            text = "共 ${record.mediaCount} 项",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                            maxLines = 1,
                            softWrap = false
                        )
                    }

                    // 载入工作台胶囊
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable {
                            onRestoreRecord(record)
                            onDismiss()
                        }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Restore,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = "载入",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimary,
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
