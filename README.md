# PureSnap —— 国内外多平台无水印原图 / 实况 / 视频直出提取工具

<p align="left">
  <img src="https://img.shields.io/badge/Platform-Android%2010%2B%20(API%2029%2B)-2563EB?logo=android&logoColor=white" alt="Android 10+" />
  <img src="https://img.shields.io/badge/Target%20SDK-API%2035-0284C7" alt="Target API 35" />
  <img src="https://img.shields.io/badge/UI-Jetpack%20Compose%20%2B%20Material%203-0284C7?logo=jetpackcompose&logoColor=white" alt="Jetpack Compose" />
  <img src="https://img.shields.io/badge/Language-Kotlin%202.0.21-4F46E5?logo=kotlin&logoColor=white" alt="Kotlin 2.0.21" />
  <img src="https://img.shields.io/badge/Release-v1.3.1-2563EB" alt="Release v1.3.1" />
</p>

**PureSnap** 是一款基于 **Material Design 3** 与 **Jetpack Compose** 构建的 Android 原生系统级无损媒体提取与处理工具，采用**皇家宝石蓝与冰川蓝（Sapphire & Glacier Blue）**现代卡片设计（并支持 Material You 动态壁纸取色与浅色/深色主题切换）。专注于解决传统去水印工具「画质二次压缩、底部水印残留、平台覆盖少、跨应用频繁切换」痛点，支持**国内外 14 大社交平台及通用直链**的原图、实况图（Motion Photo `.MP.jpg`）、高清视频与 BGM 原声无损直出。

---

## ✨ 核心亮点与新特性（v1.3.1）

- **🎯 真·原图零重压缩直出**
  - 默认以 `KEEP_ORIGINAL` 模式直接保存目标平台 CDN 原始最高画质文件流，完整保留 Display P3 色域与原始 EXIF 元数据（亦可选 100% 质量兼容 JPG 或无损 PNG）。
  - 支持最高画质 URL 改写与降质参数剥离（如微博 `orj` ➔ `large`、Pinterest `orig`、X/Twitter `name=orig`、小红书去水印原文件直链、快手去降质后缀）。
- **🛡️ 抖音底部水印彻底净化 + 桌面端兜底解析**
  - 优先提取 `origin_image` / `display_image` / `url_list`（`urlList`）及 `watermark_free_download_url_list` 无水印源地址，自动剔除包含 `抖音号：xxx` 的 `download_url_list` / `~tplv-dy-water` 水印直链。
  - 当移动端分享页受限或返回兜底错误页时，自动切换桌面端 `douyin.com/note/<id>` Next.js RSC 数据流解析高分辨率原图；同时对历史记录中的旧水印缓存进行自动净化或重新解析。
- **📸 Android 原生实况照片（Motion Photo `.MP.jpg`）全链路支持**
  - 精准识别小红书、抖音、微博、Threads 等平台的实况图文动态视频轨，支持三种导出模式：合成为 Android 系统相册原生兼容的 **`.MP.jpg` 实况照片**、**静态原图 + MP4 视频双轨保存**、或仅存静态原图（[`MotionPhotoSynthesizer.kt`](app/src/main/java/com/puresnap/app/util/MotionPhotoSynthesizer.kt)）。
- **🧰 内置离线无损实用工具箱**
  - **相片/视频 MD5 去重改写**：不重新编码像素与视频流，仅合规注入 JPEG COM / MP4 `free` box 元数据盐块生成全新 MD5（[`MediaStoreArchiver.kt`](app/src/main/java/com/puresnap/app/util/MediaStoreArchiver.kt)）。
  - **EXIF 检查与 GPS 隐私无损擦除**：查看相机型号、镜头、曝光参数与地理位置，一键生成去除定位等敏感信息的纯净副本（[`ExifPrivacyHelper.kt`](app/src/main/java/com/puresnap/app/util/ExifPrivacyHelper.kt)）。
  - **实况 `.MP.jpg` 合成与拆分**：本地照片 + 视频合成为实况照片，或从现有 `.MP.jpg` 无损拆分出静态 JPG 与动态 MP4。
- **⚡ 极简交互与多链接批量队列**
  - 支持输入框一次性粘贴多条混杂口令与链接并按队列顺序批量解析。
  - **系统分享半屏抽屉（`ShareOverlayActivity`）**：在任意平台点击「分享 ➔ 更多 ➔ PureSnap」，原地拉起半屏解析抽屉，保存即走。
  - **顶部自适应图标工具栏 & 版本角标**：`v1.3.1` 修复大字体/窄屏下标题与工具栏挤压重叠问题，右上角采用等距图标按钮快捷进入工具箱、历史库与设置。

---

## 🌐 支持平台矩阵（14 大国内外平台 + 通用直链）

| 分类 | 平台 | 支持内容与解析能力 | 核心实现 |
|---|---|---|---|
| **国内平台** | **小红书** | 原图、实况图（Live Photo）、4K 视频，支持无水印原文件 CDN 直连 | [`MultiPlatformParserEngine.kt`](app/src/main/java/com/puresnap/app/data/parser/MultiPlatformParserEngine.kt) |
| | **抖音** | 无水印图集（过滤 `tplv-dy-water`）、实况图、最高码率视频、BGM 原声 | [`MultiPlatformParserEngine.kt`](app/src/main/java/com/puresnap/app/data/parser/MultiPlatformParserEngine.kt) |
| | **快手** | 图集原图、高清视频、BGM，支持重定向与状态树多层容灾 | [`MultiPlatformParserEngine.kt`](app/src/main/java/com/puresnap/app/data/parser/MultiPlatformParserEngine.kt) |
| | **微博** | `large` 最高清原图、实况图、高清视频 | [`DomesticExtPlatformParsers.kt`](app/src/main/java/com/puresnap/app/data/parser/DomesticExtPlatformParsers.kt) |
| | **Bilibili** | 动态原图、高清视频流、高清封面 | [`DomesticExtPlatformParsers.kt`](app/src/main/java/com/puresnap/app/data/parser/DomesticExtPlatformParsers.kt) |
| | **最右** | 无水印原图与视频直链提取 | [`DomesticExtPlatformParsers.kt`](app/src/main/java/com/puresnap/app/data/parser/DomesticExtPlatformParsers.kt) |
| | **皮皮虾** | 原图与无水印视频直链提取 | [`DomesticExtPlatformParsers.kt`](app/src/main/java/com/puresnap/app/data/parser/DomesticExtPlatformParsers.kt) |
| **国外平台** | **Instagram** | 原图多图轮播、Reels 视频 | [`InternationalPlatformParsers.kt`](app/src/main/java/com/puresnap/app/data/parser/InternationalPlatformParsers.kt) |
| | **TikTok** | 图集原图、无水印视频、原声音频 | [`InternationalPlatformParsers.kt`](app/src/main/java/com/puresnap/app/data/parser/InternationalPlatformParsers.kt) |
| | **X / Twitter** | `name=orig` 无损原图、GIF、高清视频 | [`InternationalPlatformParsers.kt`](app/src/main/java/com/puresnap/app/data/parser/InternationalPlatformParsers.kt) |
| | **Threads** | 原图、实况图、高清视频 | [`InternationalPlatformParsers.kt`](app/src/main/java/com/puresnap/app/data/parser/InternationalPlatformParsers.kt) |
| | **Pinterest** | `originals` 无损原图、视频 | [`InternationalPlatformParsers.kt`](app/src/main/java/com/puresnap/app/data/parser/InternationalPlatformParsers.kt) |
| | **YouTube** | 4K 高清封面、Shorts / 视频 | [`InternationalPlatformParsers.kt`](app/src/main/java/com/puresnap/app/data/parser/InternationalPlatformParsers.kt) |
| | **Reddit** | 画廊多图原图、高清视频 | [`InternationalPlatformParsers.kt`](app/src/main/java/com/puresnap/app/data/parser/InternationalPlatformParsers.kt) |
| **通用兜底** | **通用直链 / WebView** | 图片/视频直链极速直出 + 隐形沙盒 WebView DOM/API 嗅探 + 辅助登录提取 | [`HiddenWebViewExtractor.kt`](app/src/main/java/com/puresnap/app/data/parser/HiddenWebViewExtractor.kt) |

---

## 🧩 工作流与界面模块

- **双栏悬浮导航**：底部悬浮胶囊切换 **「工作台」**（[`WorkbenchScreen.kt`](app/src/main/java/com/puresnap/app/ui/screens/WorkbenchScreen.kt)）与 **「媒体库」**（[`GalleryScreen.kt`](app/src/main/java/com/puresnap/app/ui/screens/GalleryScreen.kt)）。
- **媒体预览与交互**：默认全选，支持九宫格多选与长按拖拽连续勾选、全屏双指缩放预览、在线视频播放，以及一键复制原帖标题/正文文案/话题标签和保存头像/封面（[`MediaGridPreview.kt`](app/src/main/java/com/puresnap/app/ui/components/MediaGridPreview.kt)）。
- **相册按平台自动归档**：最多 4 并发协程流式写入 Android 10+ `MediaStore`，分别归档至 `Pictures/PureSnap/<Platform>`、`Movies/PureSnap/<Platform>` 与 `Music/PureSnap/<Platform>`，大视频不入内存防 OOM。
- **媒体库 & 历史库管理**：本地瀑布流浏览已归档原片，支持按平台/媒体类型筛选、关键词搜索与批量删除；Room 持久化保存解析历史与多平台 Cookie 凭证（[`HistoryBottomSheet.kt`](app/src/main/java/com/puresnap/app/ui/screens/HistoryBottomSheet.kt)、[`SettingsBottomSheet.kt`](app/src/main/java/com/puresnap/app/ui/screens/SettingsBottomSheet.kt)）。

---

## 🏗️ 技术架构与工程目录

```text
PureSnap/
├── app/
│   └── src/
│       ├── main/java/com/puresnap/app/
│       │   ├── data/
│       │   │   ├── local/        # Room 数据库 (ParseHistoryEntity + DownloadedMediaEntity)
│       │   │   ├── parser/       # 国内外 14 平台直链/状态树解析引擎 + 隐藏式 WebView 嗅探器
│       │   │   └── preferences/  # 用户导出偏好、主题与多平台 Cookie 持久化
│       │   ├── model/            # PlatformType、MediaItem、ParsedPost 等领域模型
│       │   ├── ui/
│       │   │   ├── components/   # 九宫格/全屏预览、变形下载按钮、剪贴板灵动条、WebView 辅助框
│       │   │   ├── screens/      # 工作台、瀑布流媒体库、工具箱、平台矩阵、历史记录与全局设置
│       │   │   └── theme/        # Material 3 宝石蓝主题与动态配色支持
│       │   ├── util/             # 链接提取、CDN 加载缓存、MediaStore 归档、MotionPhoto 合成、EXIF 擦除
│       │   ├── MainActivity.kt   # 主容器与系统分享入口
│       │   └── ShareOverlayActivity.kt # 系统分享面板半屏透明抽屉 Activity
│       └── test/java/com/puresnap/app/
│           └── PureSnapParserTest.kt   # 21 项多平台解析、无水印校验与实况合成单元测试
└── cloud-rule-worker/            # 配套云端动态规则与短链重定向服务 (Python FastAPI)
```

---

## 📦 下载与构建

请前往 **[Releases](../../releases)** 页面下载最新编译的 Android 安装包：

- **最新版本 (`v1.3.1` / Build `5`)**：`PureSnap-v1.3.1.apk`（支持 Android 10 / API 29 及以上设备，Target API 35）

### 本地编译与测试

1. 安装 **JDK 17** 与 **Android SDK (API 35)**。
2. 在项目根目录运行单元测试并打包 Debug / Release APK：
   ```powershell
   .\gradlew.bat testDebugUnitTest --rerun-tasks assembleDebug assembleRelease
   ```
3. Release 安装包输出于：`app/build/outputs/apk/release/app-release.apk`。

---

## ⚖️ 免责声明

本工具仅提供公开社交平台网页端原始媒体资源的个人学习、欣赏与备份便利。请尊重原作者的著作权与知识产权，切勿将提取的图片、实况照片、视频及音频用于任何未经授权的商业用途。
