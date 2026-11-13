package com.puresnap.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.puresnap.app.model.PlatformType

/**
 * 国内外全平台能力矩阵面板（支持一键进入对应平台的 WebView 登录或浏览嗅探）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupportedPlatformsSheet(
    onDismiss: () -> Unit,
    onOpenPlatformWebView: (url: String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val domestic = PlatformType.domesticPlatforms
    val international = PlatformType.internationalPlatforms

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Public,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "全平台解析支持矩阵",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "覆盖 ${domestic.size} 大国内平台与 ${international.size} 大海外平台 + 任意网页/原图直链",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 12.dp)
            ) {
                item {
                    SectionHeaderPill(
                        label = "国内主流平台 (${domestic.size})",
                        accent = MaterialTheme.colorScheme.primary
                    )
                }
                items(domestic, key = { "dom_${it.name}" }) { platform ->
                    PlatformCapabilityRow(
                        platform = platform,
                        onOpenWeb = {
                            onDismiss()
                            onOpenPlatformWebView(defaultEntryUrlFor(platform))
                        }
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(6.dp))
                    SectionHeaderPill(
                        label = "国外主流平台 (${international.size})",
                        accent = Color(0xFF0D9488)
                    )
                }
                items(international, key = { "intl_${it.name}" }) { platform ->
                    PlatformCapabilityRow(
                        platform = platform,
                        onOpenWeb = {
                            onDismiss()
                            onOpenPlatformWebView(defaultEntryUrlFor(platform))
                        }
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(4.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Language,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "支持直接粘贴含 .jpg / .png / .webp / .heic / .mp4 的文件直链，或一次性粘贴多条链接启动批量队列解析。",
                                fontSize = 11.5.sp,
                                lineHeight = 16.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeaderPill(label: String, accent: Color) {
    Surface(
        color = accent.copy(alpha = 0.12f),
        shape = CircleShape
    ) {
        Text(
            text = label,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Bold,
            color = accent,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun PlatformCapabilityRow(
    platform: PlatformType,
    onOpenWeb: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenWeb() }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(platform.brandColor)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = platform.displayName,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            color = platform.brandColor.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = platform.capabilityHint,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = platform.brandColor,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = platform.hostKeywords.take(3).joinToString(" · "),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            IconButton(onClick = onOpenWeb, modifier = Modifier.size(34.dp)) {
                Icon(
                    imageVector = Icons.Default.OpenInBrowser,
                    contentDescription = "在内置浏览器打开",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

private fun defaultEntryUrlFor(platform: PlatformType): String {
    return when (platform) {
        PlatformType.XIAOHONGSHU -> "https://www.xiaohongshu.com"
        PlatformType.DOUYIN -> "https://www.douyin.com"
        PlatformType.KUAISHOU -> "https://www.kuaishou.com"
        PlatformType.WEIBO -> "https://m.weibo.cn"
        PlatformType.BILIBILI -> "https://www.bilibili.com"
        PlatformType.ZUIYOU -> "https://www.izuiyou.com"
        PlatformType.PIPIXIA -> "https://h5.pipix.com"
        PlatformType.INSTAGRAM -> "https://www.instagram.com"
        PlatformType.TIKTOK -> "https://www.tiktok.com"
        PlatformType.TWITTER -> "https://x.com"
        PlatformType.THREADS -> "https://www.threads.net"
        PlatformType.PINTEREST -> "https://www.pinterest.com"
        PlatformType.YOUTUBE -> "https://www.youtube.com"
        PlatformType.REDDIT -> "https://www.reddit.com"
        PlatformType.UNKNOWN -> "https://www.google.com"
    }
}
