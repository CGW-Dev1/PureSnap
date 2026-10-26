package com.puresnap.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.MotionPhotosOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.puresnap.app.data.preferences.AppPreferencesRepository
import com.puresnap.app.data.preferences.UserPreferencesState
import com.puresnap.app.model.ExportFormatMode
import com.puresnap.app.model.LivePhotoExportMode

/**
 * 全局设置与高阶反爬配置抽屉
 * 包含：
 * - 原图格式转换引擎（兼容模式 100% JPG / 无损 PNG / 保持原格式）
 * - 实况图（Live Photo）导出策略
 * - 剪贴板智能感知开关
 * - MD5 修改与去重提醒开关（P2）
 * - Material You 动态壁纸取色与深浅色主题切换
 * - 云端动态规则节点与个人 Cookie 高阶配置
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsBottomSheet(
    preferences: UserPreferencesState,
    prefsRepo: AppPreferencesRepository,
    onDismiss: () -> Unit,
    onOpenWebViewAssist: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scrollState = rememberScrollState()

    var cloudEndpoint by remember(preferences.cloudApiEndpoint) { mutableStateOf(preferences.cloudApiEndpoint) }
    var xhsCookie by remember(preferences.xhsCookie) { mutableStateOf(preferences.xhsCookie) }
    var douyinCookie by remember(preferences.douyinCookie) { mutableStateOf(preferences.douyinCookie) }
    var zuiyouCookie by remember(preferences.zuiyouCookie) { mutableStateOf(preferences.zuiyouCookie) }
    var xhsCdnHost by remember(preferences.xhsCdnHostRule) { mutableStateOf(preferences.xhsCdnHostRule) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f)
                .verticalScroll(scrollState)
                .padding(horizontal = 18.dp)
                .padding(bottom = 36.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "PureSnap 引擎与偏好设置",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = "当前动态规则版本：${preferences.dynamicRuleVersion} · Android 10+ MediaStore 零权限直出",
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 1. 原图格式转换引擎 (P1 & 第五章原图格式兼容性防呆)
            SectionCard(
                icon = Icons.Default.HighQuality,
                title = "原图格式转换引擎（兼容性防呆）"
            ) {
                ExportFormatMode.entries.forEach { mode ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { prefsRepo.setExportFormatMode(mode) }
                            .padding(vertical = 6.dp)
                    ) {
                        RadioButton(
                            selected = preferences.exportFormatMode == mode,
                            onClick = { prefsRepo.setExportFormatMode(mode) }
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Column {
                            Text(
                                text = mode.title,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.5.sp
                            )
                            Text(
                                text = mode.subtitle,
                                fontSize = 11.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 2. 实况照片 (Live Photo) 导出设置
            SectionCard(
                icon = Icons.Default.MotionPhotosOn,
                title = "实况照片 (Live Photo) 默认导出方式"
            ) {
                LivePhotoExportMode.entries.forEach { mode ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { prefsRepo.setLivePhotoExportMode(mode) }
                            .padding(vertical = 5.dp)
                    ) {
                        RadioButton(
                            selected = preferences.livePhotoExportMode == mode,
                            onClick = { prefsRepo.setLivePhotoExportMode(mode) }
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Column {
                            Text(
                                text = mode.title,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.5.sp
                            )
                            Text(
                                text = mode.subtitle,
                                fontSize = 11.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 3. 灵动交互与二创 MD5 修改去重 (P0 & P2)
            SectionCard(
                icon = Icons.Default.Fingerprint,
                title = "智能感知与 MD5 二创去重（P2）"
            ) {
                SwitchPreferenceRow(
                    title = "剪贴板智能感知（Smart Paste Banner）",
                    subtitle = "App 切回前台时自动识别小红书/抖音/最右口令并弹出灵动胶囊提示条",
                    checked = preferences.smartClipboardEnabled,
                    onCheckedChange = { prefsRepo.setSmartClipboardEnabled(it) }
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                SwitchPreferenceRow(
                    title = "保存时自动修改文件 MD5 值（适合二创用户）",
                    subtitle = "写入相册前微调文件末尾非关键字节，生成全新唯一 MD5 哈希值且不损画质",
                    checked = preferences.modifyMd5Enabled,
                    onCheckedChange = { prefsRepo.setModifyMd5Enabled(it) }
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                SwitchPreferenceRow(
                    title = "已下载链接重复提醒",
                    subtitle = "当解析此前已归档过的作品链接时，在解析卡片顶部显示温和提醒",
                    checked = preferences.duplicateReminderEnabled,
                    onCheckedChange = { prefsRepo.setDuplicateReminderEnabled(it) }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 4. Material Design 3 视觉与动态壁纸取色
            SectionCard(
                icon = Icons.Default.ColorLens,
                title = "Material Design 3 外观与动态取色"
            ) {
                SwitchPreferenceRow(
                    title = "Android 12+ 动态壁纸取色（Dynamic Color）",
                    subtitle = "默认使用 PureSnap 经典宝石蓝主题，开启后根据系统壁纸自动变色",
                    checked = preferences.dynamicColorEnabled,
                    onCheckedChange = { prefsRepo.setDynamicColorEnabled(it) }
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val modes = listOf(0 to "跟随系统", 1 to "浅色模式", 2 to "深色模式")
                    modes.forEach { (modeCode, label) ->
                        val isSelected = preferences.darkThemeMode == modeCode
                        Surface(
                            shape = CircleShape,
                            color = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            border = BorderStroke(
                                width = 1.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp)
                                .clip(CircleShape)
                                .clickable { prefsRepo.setDarkThemeMode(modeCode) }
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 12.5.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 5. 云端动态规则节点与反爬 Cookie 兜底通道（第四章 & 第五章）
            SectionCard(
                icon = Icons.Default.CloudSync,
                title = "混合解析节点与反爬风控兜底（高阶设置）"
            ) {
                OutlinedTextField(
                    value = xhsCdnHost,
                    onValueChange = { xhsCdnHost = it },
                    label = { Text("小红书无损原图 CDN 节点规则") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = cloudEndpoint,
                    onValueChange = { cloudEndpoint = it },
                    label = { Text("自定义云端解析服务地址 (FastAPI / Workers，可选)") },
                    placeholder = { Text("https://api.yourdomain.com/v1/parse") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = xhsCookie,
                    onValueChange = { xhsCookie = it },
                    label = { Text("小红书个人 Cookie（反爬风控兜底，可选）") },
                    placeholder = { Text("web_session=...; a1=...") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = douyinCookie,
                    onValueChange = { douyinCookie = it },
                    label = { Text("抖音个人 Cookie（反爬风控兜底，可选）") },
                    placeholder = { Text("ttwid=...; msToken=...") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedButton(
                        onClick = {
                            onDismiss()
                            onOpenWebViewAssist()
                        },
                        shape = CircleShape,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.VerifiedUser,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = "WebView 验证",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }

                    Button(
                        onClick = {
                            prefsRepo.updateAdvancedConfig(
                                cloudApiEndpoint = cloudEndpoint,
                                xhsCookie = xhsCookie,
                                douyinCookie = douyinCookie,
                                zuiyouCookie = zuiyouCookie,
                                xhsCdnHostRule = xhsCdnHost
                            )
                            onDismiss()
                        },
                        shape = CircleShape,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                    ) {
                        Text(
                            text = "保存配置",
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
}

@Composable
private fun SectionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.5.sp),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun SwitchPreferenceRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.5.sp
            )
            Text(
                text = subtitle,
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}
