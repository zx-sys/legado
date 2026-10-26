# Legado 分叉决策与上游合并指南

> 目的：固定本仓库的产品行为与数据归属，避免每次从上游同步代码时反复踩坑。
> 任何与本文冲突的上游实现，默认不合并；确需合并时先改本文和 `AGENTS.md`。

## 1. 产品形态

- Android 阅读器（本仓库 `app/`）+ 自建后台（`backend/` Django）+ 管理端（`admin-web/` Vue 3）。
- 手机号即账号，不再使用本地密码引导。
- 文件/数据归属：MySQL + Redis + 阿里云 OSS。
- `server/` 是早期 FastAPI 原型，已弃用，只作参考，不新增功能。

### 1.1 为什么这样取舍

- **阅读习惯全局**：一个用户只有一套阅读偏好；按书保存会让换书后字体/翻页突变、备份和同步字段爆炸，维护成本高。
- **WebDAV 移除**：多端一致性、备份可靠性、权限和部署都集中到自建后端，避免 WebDAV 凭据和本地文件状态两套真源。
- **在线 TTS 移除**：在线音色依赖第三方可用性和密钥，隐私与稳定性不可控；统一为系统 TTS + 本地 sherpa-onnx 目录。
- **字体/TTS 目录化**：APK 不随功能更新，模型和字体在后台/OSS 管理，客户端按需下载。

### 1.2 朗读会话与 App 内悬浮球

- **朗读会话独立**：`BaseReadAloudService` 通过 `ReadAloudSession` 持有会话书/书源/章节数，不再借用 `ReadBook` 单例；支持边听 A 书边读 B 书。会话书与当前书相同时仍走原有实时状态，保证同书行为不变。
- **App 内悬浮球**：`ReadAloudFloatBallManager` 把悬浮球挂到当前 Activity 的 content 根布局，只存在于 App 内，**不申请系统级悬浮窗**。三个热区：封面回朗读位置、播放/暂停、退出朗读；可拖动吸附左右边缘，底部预留 104dp 避让阅读页浮条。
- **控制入口统一**：阅读页返回键不再暂停朗读；暂停态离开阅读页也不停止服务，统一由悬浮球/通知控制。
- **阅读页浮条**：只在脱离阅读跟随时显示“回到朗读位置 / 从此处开始读”，不再显示单独暂停圆钮。
- **事件门控**：`ALOUD_STATE`、`TTS_PROGRESS`（sticky）、`READ_ALOUD_FOLLOW` 的消费方都必须用 `ReadAloud.isReadingBook(当前书)` 过滤，避免 B 阅读页响应 A 的进度。
- 合并上游朗读相关提交时保留以上边界，见 `AGENTS.md` 的代码所有权。

## 2. 已删除功能（禁止合并回来）

| 功能 | 当前状态 | 上游出现时如何处理 |
|---|---|---|
| WebDAV 书籍同步 | 已移除（类、DAO、实体、菜单、帮助文档、备份项） | 删除/不合入；只有 `LocalConfig.cleanupRemovedPreferences()` 的旧键清理保留 |
| WebDAV 备份/恢复 | 已移除 | 改用后台备份 + OSS |
| HTTP/在线 TTS（Edge/百度/阿里/Next） | 已移除（`HttpTTS`、`HttpTTSDao`、`HttpReadAloudService`、在线 TTS 菜单/默认数据） | 不合并；TTS 只用系统 TTS + 本地 sherpa-onnx 目录 |
| 首次启动本地密码 | 已移除 | 保持手机号绑定流程 |
| 内置字体包 `assets/font/presets` | 已移除 | 用后台 `FontPack` + 字体市场 |
| 写死单一 TTS 模型（melo） | 已移除 | 用 `RemoteTtsEngines` 目录 + 配置驱动 |
| 每本书一套文本阅读习惯/TTS 引擎 | 已移除 | 文本阅读习惯与 TTS 全局化，不恢复按书 UI/同步 |

## 3. 全局 vs 单书：配置边界

### 全局（会同步、跨设备一致）

- **阅读排版**：字体、字号、字距、行距、段距、缩进、简繁、背景、文字颜色、页面动画等
  - 存储：`ReadBookConfig.configList` / `shareConfig`（`readConfig.json`、`shareReadConfig.json`）
  - 同步类型：`read_config`、`share_read_config`
- **主题**：`theme_config`（`themeConfig.json`）
- **应用设置**：SharedPreferences 中可移植的 key（含朗读引擎、音色、语速、跟随系统、书架布局、发现页、阅读界面开关等）
  - 存储：默认 SharedPreferences（`config.xml` 备份）
  - 同步：`SettingsSync` 按 key LWW
- **文本翻页动画**：`ReadBookConfig.pageAnim`（文本阅读始终读全局）

### 单书内容级（保留但不属于“阅读习惯”，不参与配置同步）

`Book.readConfig` 仍服务于：

- 图片书翻页动画（`getPageAnim()` 中图片书分支）
- 替换规则（`useReplaceRule`、`manualReplaceRuleIds`）
- 图片样式、模拟阅读起始日期/章节、音频播放模式/速度、音频跳过片头尾等

这些字段可以留在本地数据库，但：

- 不再进入同步 payload；
- 不再提供“本书阅读习惯/本书 TTS”入口；
- 文本阅读与朗读引擎不得读取 `Book.readConfig.ttsEngine` 或 `Book.getPageAnim()` 的文本分支来覆盖全局值。

### 已明确不做的事

- 不新增“本书字体/字号/行距/背景”覆盖。
- 不新增“本书朗读引擎/音色”覆盖；`SpeakEngineDialog` 只有全局选择。
- 不把 `Book.readConfig` 整体塞进书架同步。

## 4. 后端数据模型

### 账号与备份

- `LegadoUser(phone)`：手机号账号。
- `Backup` / `BackupItem` / `BackupRecord`：zip 快照、文件项、结构化记录；文件可落 OSS 或本地。
- 首次绑定：`GET /api/legado/app/account?phone=` → 若有 `latest_backup` 则下载并恢复；新账号创建后上传初始备份。
- 备份仅作保底；实时数据由同步补齐。

### 书架同步

- `LegadoBook`：书籍元数据 + 阅读进度/最后阅读 + `sync_updated_at` + `removed/removed_at`。
- `LegadoBookGroup`：分组元数据 + `sync_updated_at` + `removed/removed_at`。
- 身份：书籍按 `(name, author)`（服务端唯一键），`book_url` 可变；分组按名称。
- 删除：墓碑；旧设备用旧时间戳补推不能复活；重新添加需更新的时间。

### 用户配置

- `UserConfig`：
  - `read_config` / `share_read_config` / `theme_config`：整文件内容
  - `app_settings`：按 key 的 `{value, updatedAt}` 映射
- `/api/legado/app/sync` 同时承载 `progress/records/configs/books/groups/settings`。

### Redis

- key：`legado:u:{phone}:progress|records|config:{type}`
- 进度/记录/配置 TTL 30 天；`legado:dirty:*` 每 30 秒落 MySQL。
- 书架/分组/设置直接写 MySQL（设置不需要 Redis 热层）。

## 5. 同步协议与冲突规则

请求：`GET/POST /api/legado/app/sync`，头 `X-User-Phone`。

- 写入：客户端 5 秒合并批量 POST；失败 30 秒重试；设置改动持久化到本地 pending，重启后补推。
- 拉取：`since` 游标（服务端时间），增量返回；升级 `configSyncVersion` 时游标归零做一次全量。
- LWW：所有带 `updatedAt` 的数据以时间戳大者为准；服务端把客户端时间压到当前时间，防止未来时钟。
- 进度：正在阅读的书不自动跳页，存 deferred，离开阅读后再应用。
- 设置：按 key 合并；本地时间戳记录在 `LocalConfig.settingsSyncTimes`，应用远端时防回推（`lastApplied` + `isApplying`）。
- 阅读配置：每次拉取都应用（不再只在启动时应用），应用后 `initConfigs()` 刷新内存。
- 删除：墓碑 + 时间戳比较；`removed_at` 之后旧数据不能复活。

### 设置同步排除项（设备/凭据/缓存）

备份服务器/手机号/本地路径、设备 ID、Web/MCP 端口、API Token、字体/TTS 安装缓存、
书源同步元数据、阅读控件坐标、阅读会话上次位置、调试开关等。
完整名单在 `app/src/main/java/io/legado/app/help/sync/SettingsSync.kt`。

### 实时通知

- 服务端：`/ws/legado/sync/`，手机号（头或 query）鉴权，加入组 `legado:u:{phone}`。
- 写入成功后广播 kinds；客户端收到后立即增量拉取；断线指数退避重连，60 秒轮询兜底。
- Channel layer：`channels_redis.pubsub.RedisPubSubChannelLayer`（当前 Redis 不支持 BZPOPMIN，不能用 core 层）。
- ASGI 必须保留 `/ws/msg/` 与 `/ws/legado/sync/` 两条路由。

## 6. 字体 / TTS 远程目录

### 字体

- 后台 `FontPack`（name/code/version/license/source_url/package_url/sha256/package_size 等）。
- OSS：`CourseStandards/legado/fonts/{code}/{code}.{ttf|otf}`。
- APK：`RemoteFonts` 拉目录（6h 缓存），下载校验 sha256，安装到 `externalFiles/font/`。
- 字体选择列表显示后台名称，不再内置字体文件。

### TTS

- 后台 `TtsEngine`：`sherpa_onnx` / `system` / `other`，无 `http`。
- OSS：`CourseStandards/legado/tts/engines/{code}/...`。
- APK：`RemoteTtsEngines` 下载解包，`LocalTtsConfigFactory` 按 `model_type` 组装 vits/kokoro/matcha。
- 引擎选择只有全局；多音色弹音色列表，sid 存 `TtsSelection`。

## 7. 上游合并对照表

| 上游内容 | 合并判定 |
|---|---|
| `AppWebDav*`、`Server`、`ServerDao`、WebDAV 菜单/帮助/备份项 | 丢弃；保留旧配置清理 |
| `HttpTTS*`、在线 TTS 服务/菜单/默认数据 | 丢弃 |
| `Book.readConfig.pageAnim` / `ttsEngine` 的按书覆盖 UI | 丢弃；文本翻页/TTS 读全局 |
| `assets/font/presets`、`BundledFonts` | 丢弃；走 FontPack |
| 硬编码 TTS 模型下载 | 丢弃；走 TtsEngine 目录 |
| WebDAV 备份/同步设置 | 丢弃；走后端备份/同步 |
| 本地密码/首次引导 | 丢弃；走手机号绑定 |
| 上游新增阅读界面设置 | 可合并，但必须进入全局 `ReadBookConfig`/AppConfig 并加入同步白名单 |
| 上游新增朗读设置 | 可合并，但必须是全局 key；不要写进 Book.readConfig 作为习惯 |
| 上游朗读服务与 `ReadBook` 耦合实现 | 保留本仓库 `ReadAloudSession` 解耦、App 内悬浮球与同书门控；不合入直接复用 `ReadBook` 全局状态的版本 |
| 上游同步/备份实现 | 不替换本仓库 `help/sync` 与 backend sync；可借鉴字段 |
| `server/` FastAPI | 不扩展 |
| `admin-web` 旧 dvlyadmin 代码 | 与仓库内 admin-web 冲突时保留仓库实现 |

### 冲突处理原则

1. 先确认冲突文件属于“代码所有权边界”（见 `AGENTS.md`），边界内默认保留本仓库。
2. 上游带来的新阅读/朗读设置：全局化、加入同步、排除设备相关项；不要按书存。
3. 上游带来的同步/备份：先对照 `backend/apps/legado/services/sync_service.py` 与本仓库协议，不直接替换。
4. Room 迁移只追加，不回滚/重置表；后台迁移只追加。
5. 保留 ASGI/WS 路由、Channels Redis 配置、手机号鉴权。
6. 不提交 `server/.env`、`backend/config_local.py`、构建产物、截图。

## 8. 合并后验证清单

- 文本阅读：切书后翻页动画/字体/间距一致；无“本书阅读设置”。
- 朗读：引擎选择无“书”按钮；切书后仍用全局引擎/音色。
- 无 WebDAV / 在线 TTS 的类、菜单、资源、默认数据。
- 字体市场、TTS 市场可用。
- `GET /api/legado/app/sync?since=0` 返回 `books/groups/settings`；WS 日志有 `ws connected`。
- 首次绑定：最新备份恢复后，实时同步补齐书架/进度；删除/重加走墓碑。
- 构建：

```bash
./gradlew assembleAppDebug -Pbackup.server.url=http://118.31.79.174:9666 -ParmOnly=true
cd backend && .venv/Scripts/python.exe manage.py check
cd backend && .venv/Scripts/python.exe manage.py makemigrations --check --dry-run
```
