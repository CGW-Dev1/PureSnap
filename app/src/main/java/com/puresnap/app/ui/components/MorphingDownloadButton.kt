package com.puresnap.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.puresnap.app.ui.MorphDownloadState

/**
 * 变形进度下载按钮（Morphing Progress Button）
 * 点击后按钮自身平滑变形为实时进度条："正在保存原图 (3/6)..."，
 * 完成后变为翡翠绿对勾并伴随清脆的触觉震动。
 */
@Composable
fun MorphingDownloadButton(
    downloadState: MorphDownloadState,
    selectedCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current

    LaunchedEffect(downloadState) {
        if (downloadState is MorphDownloadState.Completed) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    val targetBgColor by animateColorAsState(
        targetValue = when (downloadState) {
            is MorphDownloadState.Idle -> MaterialTheme.colorScheme.primary
            is MorphDownloadState.Downloading -> MaterialTheme.colorScheme.primaryContainer
            is MorphDownloadState.Completed -> Color(0xFF10B981) // 翡翠绿完成态
        },
        animationSpec = tween(280),
        label = "morph_bg_color"
    )

    val progressFraction by animateFloatAsState(
        targetValue = when (downloadState) {
            is MorphDownloadState.Idle -> 0f
            is MorphDownloadState.Downloading -> {
                if (downloadState.total > 0) {
                    (downloadState.completed.toFloat() / downloadState.total.toFloat()).coerceIn(0.08f, 1f)
                } else 0.2f
            }
            is MorphDownloadState.Completed -> 1f
        },
        animationSpec = tween(220),
        label = "morph_progress_fraction"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(46.dp)
            .clip(CircleShape)
            .background(targetBgColor)
            .clickable(enabled = downloadState !is MorphDownloadState.Downloading && selectedCount > 0) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
    ) {
        // 下载中的内部动态填充进度条
        if (downloadState is MorphDownloadState.Downloading) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(progressFraction)
                    .align(Alignment.CenterStart)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.85f))
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp)
        ) {
            when (downloadState) {
                is MorphDownloadState.Idle -> {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "保存选中原图 ($selectedCount)",
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                is MorphDownloadState.Downloading -> {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "正在保存原图 (${downloadState.completed}/${downloadState.total})...",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                is MorphDownloadState.Completed -> {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "已保存 ${downloadState.count} 项至相册 ✓",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
