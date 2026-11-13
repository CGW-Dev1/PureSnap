package com.puresnap.app.ui.screens

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.MotionPhotosAuto
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.puresnap.app.data.local.DownloadedMediaEntity
import com.puresnap.app.util.ExifPrivacyHelper
import com.puresnap.app.util.MediaStoreArchiver
import com.puresnap.app.util.MotionPhotoSynthesizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * PureSnap 实用工具箱（离线无损工作台）：
 * 1. 相片/视频 MD5 去重改写（零重压缩改写合规元数据段）
 * 2. EXIF 相机信息查看与 GPS 位置隐私无损擦除
 * 3. Android 原生实况照片 (.MP.jpg) 合成与拆分提取
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UtilityToolboxSheet(
    modifyMd5Default: Boolean,
    onDismiss: () -> Unit,
    onEntityCreated: (DownloadedMediaEntity) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var md5ResultText by remember { mutableStateOf<String?>(null) }
    var md5Processing by remember { mutableStateOf(false) }

    var exifUri by remember { mutableStateOf<Uri?>(null) }
    var exifReport by remember { mutableStateOf<ExifPrivacyHelper.ExifReport?>(null) }
    var exifProcessing by remember { mutableStateOf(false) }
    var exifCleanedResult by remember { mutableStateOf<String?>(null) }

    var synthImageUri by remember { mutableStateOf<Uri?>(null) }
    var synthVideoUri by remember { mutableStateOf<Uri?>(null) }
    var motionProcessing by remember { mutableStateOf(false) }
    var motionResultText by remember { mutableStateOf<String?>(null) }

    // Launcher 1: 选择图片/视频修改 MD5
    val md5Picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                md5Processing = true
                md5ResultText = null
                val entity = MediaStoreArchiver.duplicateLocalMediaWithNewMd5(context, uri)
                md5Processing = false
                if (entity != null) {
                    onEntityCreated(entity)
                    md5ResultText = "已生成零损新文件：${entity.relativePath}\n新 MD5: ${entity.md5Hash}"
                    Toast.makeText(context, "MD5 改写完成并已入库", Toast.LENGTH_SHORT).show()
                } else {
                    md5ResultText = "改写失败：请确认选择有效图片或 MP4 视频"
                }
            }
        }
    }

    // Launcher 2: 选择图片检测 EXIF
    val exifPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            exifUri = uri
            exifCleanedResult = null
            scope.launch {
                exifProcessing = true
                exifReport = ExifPrivacyHelper.inspectExif(context, uri)
                exifProcessing = false
            }
        }
    }

    // Launcher 3: 选择实况合成静态图
    val synthImgPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) synthImageUri = uri
    }

    // Launcher 4: 选择实况合成短视频
    val synthVidPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) synthVideoUri = uri
    }

    // Launcher 5: 选择动态照片进行拆分
    val splitMotionPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                motionProcessing = true
                motionResultText = null
                val msg = splitAndSaveMotionPhoto(context, uri)
                motionProcessing = false
                motionResultText = msg
            }
        }
    }

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
                    imageVector = Icons.Default.Build,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "实用影像工具箱",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "全本地离线处理 · 原画像素零二次压缩",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 8.dp)
            ) {
                // ── 工具 1：本地文件无损 MD5 改写 ──
                item {
                    ToolboxSectionCard(
                        icon = Icons.Default.Fingerprint,
                        title = "相片 / 视频无损 MD5 改写",
                        subtitle = "保持 100% 原始分辨率与画质不变，仅修改合规元数据/容器末尾实现去重过审",
                        accentColor = Color(0xFF2563EB)
                    ) {
                        Button(
                            onClick = { md5Picker.launch(arrayOf("image/*", "video/*")) },
                            enabled = !md5Processing,
                            shape = CircleShape,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (md5Processing) {
                                CircularProgressIndicator(
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("正在生成新 MD5 副本…")
                            } else {
                                Text("选择相册图片 / 视频生成新 MD5 副本")
                            }
                        }

                        md5ResultText?.let { msg ->
                            Spacer(modifier = Modifier.height(8.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = msg,
                                    fontSize = 11.5.sp,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }
                    }
                }

                // ── 工具 2：EXIF 摄影参数检查与 GPS 隐私抹除 ──
                item {
                    ToolboxSectionCard(
                        icon = Icons.Default.PrivacyTip,
                        title = "EXIF 相机参数查看与隐私擦除",
                        subtitle = "检测照片内嵌经纬度定位与拍摄机型参数，支持一键生成无痕隐私纯净原图",
                        accentColor = Color(0xFF0D9488)
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(
                                onClick = { exifPicker.launch(arrayOf("image/*")) },
                                enabled = !exifProcessing,
                                shape = CircleShape,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("读取照片 EXIF", fontSize = 12.5.sp)
                            }

                            if (exifUri != null) {
                                Button(
                                    onClick = {
                                        val src = exifUri ?: return@Button
                                        scope.launch {
                                            exifProcessing = true
                                            val outUri = ExifPrivacyHelper.createPrivacyCleanedCopy(context, src)
                                            exifProcessing = false
                                            exifCleanedResult = if (outUri != null) {
                                                "✓ 已生成抹除 GPS/机型的无痕原图至 Pictures/PureSnap/Cleaned"
                                            } else {
                                                "隐私抹除失败，请重试"
                                            }
                                        }
                                    },
                                    enabled = !exifProcessing,
                                    shape = CircleShape,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Shield,
                                        contentDescription = null,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("抹除隐私导出", fontSize = 12.5.sp)
                                }
                            }
                        }

                        exifReport?.let { rep ->
                            Spacer(modifier = Modifier.height(10.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(3.dp),
                                    modifier = Modifier.padding(10.dp)
                                ) {
                                    Text(
                                        text = "设备：${rep.cameraMakeModel} · ${rep.lensModel}",
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = "参数：${rep.exposureInfo} · ${rep.dimensions}",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "时间：${rep.dateTaken}",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "定位：${rep.gpsSummary}",
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (rep.hasGpsLocation) MaterialTheme.colorScheme.error else Color(0xFF059669)
                                    )
                                }
                            }
                        }

                        exifCleanedResult?.let { res ->
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = res,
                                fontSize = 11.5.sp,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                // ── 工具 3：Android 实况照片 (.MP.jpg) 合成与拆包 ──
                item {
                    ToolboxSectionCard(
                        icon = Icons.Default.MotionPhotosAuto,
                        title = "实况动态照片 (.MP.jpg) 合成与拆包",
                        subtitle = "自选 1 张图片 + 1 段短视频合成安卓系统相册原生实况；或将实况拆分为高清照片与视频",
                        accentColor = Color(0xFF7C3AED)
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { synthImgPicker.launch(arrayOf("image/*")) },
                                shape = CircleShape,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Photo,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (synthImageUri != null) "静态图 ✓" else "选静态封面",
                                    fontSize = 12.sp
                                )
                            }

                            OutlinedButton(
                                onClick = { synthVidPicker.launch(arrayOf("video/mp4", "video/*")) },
                                shape = CircleShape,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Videocam,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (synthVideoUri != null) "短视频 ✓" else "选动态视频",
                                    fontSize = 12.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    val iUri = synthImageUri
                                    val vUri = synthVideoUri
                                    if (iUri == null || vUri == null) {
                                        Toast.makeText(context, "请先同时选择静态图片和短视频", Toast.LENGTH_SHORT).show()
                                        return@Button
                                    }
                                    scope.launch {
                                        motionProcessing = true
                                        motionResultText = null
                                        val msg = synthesizeAndSaveMotionPhoto(
                                            context = context,
                                            imageUri = iUri,
                                            videoUri = vUri,
                                            modifyMd5 = modifyMd5Default
                                        )
                                        motionProcessing = false
                                        motionResultText = msg
                                    }
                                },
                                enabled = !motionProcessing,
                                shape = CircleShape,
                                modifier = Modifier.weight(1.2f)
                            ) {
                                Text("合成 .MP.jpg", fontSize = 12.5.sp)
                            }

                            FilledTonalButton(
                                onClick = { splitMotionPicker.launch(arrayOf("image/*")) },
                                enabled = !motionProcessing,
                                shape = CircleShape,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CallSplit,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("实况拆包", fontSize = 12.5.sp)
                            }
                        }

                        motionResultText?.let { res ->
                            Spacer(modifier = Modifier.height(8.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = res,
                                    fontSize = 11.5.sp,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolboxSectionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accentColor: Color,
    content: @Composable () -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f)
        ),
        border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = accentColor.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.padding(7.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = title,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Text(
                        text = subtitle,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

private suspend fun synthesizeAndSaveMotionPhoto(
    context: Context,
    imageUri: Uri,
    videoUri: Uri,
    modifyMd5: Boolean
): String = withContext(Dispatchers.IO) {
    try {
        val imgBytes = context.contentResolver.openInputStream(imageUri)?.use { it.readBytes() }
            ?: return@withContext "读取图片失败"
        val vidBytes = context.contentResolver.openInputStream(videoUri)?.use { it.readBytes() }
            ?: return@withContext "读取视频失败"

        val mpBytes = MotionPhotoSynthesizer.synthesizeMotionPhoto(imgBytes, vidBytes, modifyMd5)
        val fileName = "PureSnap_Synth_${System.currentTimeMillis()}.MP.jpg"
        val relPath = "${Environment.DIRECTORY_PICTURES}/PureSnap/MotionPhoto"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, relPath)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return@withContext "写入系统相册失败"
        context.contentResolver.openOutputStream(uri)?.use { it.write(mpBytes) }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        context.contentResolver.update(uri, values, null, null)
        MediaScannerConnection.scanFile(context, arrayOf(uri.toString()), null, null)
        "✓ 实况合成为：$relPath/$fileName（支持相册长按播放）"
    } catch (e: Exception) {
        "实况合成失败：${e.localizedMessage ?: "格式不支持"}"
    }
}

private suspend fun splitAndSaveMotionPhoto(context: Context, sourceUri: Uri): String =
    withContext(Dispatchers.IO) {
        try {
            val allBytes = context.contentResolver.openInputStream(sourceUri)?.use { it.readBytes() }
                ?: return@withContext "读取文件失败"
            val split = MotionPhotoSynthesizer.splitMotionPhoto(allBytes)
                ?: return@withContext "所选照片未包含内嵌 Motion Photo 视频流"

            val ts = System.currentTimeMillis()
            val imgValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "PureSnap_Split_$ts.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/PureSnap/MotionSplit")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val imgUri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, imgValues)
            if (imgUri != null) {
                context.contentResolver.openOutputStream(imgUri)?.use { it.write(split.jpegBytes) }
                imgValues.clear()
                imgValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(imgUri, imgValues, null, null)
            }

            val vidValues = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "PureSnap_Split_$ts.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/PureSnap/MotionSplit")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val vidUri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, vidValues)
            if (vidUri != null) {
                context.contentResolver.openOutputStream(vidUri)?.use { it.write(split.videoBytes) }
                vidValues.clear()
                vidValues.put(MediaStore.Video.Media.IS_PENDING, 0)
                context.contentResolver.update(vidUri, vidValues, null, null)
            }
            context.sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE))
            "✓ 拆包成功：已分别保存静态原图 (Pictures/PureSnap/MotionSplit) 与 MP4 短片 (Movies/PureSnap/MotionSplit)"
        } catch (e: Exception) {
            "实况拆包异常：${e.localizedMessage ?: "未知错误"}"
        }
    }
