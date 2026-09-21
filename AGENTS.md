# AGENTS.md — TxtReaderApp

## 构建与运行
android-studio中有JDK 21,地址在`D:\softwares\android-studio\jbr\bin`
```bash
# Windows
.\gradlew.bat :app:assembleDebug

# macOS/Linux
./gradlew :app:assembleDebug
```

无测试套件。`assembleDebug` 是唯一的验证方式。

## 关键技术栈

| 组件                            | 版本         |
| ------------------------------- | ------------ |
| AGP                             | 9.3.0        |
| Kotlin                          | 2.2.10       |
| Compose BOM                     | 2026.02.01   |
| KSP                             | 2.2.10-2.0.2 |
| Room                            | 2.8.4        |
| Navigation Compose              | 2.9.8        |
| Lifecycle                       | 2.8.7        |
| compileSdk / minSdk / targetSdk | 36           |
| Java                            | 11           |

## AGP 9 + KSP 兼容性

`gradle.properties` 中 `android.disallowKotlinSourceSets=false` — **绝对不能删除**。AGP 9.3.0 内置 Kotlin 插件会阻止 KSP 的 source-set DSL。如果看到 `"Using kotlin.sourceSets DSL to add Kotlin sources is not allowed with built-in Kotlin"` 错误，说明此配置丢失。

## 项目目录结构

```
TxtReaderApp/
├── app/
│   ├── build.gradle.kts          # 模块构建配置
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/xld/txtreader/
│       │   ├── MainActivity.kt           # 入口 Activity，导航 + ACTION_VIEW 处理
│       │   ├── TxtReaderApplication.kt   # Application，提供单例（appSettings, appRepositories）
│       │   ├── OpenBookStore.kt          # 单例，传递 filePath 给 ReaderScreen
│       │   ├── core/                     # 领域逻辑层（无 Android UI 依赖）
│       │   │   ├── RegexPresets.kt       # 17 种章节正则预设 + CUSTOM_INDEX=16 哨兵值
│       │   │   ├── ChapterParser.kt      # 逐行扫描，正则必须匹配行首（range.first==0），≤80字符
│       │   │   ├── Paginator.kt          # 基于 android.text.StaticLayout 的分页（UI和TTS共用）
│       │   │   ├── EncodingReader.kt     # BOM检测，UTF-8严格 → GBK降级
│       │   │   └── BookContent.kt        # 书籍模型 + BookSearcher 全文搜索
│       │   ├── data/                     # 持久化层
│       │   │   ├── db/
│       │   │   │   ├── BookRecord.kt     # Room 实体，filePath 为主键
│       │   │   │   ├── BookDao.kt        # DAO，Flow 查询
│       │   │   │   └── AppDatabase.kt    # Room 数据库（txtreader.db）
│       │   │   ├── BookRepository.kt     # 导入/删除/进度 facade
│       │   │   └── SettingsStore.kt      # SharedPreferences（字体、颜色、视口、TTS语速）
│       │   ├── tts/                      # 语音朗读
│       │   │   ├── TtsController.kt      # 通过 startService 发送指令
│       │   │   └── TtsService.kt         # 前台服务，通知栏控制，EventEmitter 监听
│       │   └── ui/                       # Compose UI
│       │       ├── booklist/             # 书架页（搜索、排序、批量删除）
│       │       ├── reader/               # 阅读页（分页、设置、TTS、搜索）
│       │       ├── SettingsUtil.kt       # 系统默认应用设置跳转
│       │       └── theme/                # MaterialTheme
│       └── res/drawable/                 # 自定义矢量图标
├── gradle/
│   └── libs.versions.toml               # 版本目录
├── gradle.properties                     # 构建属性
├── build.gradle.kts                      # 根构建文件
└── AGENTS.md                             # 本文件
```

## 数据库结构

**数据库名**: `txtreader.db`，版本 1，单表。

**表 `books`**:

| 字段             | 类型              | 说明                            |
| ---------------- | ----------------- | ------------------------------- |
| `filePath`       | TEXT (PrimaryKey) | 文件绝对路径                    |
| `currentChapter` | INTEGER           | 当前章节索引                    |
| `pageIndex`      | INTEGER           | 章节内页码                      |
| `totalChapter`   | INTEGER           | 总章节数                        |
| `textNum`        | INTEGER           | 总字符数                        |
| `updateTime`     | INTEGER           | 最后更新时间戳                  |
| `fileName`       | TEXT              | 文件名                          |
| `fileSize`       | INTEGER           | 文件大小                        |
| `regexType`      | INTEGER           | 正则类型索引（0-16，16=自定义） |
| `regexStr`       | TEXT              | 正则表达式字符串                |
| `encodeStr`      | TEXT              | 编码字符串（如 UTF-8、GBK）     |

- 使用 `@Upsert` 按 `filePath` 去重
- 通过 `fallbackToDestructiveMigration()` 处理版本迁移（开发阶段）

## 功能需求

### 1. 书架页（BookListScreen）

- 显示已导入的书籍列表（文件名、大小、最后阅读时间、章节进度）
- 搜索书名
- 按最近阅读/名称排序
- 长按进入批量选择模式
- 批量操作：删除记录（ic_delete_record）、删除文件（ic_delete_file）
- 每张卡片右上角 ⋮ 菜单：查看详情、删除记录、删除文件
- 导入方式：系统文件选择器（ACTION_GET_CONTENT）

### 2. 阅读页（ReaderScreen）

- HorizontalPager 分页阅读
- 字体大小、行间距调节
- 背景色/文字色切换（预设颜色 + 自定义）
- 章节跳转（侧边抽屉）
- 全文搜索（关键词高亮，黄色背景+粗体）
- TTS 朗读（底部控制栏）
- 外部文件打开（ACTION_VIEW，支持 file:// 和 content://）

### 3. TTS 朗读（TtsService）

- 前台服务，通知栏播放/暂停控制
- 句子级高亮标注（蓝色半透明背景）
- 暂停后从暂停位置继续播放
- 当前页播放完毕自动翻到下一页继续播放
- 语速调节
- 文本分句分块：先按句号等标点分句（用于高亮），再按逗号等分块（用于TTS播放粒度≤40字）
- 翻页/内容变化时从头开始朗读

## 开发命令

```bash
# 构建调试版 APK
.\gradlew.bat :app:assembleDebug

# 清理构建
.\gradlew.bat clean

# 无测试套件，assembleDebug 即为验证
```

## 重要注意事项

### 颜色处理

**始终使用 `Color(argb.toInt())`**，绝对不要用 `Color(argb.toULong())`。`Color(ULong)` 构造函数期望 Compose 内部编码，不是原始 ARGB。`bgColor`/`textColor` 存储为 `Long`（如 `0xFFFFF9F0`）— 用 `.toInt()` 转换为正确的 ARGB Int。

### 章节正则

- 正则预设按整数索引（0–16）。索引 16 = 自定义正则。
- `ChapterParser.parse()` 逐行匹配。正则必须在行首匹配（`range.first==0`）。超过 80 字符的行跳过作为正文。

### Room KSP

Room 编译器使用 KSP（非 kapt）。实体 `BookRecord` 使用 `@Upsert` 按 `filePath` 去重。

### 图标

仅使用 `material-icons-core` — 不要添加 `material-icons-extended`。自定义图标使用 `res/drawable/` 下的矢量图（ic_pause, ic_play, ic_sort, ic_chapters, ic_speaker, ic_skip_prev, ic_skip_next, ic_delete_record, ic_delete_file）。

### 导航路由

- `booklist` → 书架页（首页）
- `readerContent` → 阅读页（无参数，通过 `OpenBookStore` 单例传递 filePath）

### 外部文件打开

`ACTION_VIEW` intent（content:// 或 file://）在 `MainActivity.handleOpenIntent()` 中处理。content:// URI 通过 ContentResolver 读取（需要 `MANAGE_EXTERNAL_STORAGE`）。file:// 直接读取。导航使用 `OpenBookStore` 单例传递文件路径（无路由参数）。

### 依赖注入

无 DI 框架。通过 `TxtReaderApplication` 的扩展属性（`appSettings`、`appRepositories`）实现手动单例模式。

### AndroidManifest 权限

- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PLAYBACK`：TTS 前台服务
- `POST_NOTIFICATIONS`：通知栏
- `MANAGE_EXTERNAL_STORAGE`：直接文件访问
- `<queries>` 声明 `android.intent.action.TTS_SERVICE`：Android 11+ TTS 引擎发现

### TTS 架构要点

- TtsService 是纯文本朗读服务，接收 `EVENT_TTS_PLAY` 事件中的整页文本，调用 `tts.speak(text, QUEUE_FLUSH)` 直接朗读
- `TtsController` 通过 `startService(Intent)` 向 TtsService 发送播放指令（含文本和书籍信息）
- `EventEmitter` 传递事件：ReaderViewModel → TtsService（PLAY/PAUSE/STOP/NEXT_PAGE/SPEED等）、TtsService → ReaderViewModel（`EVENT_TTS_DONE`/`EVENT_TTS_START`/`EVENT_TTS_READY`）
- `EVENT_TTS_DONE` 由 TtsService 在 `onUtteranceDone()` 时发出，ReaderViewModel 收到后自动翻到下一页并朗读新文本
- TtsService 不进行分页/内容加载，仅朗读当前页面文本
- 通知栏使用 `CATEGORY_TRANSPORT` + `FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK` 媒体播放通知
- TtsService 通过 `onStartCommand` 接收 PendingIntent 通知栏按钮指令，转换为 `startService` 调用
- `TtsBus` 已完全移除，UI 层 TTS 状态直接从 `ReaderUiState` 读取

### 代码规范

- 代码中不添加注释，除非明确要求
- 使用 AutoMirrored 版本的图标（如 `Icons.AutoMirrored.Filled.ArrowBack`）
- `AnimatedVisibility` 在 Column 作用域内使用全限定名 `androidx.compose.animation.AnimatedVisibility(...)` 避免 ColumnScope 扩展解析问题
