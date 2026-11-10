# AGENTS.md — TxtReaderApp

## 交互偏好

- 语言:始终使用中文回复，代码注释与文档同步使用中文。
- 行动优先:用户提出明确操作指令时，默认直接通过工具执行，而非仅列出操作步骤。
- 精炼交付:回答直接聚焦核心结果与关键决策，避免重复输出无需解释的通用背景。

## 操作与安全底线

- 先读后改:修改已有文件前，读取完成本次修改所需的文件与上下文;大型文件按需读取相关范围，避免无关全量读取，严禁盲写覆盖。
- 增量安全:除当前任务授权的目标文件外，保持其他文件与目录原样。
- 删除守卫:严禁自主推断并执行文件或目录的永久删除(rm、清空目录等);仅在用户当前轮次提示词中明确指定了具体删除对象与路径时方可执行。
- 环境隔离:Python优先使用当前激活的环境(Conda base/uv);Node.js遵循当前激活的nvm 版本;严禁全局提权安装可能污染系统的包

## 构建与运行

只在build模式时进行构建验证，plan模式不进行构建验证

android-studio中有JDK 21,地址在`D:\softwares\android-studio\jbr\bin`

```bash
# Windows
.\gradlew.bat :app:assembleDebug

# macOS/Linux
./gradlew :app:assembleDebug
```

无测试套件。`assembleDebug` 是唯一的验证方式。

## 执行流程

1. 根据需求给出详细计划，并确认任务细节
2. 根据任务进行成具体的代码执行
3. 验证测试，如果有报错则进行修复
4. 如果更新了相关功能逻辑或新增文档，更新`AGENTS.md`

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
| androidx.media                  | 1.7.0        |
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
│       │   │   ├── ChapterParser.kt      # 逐行扫描，正则分章逻辑：trim 后整行任意位置匹配判定标题
│       │   │   ├── Paginator.kt          # 基于 android.text.StaticLayout 的分页（UI和TTS共用）
│       │   │   ├── EncodingReader.kt     # BOM检测，UTF-8严格 → GBK降级
│       │   │   └── BookContent.kt        # 书籍模型 + BookSearcher 全文搜索
│       │   ├── data/                     # 持久化层
│       │   │   ├── db/
│       │   │   │   ├── BookRecord.kt     # Room 实体，filePath 为主键，含 groupId
│       │   │   │   ├── BookGroup.kt      # Room 实体，分组表（id 自增主键、groupName 唯一）
│       │   │   │   ├── BookDao.kt        # DAO，Flow 查询 + 分组移动/统计
│       │   │   │   ├── BookGroupDao.kt   # 分组 DAO（增删改名、统计回写）
│       │   │   │   └── AppDatabase.kt    # Room 数据库（txtreader.db，version 2 + MIGRATION_1_2）
│       │   │   ├── BookRepository.kt     # 导入/删除/进度/分组 facade
│       │   │   └── SettingsStore.kt      # SharedPreferences（字体、颜色、视口、TTS语速、书架视图模式）
│       │   ├── tts/                      # 语音朗读
│       │   │   ├── TtsController.kt      # 通过 startService 发送指令
│       │   │   └── TtsService.kt         # 前台服务，通知栏控制，EventEmitter 监听
│       │   └── ui/                       # Compose UI
│       │       ├── booklist/             # 书架页（列表/分组视图、搜索、排序、分组、批量删除）
│       │       │   ├── BookListScreen.kt # 书架主界面（视图切换、分组页、分组详情页、各类弹窗）
│       │       │   ├── BookListViewModel.kt # 视图状态、分组卡片推导、懒加载、分组操作
│       │       │   ├── FileRow.kt        # 文件列表项（显示所属分组、修改分组菜单）
│       │       │   ├── GroupRow.kt       # 分组卡片（组名、文件数、重命名/删除菜单）
│       │       │   ├── BookCard.kt       # 文件详情弹窗 BookDetailDialog
│       │       │   ├── GroupCard.kt      # 分组选择弹窗 GroupPickerDialog
│       │       │   ├── ImportTxtScreen.kt / ImportTxtViewModel.kt # 导入全部 txt 页
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

**数据库名**: `txtreader.db`，版本 2，两张表。

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
| `groupId`        | INTEGER NOT NULL  | 所属分组 ID，默认 0 = 未分类    |

**表 `book_groups`**:

| 字段         | 类型                      | 说明                                                         |
| ------------ | ------------------------- | ------------------------------------------------------------ |
| `id`         | INTEGER (PrimaryKey 自增) | 分组 ID，即 `books.groupId` 引用的值                         |
| `groupName`  | TEXT NOT NULL UNIQUE      | 分组名称（唯一索引 `index_book_groups_groupName`，不可重复） |
| `fileNum`    | INTEGER NOT NULL          | 组内书本数量（冗余字段，变更后回写）                         |
| `updateTime` | INTEGER NOT NULL          | 最近一次分组变更时间（同上）                                 |

- 使用 `@Upsert` 按 `filePath` 去重
- 版本 1 → 2 通过 `AppDatabase.MIGRATION_1_2` 手写 DDL 升级（建 `book_groups` 表 + `books` 加 `groupId` 列），**不清空已有书架**；`fallbackToDestructiveMigration()` 仅作未知版本兜底
- "未分类" 是虚拟分组：ID 固定为 `0`，不写入 `book_groups`，数量由 `books` 表实时统计
- `fileNum`/`updateTime` 在新增/删除/移动书本后由 `BookRepository.refreshGroups()` 统一回写

## 功能需求

### 1. 书架页（BookListScreen）

- 显示已导入的书籍列表（文件名、大小、最后阅读时间、章节进度、所属分组）
- 搜索书名
- 按最近阅读/名称排序
- 长按进入批量选择模式
- 批量操作：删除记录（ic_delete_record）、删除文件（ic_delete_file）、批量修改分组（ic_group）
- 每张卡片右上角 ⋮ 菜单：查看详情、修改分组、删除记录、删除文件
- 导入方式：系统文件选择器（ACTION_GET_CONTENT）
- **导入全部 txt**：顶栏 ic_import 图标 + 空状态页文字按钮 → 跳转 `importTxt` 导入页
- **懒加载**：`UiState.visibleCount`（初始 `PAGE_SIZE=20`），`LazyColumn` 只渲染 `books.take(visibleCount)`；
  滚动到倒数第 5 项时 `loadMore()` 追加 20 条（`snapshotFlow` 监听 `layoutInfo`，key 含 `visibleCount`/`books.size`）；
  搜索或排序变化时重置为 20 并 `scrollToItem(0)` 置顶

### 1.1 视图切换与分组

- **视图切换**：顶栏 `ic_view_group`/`ic_view_list` 图标切换列表视图 / 分组视图，模式持久化到
  `SettingsStore.bookViewIsGroup`；切换时清空选择与分页
- **分组视图**（`BookListViewModel.ViewMode.GROUP`）：
  - 顶栏 `ic_group_add` 图标弹出「添加分组」输入框，重名时内联报错"分组已存在"
  - 分组卡片 `GroupRow`：组名 + `N 个文件` + 最近更新时间，⋮ 菜单提供重命名 / 删除分组
  - 删除分组先弹 `AlertDialog` 确认（提示组名与组内文件数，确认后才执行 `vm.deleteGroup`）
  - 顶栏排序菜单复用为 `GroupSortKey`（文件数 / 时间 / 名称 × 升降序），未分类卡片恒排首位
  - 搜索框在此视图过滤分组名；进入分组/返回时自动清空关键词
  - 分组卡片同样懒加载（`groupVisibleCount` + `loadMoreGroups()`）
- **未分类**：虚拟分组，ID 固定 `0`，不入库、不可重命名/删除，卡片由 `books` 实时统计数量
- **分组详情页**（`currentGroupId != null`，页面内状态，非独立路由）：
  - 顶栏显示组名 + 返回按钮（`ic_arrow_back`），内容区首行显示 `组名 · N 个文件`
  - 文件列表与列表视图完全共用 `FileRow`，独立懒加载 `groupBookVisibleCount`
  - 搜索框过滤书名，排序菜单走 `SortKey`
- **修改分组**：`FileRow` ⋮ 菜单「修改分组」→ `GroupPickerDialog` 单选（首项未分类，末项「新增分组」）；
  选中「新增分组」展开输入框，确认按钮变「创建并移动」，`addGroup` 成功返回新分组 ID 后立即把文件移入，
  失败（重名/空名）时输入框内联报错且弹窗保持打开；多选模式顶栏 `ic_group` → 同一弹窗批量移动，确认后清空选择
- 新增/删除/移动书本后由 `BookRepository.refreshGroups()` 回写各分组 `fileNum` 与 `updateTime`
- 重命名分组只改 `book_groups.groupName`（`books.groupId` 存自增 ID，无需级联）；
  删除分组时组内书本自动归入未分类

### 1.2 导入全部 txt 页（ImportTxtScreen，路由 `importTxt`）

- 无权限时显示引导页，点击跳 `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`；`LifecycleResumeEffect` 在返回时重试扫描
- **扫描范围**：常用公共目录（Download/Documents/DCIM/Pictures/Music/Movies/Podcasts/Books/Ringtones/Notifications/Alarms）递归子目录，
  跳过 `Android/` 与 `.` 开头的隐藏项，深度上限 8 层
- **过滤**：仅 `.txt`（忽略大小写）且 `file.length() > 1024`（`MIN_TXT_BYTES`），且**已添加到书架的文件不进列表**（扫描时跳过，
  书架变化时 `removeAddedFiles()` 从列表与勾选集同步移除）
- 列表项：文件名 + 所在目录 + 大小（`ImportFileRow` 不再有置灰态）
- 列表分页展示：`visibleFileCount`（初始 100，滚动到底追加 100）
- 底部操作栏（`navigationBarsPadding()` 避让系统导航栏）：
  - 未导入时：全选 / 取消全选（仅当有勾选时显示）/ 确认（`已选/总数`）
  - 导入中：只显示红色「停止导入」按钮，进度条显示 `n/N`
- **停止导入**：`stopImport()` 取消 `importJob` 并置 `importProgress = null` 恢复可操作；
  协程内进度回写带 `importProgress == null` 守卫，防止取消间隙覆盖；已导入的文件保留
- 确认后按列表顺序逐个导入（`EncodingReader` → `ChapterParser` → `repo.addBook`），
  完成后 `onBack()` 返回书架（Room Flow 自动刷新）

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
- 文本分句分块：先按句号等标点分句（用于高亮），再按每段≤200字分块（用于TTS播放）
- 翻页/内容变化时从头开始朗读

## 重要注意事项

### 颜色处理

**始终使用 `Color(argb.toInt())`**，绝对不要用 `Color(argb.toULong())`。`Color(ULong)` 构造函数期望 Compose 内部编码，不是原始 ARGB。`bgColor`/`textColor` 存储为 `Long`（如 `0xFFFFF9F0`）— 用 `.toInt()` 转换为正确的 ARGB Int。

### 章节正则

- 正则预设按整数索引（0–16）。索引 16 = 自定义正则。
- `ChapterParser.parse()` 逐行匹配（移植 JS 分章逻辑）：全文先移除 `\r`/`\t` 再按 `\n` 切行；每行 **trim 后整行任意位置匹配**判定标题（无行首锚定、无 80 字符长度限制）；匹配到新标题且与当前章节名不同时切章，同名标题行丢弃；**标题行保留为新章节正文首行**（阅读页正文来自 chapterText，无独立标题头部）；`initialTitle`（各调用点传文件名）为初始章节名，正则无匹配时整书为一章。

### Room KSP

Room 编译器使用 KSP（非 kapt）。实体 `BookRecord` 使用 `@Upsert` 按 `filePath` 去重。

### 导航路由

- `booklist` → 书架页（首页）
- `importTxt` → 导入全部 txt 页（ImportTxtScreen，带返回按钮）
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
- `EVENT_TTS_DONE` 由 TtsService 在 `onUtteranceDone()` 时发出，ReaderViewModel 收到后自动翻到下一页并朗读新文本；若当前已是全书最后一页（`hasNextPage()` 为 false），则调用 `stopTts()` 停止朗读、清除高亮并结束前台服务，避免 `seekToGlobal` 钳制到末页后无限重复朗读
- 停止/暂停朗读（`stopTts`/`ttsToggle`/`backPage`/`EVENT_TTS_STOPPED`）以及翻页、章节跳转、进度跳转时，均清除朗读句子高亮（`ttsHighlightIndex=-1`、`ttsHighlightRanges` 置空），避免残留高亮
- TtsService 不进行分页/内容加载，仅朗读当前页面文本
- 通知栏使用 `MediaSessionCompat` + `NotificationCompat.MediaStyle` 媒体通知：标题为 txt 文件名，正文为“第 N 章 / 共 M 章”，按钮为上一章、播放/暂停、下一章
- `TtsController.play()` 通过 Intent extras 传递书名、章节序号、总章节数、文件路径；TtsService 据此刷新 MediaSession 元数据和通知
- 通知栏播放/暂停由 TtsService 直接处理：暂停时保留前台服务和通知，再次播放从当前句子继续；`EVENT_TTS_PAUSED` 通知 ReaderViewModel 同步 UI 状态
- 通知栏上一章/下一章由 TtsService 发出 `EVENT_TTS_PREV_CHAPTER` / `EVENT_TTS_NEXT_CHAPTER`，ReaderViewModel 切换章节后从新章节开头朗读
- 点击通知栏通过 `MainActivity.ACTION_OPEN_READER` + `EXTRA_FILE_PATH` 回到阅读页；同一文件不会再次调用 `OpenBookStore.open()`，已在阅读页时不重建 ViewModel、不重新 load 文件，朗读从原进度继续
- TtsService 通过 `onStartCommand` 接收 PendingIntent 通知栏按钮指令；服务返回 `START_NOT_STICKY`，避免进程重建后残留无内容的通知
- `TtsBus` 已完全移除，UI 层 TTS 状态直接从 `ReaderUiState` 读取
