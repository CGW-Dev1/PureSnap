# PureSnap —— 多平台无水印原图/实况图/视频提取工具

<p align="left">
  <img src="https://img.shields.io/badge/Platform-Android%2010%2B%20(API%2029%2B)-2563EB?logo=android&logoColor=white" alt="Android 10+" />
  <img src="https://img.shields.io/badge/UI-Jetpack%20Compose%20%2B%20Material%203-0284C7?logo=jetpackcompose&logoColor=white" alt="Jetpack Compose" />
  <img src="https://img.shields.io/badge/Language-Kotlin%202.0-4F46E5?logo=kotlin&logoColor=white" alt="Kotlin 2.0" />
  <img src="https://img.shields.io/badge/Release-v1.2.0-2563EB" alt="Release v1.2.0" />
</p>

**PureSnap** 是一款基于 **Material Design 3** 与 **Jetpack Compose** 构建的 Android 原生系统级无感原画提取工具，采用**皇家宝石蓝与冰川蓝（Sapphire & Glacier Blue）**现代卡片设计体系。专注于解决社交平台去水印工具常见的「画质二次压缩、广告弹窗泛滥、跨应用频繁切换」三大痛点，提供**极短操作路径、真原画质无损提取、多图集高效批量管理与零广告清爽交互**。

---

## ✨ 产品核心定位与亮点

- **🎯 真·原图直出（无损画质）**
  直接解析目标平台 CDN 原始媒体流直链，剥离小红书/抖音/最右等平台的缩放与水印后缀（如 `?imageView2/...`），保留原始分辨率、色彩空间（Display P3 / sRGB）以及**小红书实况图（Live Photo）**动态视频流。
- **⚡ 原生级无感交互（半屏即用即走）**
  - **系统分享面板直达（Share Sheet Overlay）**：注册系统 `ACTION_SEND`，在小红书/抖音点击「分享 → 更多 → PureSnap · 无损提取」时，直接在当前界面底部拉起透明半屏解析抽屉，一键保存后自动下滑关闭，无缝返回原应用。
  - **剪贴板灵动感知（Smart Paste Banner）**：切回前台时自动识别剪贴板中的分享口令，顶部滑出带微震动反馈的灵动胶囊提示条，支持一键触发解析。
- **🎨 宝石蓝现代卡片 UI（Sapphire Blue Design）**
  采用皇家宝石蓝与冰川蓝三层立体卡片（24dp 圆角外卡 + 冰蓝凹槽输入区 + 宝石蓝渐变胶囊按钮）、等宽对称悬浮双栏胶囊导航栏、流光骨架屏（Shimmer Skeleton）与平滑变形下载进度按钮（Morphing Button）。

---

## 🧩 核心功能矩阵

| 模块 | 功能点 | 优先级 | 详细说明与代码实现 |
|---|---|---|---|
| **链接输入与识别** | 口令清洗与正则提取 | P0 | 自动从包含乱码、文案、表情符号的分享口令中剥离短链（[`ShareLinkExtractor.kt`](app/src/main/java/com/puresnap/app/util/ShareLinkExtractor.kt)） |
| | 剪贴板智能感知 | P0 | 切回前台自动识别平台口令并弹出灵动胶囊提示条（[`SmartPasteBanner.kt`](app/src/main/java/com/puresnap/app/ui/components/SmartPasteBanner.kt)） |
| | 系统分享半屏抽屉 | P0 | 透明 Activity 承载 `ModalBottomSheet` 半屏极速解析与自动收起（[`ShareOverlayActivity.kt`](app/src/main/java/com/puresnap/app/ShareOverlayActivity.kt)） |
| **多平台解析引擎** | 多平台覆盖 | P0 | 支持 **小红书、抖音、最右、快手**，并兼容皮皮虾、微博、Bilibili 短链提取（[`MultiPlatformParserEngine.kt`](app/src/main/java/com/puresnap/app/data/parser/MultiPlatformParserEngine.kt)） |
| | 媒体类型全覆盖 | P0 | 支持多图集（最高 30+ 张）、单视频、小红书实况图（Live Photo 拆解为原图 + 高清 MP4）、BGM 原声音频提取 |
| | 原图格式转换引擎 | P1 | 针对 WebP/HEIC 兼容性痛点，支持「兼容模式（100% 质量 JPG）」、「保持 CDN 原格式」与「无损 PNG」三种模式 |
| **预览与下载管理** | 九宫格选集与滑动多选 | P0 | 默认全选，支持双指缩放高清预览、视频在线播放、长按滑动连续多选（Drag-to-Select）、全选/反选（[`MediaGridPreview.kt`](app/src/main/java/com/puresnap/app/ui/components/MediaGridPreview.kt)） |
| | 配套信息一键提取 | P1 | 支持独立复制原帖标题、纯净正文文案、话题标签（Tags），以及下载无水印封面图与作者高清头像 |
| | 并发下载与相册归档 | P0 | 4 线程并发下载，利用 Android 10+ `MediaStore.IS_PENDING` 机制按平台自动建立子目录（如 `Pictures/PureSnap/Xiaohongshu`）（[`MediaStoreArchiver.kt`](app/src/main/java/com/puresnap/app/util/MediaStoreArchiver.kt)） |
| **辅助与历史工具** | 本地解析历史库 | P1 | 基于 Room 自动缓存历史解析记录（含原链接、文案、无损直链），支持平台筛选与关键词搜索（[`HistoryBottomSheet.kt`](app/src/main/java/com/puresnap/app/ui/screens/HistoryBottomSheet.kt)） |
| | MD5 修改去重与提醒 | P2 | 支持保存时微调文件末尾字节生成全新唯一 MD5 哈希值（适合二创去重），以及已下载链接重复提醒 |

---

## 🏗️ 技术架构与工程目录

```text
PureSnap/
├── app/
│   └── src/main/java/com/puresnap/app/
│       ├── data/
│       │   ├── local/        # Room 数据库 (历史解析记录 + 本地归档媒体库)
│       │   ├── parser/       # 多平台真实直链解析引擎 + 隐藏式 WebView DOM 兜底提取器
│       │   └── preferences/  # 用户偏好设置与云端动态规则配置存储
│       ├── model/            # 核心领域模型 (MediaItem, ParsedPostResult, PlatformType 等)
│       ├── ui/
│       │   ├── components/   # 九宫格预览、变形下载按钮、流光骨架屏、剪贴板灵动胶囊
│       │   ├── screens/      # 工作台 (Workbench)、瀑布流媒体库 (Gallery)、历史库与设置抽屉
│       │   └── theme/        # PureSnap 皇家宝石蓝与冰川蓝配色与排版规范
│       ├── util/             # 短链口令清洗、CDN 图像渲染、MediaStore 并发归档与 MD5 微调
│       ├── MainActivity.kt   # 主工作台与悬浮双栏等宽胶囊导航容器
│       └── ShareOverlayActivity.kt # 系统分享面板半屏透明抽屉 Activity
└── cloud-rule-worker/        # 配套云端动态规则与短链重定向解析服务 (Python FastAPI)
```

---

## 📦 下载与安装

请前往右侧 **[Releases](../../releases)** 页面下载最新构建的 Android 安装包：

- **最新安装包 (`v1.2.0`)**：`PureSnap-v1.2.0.apk`（支持 Android 10 / API 29 及以上设备）

### 本地编译构建

1. 确保本地已安装 **JDK 17** 与 **Android SDK (API 35)**。
2. 在项目根目录执行：
   ```powershell
   .\gradlew.bat testDebugUnitTest assembleDebug assembleRelease
   ```
3. 编译产物位于 `app/build/outputs/apk/release/app-release.apk`。

---

## ⚖️ 免责声明

本工具仅提供公开社交平台网页端原始媒体资源的个人学习、欣赏与备份便利。请尊重原作者的著作权与知识产权，切勿将提取的图片、视频、实况及音频用于任何未经授权的商业用途。
