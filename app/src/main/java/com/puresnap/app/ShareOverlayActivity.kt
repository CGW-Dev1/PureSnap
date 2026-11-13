package com.puresnap.app

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FilterCenterFocus
import androidx.compose.material.icons.filled.MotionPhotosAuto
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.puresnap.app.model.ParseErrorType
import com.puresnap.app.ui.MorphDownloadState
import com.puresnap.app.ui.PureSnapViewModel
import com.puresnap.app.ui.components.MediaGridPreview
import com.puresnap.app.ui.components.MorphingDownloadButton
import com.puresnap.app.ui.components.ShimmerSkeletonCard
import com.puresnap.app.ui.theme.PureSnapTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 系统分享半屏抽屉（Share Sheet Overlay — P0 & 第三章 3.2 核心体验）
 * 当用户在国内外平台点击“分享 → 更多”选择「PureSnap · 无损提取」时：
 * - 以透明 Activity 承载底部 ModalBottomSheet 半屏弹窗，支持实况照片、图集全量预览、视频与背景 BGM 无损直出
 * - 保存完成后自动平滑收起并无缝返回原应用
 */
class ShareOverlayActivity : ComponentActivity() {

    private val viewModel: PureSnapViewModel by viewModels()

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sharedText = extractSharedText(intent)
        if (!sharedText.isNullOrBlank()) {
            viewModel.triggerParse(sharedText, saveToHistory = true)
        }

        setContent {
            val preferences by viewModel.preferences.collectAsState()
            val isParsing by viewModel.isParsing.collectAsState()
            val currentPost by viewModel.currentPost.collectAsState()
            val parseError by viewModel.parseError.collectAsState()
            val downloadState by viewModel.downloadState.collectAsState()
            val bgmSaving by viewModel.bgmSaving.collectAsState()

            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

            PureSnapTheme(
                darkThemeMode = preferences.darkThemeMode,
                dynamicColor = preferences.dynamicColorEnabled
            ) {
                ModalBottomSheet(
                    onDismissRequest = { finish() },
                    sheetState = sheetState,
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 18.dp)
                            .padding(bottom = 28.dp)
                    ) {
                        // 抽屉顶部栏
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
                                        .size(34.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MaterialTheme.colorScheme.primary)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.FilterCenterFocus,
                                        contentDescription = "PureSnap",
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(9.dp))
                                Column {
                                    Text(
                                        text = "PureSnap · 半屏极速解析",
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "保存后将自动收起，无缝返回原应用",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            Row {
                                IconButton(
                                    onClick = {
                                        val launchText = currentPost?.extractedUrl?.ifBlank { null }
                                            ?: currentPost?.sourceShareText?.ifBlank { null }
                                            ?: sharedText.orEmpty()
                                        val mainIntent = Intent(context, MainActivity::class.java).apply {
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                            if (launchText.isNotBlank()) {
                                                putExtra(MainActivity.EXTRA_SHARE_URL, launchText)
                                            }
                                        }
                                        startActivity(mainIntent)
                                        finish()
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                        contentDescription = "在主工作台打开",
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                IconButton(onClick = { finish() }) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "关闭抽屉"
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        when {
                            isParsing -> {
                                ShimmerSkeletonCard()
                            }

                            parseError != ParseErrorType.NONE -> {
                                Surface(
                                    shape = RoundedCornerShape(18.dp),
                                    color = MaterialTheme.colorScheme.errorContainer,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Text(
                                            text = parseError.title,
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = parseError.message,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                    }
                                }
                            }

                            currentPost != null -> {
                                val post = currentPost!!
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Surface(
                                            color = post.platform.brandColor.copy(alpha = 0.15f),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text(
                                                text = post.postTypeLabel,
                                                color = post.platform.brandColor,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "@${post.authorName}",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    TextButton(onClick = viewModel::toggleSelectAllOrInvert) {
                                        Icon(
                                            imageVector = Icons.Default.SelectAll,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "已选 ${post.selectedCount}/${post.totalCount}",
                                            fontSize = 12.5.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                if (post.hasLivePhotos || post.bgmAudio != null) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        if (post.hasLivePhotos) {
                                            Surface(
                                                color = Color(0xFF0D9488).copy(alpha = 0.14f),
                                                shape = CircleShape
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.MotionPhotosAuto,
                                                        contentDescription = null,
                                                        tint = Color(0xFF0D9488),
                                                        modifier = Modifier.size(13.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text(
                                                        text = "含 ${post.livePhotoCount} 张实况照片",
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color(0xFF0D9488)
                                                    )
                                                }
                                            }
                                        }
                                        if (post.bgmAudio != null) {
                                            Surface(
                                                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f),
                                                shape = CircleShape,
                                                modifier = Modifier
                                                    .clip(CircleShape)
                                                    .clickable(enabled = !bgmSaving) {
                                                        viewModel.saveBgmAudio(context)
                                                    }
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.MusicNote,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.secondary,
                                                        modifier = Modifier.size(13.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text(
                                                        text = if (bgmSaving) "保存中…" else "保存 BGM",
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.secondary
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 340.dp)
                                        .verticalScroll(rememberScrollState())
                                ) {
                                    MediaGridPreview(
                                        items = post.mediaItems,
                                        platform = post.platform,
                                        onToggleSelect = viewModel::toggleItemSelection,
                                        onDragSetSelect = viewModel::setItemSelection,
                                        customCookie = preferences.cookieForPlatform(post.platform),
                                        onSaveSingleItem = { item ->
                                            viewModel.downloadSingleMediaItem(context, item)
                                        },
                                        onCopyUrl = { url ->
                                            viewModel.copyTextToClipboard(context, "直链", url)
                                        }
                                    )
                                }

                                HorizontalDivider(
                                    modifier = Modifier.padding(vertical = 14.dp),
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                                )

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            viewModel.copyTextToClipboard(
                                                context,
                                                "原帖文案",
                                                post.fullCopywritingText
                                            )
                                        },
                                        shape = CircleShape,
                                        modifier = Modifier
                                            .weight(0.36f)
                                            .height(46.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ContentCopy,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("复制文案", fontSize = 12.5.sp)
                                    }

                                    MorphingDownloadButton(
                                        downloadState = downloadState,
                                        selectedCount = post.selectedCount,
                                        onClick = {
                                            viewModel.downloadSelectedMedia(context) { savedCount, folderPath ->
                                                val completed = viewModel.downloadState.value as? MorphDownloadState.Completed
                                                val msg = completed?.summaryLabel?.let {
                                                    "✓ $it 至 $folderPath"
                                                } ?: "✓ 已保存 $savedCount 项原片至 $folderPath"
                                                Toast.makeText(
                                                    context,
                                                    msg,
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                                // 保存完成后自动平滑收起半屏抽屉并结束透明 Activity
                                                scope.launch {
                                                    delay(650)
                                                    runCatching { sheetState.hide() }
                                                    finish()
                                                }
                                            }
                                        },
                                        modifier = Modifier.weight(0.64f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun extractSharedText(intent: Intent?): String? {
        if (intent == null) return null
        if (intent.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true) {
            return intent.getStringExtra(Intent.EXTRA_TEXT)
                ?: intent.getStringExtra(Intent.EXTRA_SUBJECT)
        }
        return intent.dataString
    }
}
