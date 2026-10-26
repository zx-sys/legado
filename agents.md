# Legado 分叉开发约定（给人和 LLM）

本仓库不是上游 Legado 的简单镜像：产品行为、数据归属和已删功能都做过明确取舍。
**同步上游代码前先读本文件和 `docs/FORK_DECISIONS.md`；与本文件冲突的上游实现不要合回来。**

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

## 代码所有权边界

合并上游时，以下区域默认以本仓库为准，冲突时保留本仓库实现：

- 客户端同步：`app/src/main/java/io/legado/app/help/sync/`、`ui/font/`、`ui/tts/`、`help/font/`、`help/tts/`
- 客户端关键行为：`ReadAloud.kt`、`Book.kt` 的 `getPageAnim()/getTtsEngine()`、`SpeakEngineDialog.kt`、`MainActivity.kt`、`BookDao.kt`
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

## 合并上游后的最低验证

```bash
# 客户端
./gradlew assembleAppDebug -Pbackup.server.url=http://118.31.79.174:9666
# 后台
cd backend && .venv/Scripts/python.exe manage.py check
cd backend && .venv/Scripts/python.exe manage.py makemigrations --check --dry-run
```

并人工确认：

- 文本阅读翻页/字体等全局一致，朗读引擎没有“本书”选项。
- App 内无 WebDAV、无在线 TTS 入口/类/资源。
- 字体市场、TTS 市场可用；`/api/legado/app/sync` 返回 `books/groups/settings`。
- 首次绑定：最新备份恢复后，实时数据能补齐差异。
- 不提交 `server/.env`、`backend/config_local.py`、构建产物、测试截图。

详细字段、冲突规则、上游合并对照表见 `docs/FORK_DECISIONS.md`。
