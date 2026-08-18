# 萤截 (YingJie)

安卓视频提取工具：粘贴抖音/快手/B站等平台的视频链接或分享口令，一键提取**无水印视频**、**音频**或**文案**。

> 创作者：小星萤

## 功能

| 模式 | 产出 | 说明 |
|---|---|---|
| 🎬 视频 | 无水印 MP4 | 抖音/快手取无水印单文件源流；B站自动合并音视频 |
| 🎵 音频 | 音频文件 (m4a) | 从原视频直接抽音轨，不转码、音质无损 |
| 📝 文案 | 文字 | 作品描述/简介，支持一键复制 + 保存为 TXT |

- 支持从其他 App「分享」链接直接填入
- 文件自动保存到系统 `下载/萤截/` 目录（Android 10+ 无需存储权限）
- 内置下载历史记录（可清空）
- 抖音/快手 Cookie 管理（App 内一键登录抓取，无需电脑）
- iOS 风格毛玻璃界面（Android 12+ 真模糊）
- 首次启动免责声明（强制滑到底部确认）
- 关于页（创作者标识、版本号、联系方式）

## 技术方案

- **解析内核**：内嵌 [yt-dlp](https://github.com/yt-dlp/yt-dlp)（纯 Python，持续更新）
- **Python 运行时**：[Chaquopy](https://chaquo.com/chaquopy/) 16.1.0 + Python 3.12
- **B站音视频合并**：MediaMuxer（系统原生，无需 ffmpeg）
- **App 本体**：Kotlin + 原生 View（零 androidx 依赖）
- **构建**：AGP 8.13 + Gradle 8.14.2，minSdk 26 / targetSdk 35，arm64-v8a

## 更新日志

### v1.8.3
- 修复偶发性「提取失败：AttributeError: module 'extractor' has no attribute 'extract_ison'」
- 根因：Chaquopy AssetFinder 缓存旧版 Python 模块不随 APK 升级刷新
- 方案：App 启动时按 versionCode 自动清除旧缓存，强制从 APK 重新解压最新模块

### v1.8.2
- 免责声明改为强制滑到底部才能「同意」；新增「拒绝」按钮，点击直接退出

### v1.8.1
- 新增「关于」页（创作者标识、版本号、QQ、GitHub 主页）

### v1.8.0
- 新增首次启动免责声明（同意后才能进入）

### v1.7.1
- 毛玻璃美化：彩色渐变背景 + 半透明玻璃卡片 + Android 12+ 真模糊

### v1.7.0
- 标题栏/输入框/底部栏毛玻璃化

### v1.6.0
- 更换应用图标（自适应图标）

### v1.5.1
- 清理调试代码，修复抖音 Cookie 过期提示

### v1.5.0
- 界面美化（深蓝 + 金色主题）
- 使用帮助页内容更新
- 抖音/快手 Cookie 一键登录抓取

### v1.0
- 首个版本：抖音/快手/B站 视频/音频/文案提取

## 目录结构

```
yingjie/
├── app/
│   ├── build.gradle.kts            # AGP + Chaquopy + Kotlin 配置
│   └── src/main/
│       ├── python/extractor.py     # 解析/下载核心（JSON 交互 + 进度回调）
│       ├── java/com/yingjie/app/
│       │   ├── MainActivity.kt     # 主界面（三模式流程）
│       │   ├── DisclaimerActivity.kt # 首次启动免责声明
│       │   ├── AboutActivity.kt    # 关于页
│       │   ├── HelpActivity.kt     # 使用帮助
│       │   ├── HistoryActivity.kt  # 历史记录
│       │   ├── LoginActivity.kt    # Cookie 一键登录抓取
│       │   ├── Extractor.kt        # Python 调用封装
│       │   ├── MuxerHelper.kt      # B站音视频合并（MediaMuxer）
│       │   ├── SaveUtil.kt         # MediaStore 保存
│       │   ├── HistoryDb.kt        # 历史记录（SQLite）
│       │   ├── FrostedGlass.kt     # 毛玻璃效果
│       │   └── YingJieApp.kt       # PyApplication 初始化
│       └── res/                    # 布局、主题、图标
├── build.gradle.kts                # 根配置（构建目录重定向）
├── settings.gradle.kts
├── gradle.properties               # aapt2 override（Termux arm64）
└── local.properties                # sdk.dir（本地配置，不提交）
```

## 构建（Termux arm64 环境）

```bash
export ANDROID_HOME=$HOME/android-sdk
export YINGJIE_BUILD_DIR=$HOME/yingjie-build   # 必须：共享存储不支持符号链接
gradle assembleDebug --project-cache-dir=$HOME/yingjie-gradle-cache
```

产出 APK：`$YINGJIE_BUILD_DIR/app/outputs/apk/debug/app-debug.apk`

> 注意：修改 Python 源码后需 `clean` 全量构建（FUSE 增量检测不可靠）。

## 免责声明

本工具仅供个人学习、研究、欣赏等合法用途使用。请遵守《中华人民共和国著作权法》及相关法律法规、各平台用户协议，勿用于侵犯他人合法权益的行为。详见应用内首次启动免责声明。
