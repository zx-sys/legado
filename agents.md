# Legado 分叉开发约定（给人和 LLM）

本仓库不是上游 Legado 的简单镜像：产品行为、数据归属和已删功能都做过明确取舍。
**同步上游代码前先读本文件、`docs/FORK_REQUIREMENTS.md` 和 `docs/FORK_DECISIONS.md`；与本文件冲突的上游实现不要合回来。**

## 不可变的产品决策

1. **阅读习惯是全局的**
   - 文本阅读的翻页动画、字体、字号、字距、行距、段距、缩进、背景、颜色等属于全局 `ReadBookConfig` / `read_config`，不按书保存。
   - 朗读引擎、音色属于全局 `AppConfig.ttsEngine`；朗读引擎选择页只有“全局”，没有“书”。
   - 不新增、不恢复“每本书一套阅读习惯/朗读引擎”的 UI 或同步。
   - 例外：图片书的翻页动画、音频播放模式/速度、替换规则、模拟阅读起始章节等属于内容级选项，可留在 `Book.readConfig`，但**不参与习惯类同步**。

2. **WebDAV 已移除**
   - 不再使用 WebDAV 做书籍/进度/备份同步；相关类、DAO、实体、菜单、帮助文档、备份项均已删除。
   - 上游重新加入 WebDAV 的提交不要直接合并；如需兼容旧数据，只保留一次性清理逻辑（`LocalConfig.cleanupRemovedPreferences()`）。

3. **线上 TTS 已移除**
   - 不再内置 HTTP/在线 TTS（Edge、百度、阿里、Next 等）及其 `HttpTTS` 实体、DAO、服务、默认数据。
   - 朗读只允许：系统 TTS + 本地 sherpa-onnx 引擎（目录下发，如 melo / Kokoro / Matcha）。
   - 服务端 `TtsEngine` 不再提供 `http` 类型；上游的在线 TTS 代码不要合并。
   - 朗读会话由 `ReadAloudSession` 独立持有，支持边听 A 书边读 B 书；App 内朗读悬浮球只挂在 Activity 内，不申请系统悬浮窗。

4. **备份与数据以本仓库后端为准**
   - 手机号即账号；首次绑定恢复服务端最新备份，之后通过 `/api/legado/app/sync` 实时补进度、书架、设置。
   - 备份（zip）只是保底快照，不是唯一数据源；不要在客户端重新引入 WebDAV/本地密码/其它云盘作为主同步。
   - 服务端：Django + Daphne（端口 9666）+ MySQL + Redis + OSS；不要改回 `server/` 下的 FastAPI 原型。

5. **字体与 TTS 模型是远程目录**
   - APK 不再内置字体文件；字体通过后台 `FontPack` + OSS 字体市场按需下载。
   - TTS 模型不再写死单个 melo 模型；通过 `RemoteTtsEngines` 目录下发、下载、解包、按 `config` 加载。
   - 上游的 `assets/font/presets`、硬编码模型路径、单模型下载逻辑不要合回来。

6. **阅读/朗读交互（合上游必须保留，详见 `docs/FORK_DECISIONS.md` 1.2/1.3）**
   - **两套恢复入口**：菜单里的“朗读/继续”从当前可见位置开始；App 悬浮胶囊继续暂停前的位置。页内浮条的“从这读”也从可见位置开始。
   - **页内浮条**：跟随朗读时隐藏；手动浏览/退出跟随后才显示“回到朗读位置 / 从这读”（菜单收起时），不能因为“拖动定位”开关而隐藏。
   - **高亮**：朗读高亮只改文字颜色（`ReadBookConfig.textAccentColor`），不画黄色/任何背景块；墨水屏用下划线。
   - **手势**：跟随朗读时上下滑动 = 退出跟随并自由滚动/翻页，不做“上下拖动朗读位置”；左右翻页由开关控制。
   - **跟随边界**：手动翻页/滚动先脱离跟随；跟随中通过目录、进度条或上一章/下一章换章时，朗读游标要移到新章节并继续（暂停态保持暂停）；已脱离跟随后换章只翻页面，不动朗读位置。换章加载期间页面不能被旧朗读位置拉回；回前台/解锁时只有仍处于跟随时才同步可见页。
   - **手动脱离锁**：用户滑动/跳章脱离跟随后，朗读进度事件不能自动恢复跟随；只有“回到朗读位置 / 从这读 / 新朗读会话”才清除（`SpeechFollowState.isManuallyDetached`）。
   - **通知/耳机**：通知媒体面板上一首/下一首 = 上一章/下一章；耳机媒体键仍按“媒体按钮上一段/章”开关。
   - **章末**：朗读服务单一接管切章，阅读页不重复 `play`；章节总数取阅读页与会话的较大值；TTS 章末无 `onDone` 时由看门狗自动续章。章节未就绪时启动失败必须调度重试（`scheduleChapterLoadRetry`），不能静默放弃；看门狗按实际最后入队下标武装，允许漏一次最后回调，但完全没开始不得跳章。回归：`ParagraphChapterEndPolicyTest`、`ChapterStopTimerTest`。
   - **脱离跟随高亮**：手动脱离跟随后，若朗读章节/页与当前可见页一致，只重绘文字高亮（`renderDetachedAloudHighlight`），不移动页面、不恢复跟随；回归 `ReadAloudManualPagePolicyTest`。
   - **进度**：阅读页 `onPause` 同步落库；跟随朗读换章强制落库、播放中最多 30 秒落一次；写库单调，旧位置不能覆盖新位置；本地进度比服务端新时启动回传，不是拉取覆盖。
   - **杀后台**（最近任务划掉/强杀）不得回退本地阅读进度。

## 分支需求地图（由提交历史与长期反馈沉淀）

用户的主力使用方式对应的硬需求，合并/重构前逐项核对；完整清单与验收点见 `docs/FORK_REQUIREMENTS.md`。

- **长时段息屏听书 + 高语速本地 TTS**：服务自行跨章、唤醒锁默认开、TTS 缓冲/零写入重试；大模型下载不设 60s 总超时。
- **滚动阅读为主**：首个可见行保存/恢复；`onPause` 同步落库；快速滚动条易点按。
- **整本离线缓存、频繁换源**：前 3 章全速后 2s；分页源并发 4；验证源隐藏 WebView + 限速/静默重试；失败延后并优先同域名补源；换源/刷新/删书停旧队列；空目录不能禁全源；缓存记录实际来源。
- **多端/重装**：手机号账号 + 自建后端；实时 WS + 备份保底；删除墓碑；本地进度较新回传，旧进度不能覆盖。
- **一套全局外观**：文本字体/字号/背景/翻页/朗读引擎全局；图片书仅内容级选项按书；夜间跟随系统并刷新阅读页。
- **远程字体/TTS 市场**：FontPack / RemoteTtsEngines 目录 + OSS；APK 无内置字体、无在线 TTS、无 WebDAV。
- **自定义书源跨设备**：按用户隔离、只同步非目录源、LWW + 墓碑。
- **计量单位**：音频按“集”，朗读按“章”，通知/计时/睡眠定时文案不能混。

依据提交：`8c8f61de7a`、`7876b2596d`、`81ea669612`、`600593854e`、`76ae05836c`、`942b3d30e9`、`0958431355`、`fe753b91de`；交互定稿见决策 6。

## 代码所有权边界

合并上游时，以下区域默认以本仓库为准，冲突时保留本仓库实现：

- 客户端同步：`app/src/main/java/io/legado/app/help/sync/`、`ui/font/`、`ui/tts/`、`help/font/`、`help/tts/`
- 客户端关键行为：`ReadAloud.kt`、`Book.kt` 的 `getPageAnim()/getTtsEngine()`、`SpeakEngineDialog.kt`、`MainActivity.kt`、`BookDao.kt`
- 阅读/朗读交互：`ui/book/read/ReadBookActivity.kt`、`ui/book/read/ReadView.kt`、`ui/book/read/ReadAloudControls.kt`、`ui/book/read/page/entities/`、`service/BaseReadAloudService.kt`、`service/TTSReadAloudService.kt`、`service/NeuralTtsReadAloudService.kt`、`model/ReadBook.kt`、`model/ReadAloud*.kt`
- 后台：`backend/apps/legado/`、`backend/application/asgi.py`、`backend/application/settings.py`
- 管理端：`admin-web/src/views/legado/`

## 同步架构（改同步相关代码前必读）

- HTTP 写 + WebSocket 通知 + HTTP 增量拉取。
- 进度/记录/配置：Redis 热数据 + MySQL 落库；书架/分组/设置：MySQL LWW。
- 删除书架书/分组使用墓碑，旧设备补推不能复活已删除项；重新添加需要更新的时间戳。
- 应用设置按 key 同步，排除设备/凭据/路径/缓存类键；详见 `SettingsSync.kt` 的排除表。
- 阅读中的书不自动应用远端进度，离开阅读后再应用。
- 升级配置结构时递增 `LocalConfig.configSyncVersion`，触发一次全量拉取。
- WS 路由必须同时保留：`/ws/msg/`（管理端通知）和 `/ws/legado/sync/`（App 同步）。

## 分享版构建（数据不上云）

- `app` 的 `ServerDataSync` 由打包参数决定：默认 `false`，即朋友包——书架、进度、阅读记录、自定义书源、服务端备份全部只在本机，系统云备份也关闭。
- 自己的包显式开启：`./gradlew assembleAppDebug -Pserver.sync=true`，或在 `local.properties` 写 `server.sync=true`；release 工作流已传 `true`。
- 朋友包可在“设置 → 备份与恢复”手动开启“仅同步配置”（默认关）：只用手机号同步应用设置、阅读设置和主题，书架/进度/记录不上传。
- 朋友包 AI 清洗由打包参数控制：默认关闭；传 `-PaiPolish=true`（或 `local.properties` 写 `aiPolish=true`）才显示入口和 Key 字段，Key 只存在独立偏好文件，不参与备份、配置同步和系统云备份。自用包继续走服务端清洗。
- 朗读悬浮球在“朗读设置”里由用户开关，默认开启；关闭后立即卸载悬浮球，不再使用打包参数。

### 打包与产物位置（固定）

- 默认只打自用包；朋友包仅在明确要求时再打，不要顺手一起出。
- 自用包命令（arm-only + 118 后端 + 开启数据同步）：
  ```bash
  ./gradlew assembleAppDebug \
    -Pbackup.server.url=http://118.31.79.174:9666 \
    -Pserver.sync=true -ParmOnly=true
  ```
- 产物统一复制到仓库根目录 **`deploy/legado-app-own.apk`**（固定路径、每次覆盖；`deploy/` 已 gitignore，且 `gradlew clean` 不会清掉）。
- 交付前必查：生成的 `app/build/generated/source/buildConfig/app/debug/io/legado/app/BuildConfig.java` 里 `BackupServerUrl` 含 `118.31.79.174` 且 `ServerDataSync = true`；APK 只含 `arm64-v8a`/`armeabi-v7a`。`-P` 没生效时会静默回落到 `local.properties` 的 `10.0.2.2`，真机上同步会超时。

## 合并上游后的最低验证

```bash
# 客户端（自己的包）
./gradlew assembleAppDebug -Pbackup.server.url=http://118.31.79.174:9666 -Pserver.sync=true
# 后台
cd backend && .venv/Scripts/python.exe manage.py check
cd backend && .venv/Scripts/python.exe manage.py makemigrations --check --dry-run
```

并人工确认：

- 文本阅读翻页/字体等全局一致，朗读引擎没有“本书”选项。
- App 内无 WebDAV、无在线 TTS 入口/类/资源。
- 字体市场、TTS 市场可用；`/api/legado/app/sync` 返回 `books/groups/settings`。
- 首次绑定：最新备份恢复后，实时数据能补齐差异。
- 朗读交互：菜单从可见位置、胶囊从暂停位置；页内浮条可见；高亮只有文字色；跟随中上下滑退出跟随；跟随中目录/上一章/下一章换章后朗读落到新章节且不被旧位置拉回，脱离跟随后换章不改朗读位置；通知上一首切章。
- 进度：划掉最近任务后重开不回退；后台跟随换章后本地进度已更新；本地较新时启动回传而不是被服务端覆盖。
- 不提交 `server/.env`、`backend/config_local.py`、构建产物、测试截图。

详细字段、冲突规则、上游合并对照表见 `docs/FORK_DECISIONS.md`。
