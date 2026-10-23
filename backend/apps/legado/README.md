# Legado 管理模块（dvlyadmin 版）

该模块把 Legado 的手机号数据和备份内容拆成独立后台管理模块：

| 模块 | 数据来源 | 用途 |
|---|---|---|
| 用户管理 | 手机号自动创建 | 手机号、昵称、备份数、书架数 |
| 书架管理 | `bookshelf.json` + `readRecord.json` | 用户书籍、阅读进度、阅读时长、书签、标注 |
| 用户配置 | `readConfig.json`、`shareReadConfig.json`、`themeConfig.json`、`config.xml`、`videoConfig.xml` | 阅读排版、主题、应用设置 |
| 备份管理 | 每天/每次备份的完整快照 | 查看、下载、删除、还原到当前模块 |
| 书源管理 | `bookSource.json` | 书源 CRUD、JSON 导入/导出 |
| TTS 引擎 | 后台录入/上传 OSS | 收集新引擎、启用/默认、包下载地址 |

备份 ZIP 中的数据关系：

- 用户管理/书架管理/用户配置/书源管理展示的是**当前数据**
- 阅读记录合并到书架书籍行（累计阅读时长、最后阅读时间/章节）
- 书签 `bookmark.json`、标注 `highlight.json` 是书架书籍的子表
- 每次 APK 上传的 ZIP 是一份**快照**，保存在 `Backup`、`BackupItem`、`BackupRecord`
- 备份页面的“还原模块”会把该快照中的 `bookshelf.json` 和用户配置文件覆盖到当前模块
- 默认保留历史备份；APP 里“只保留最新备份”开启时才按设备清理旧快照
- 书签、标注、阅读记录、订阅源、规则等仍保存在 `BackupItem` /
  `BackupRecord` 中；需要再拆成独立页面时，直接基于 `dataset` 扩展。

书源是全局配置，不从单个用户的备份还原，避免覆盖其他用户的共享书源。

## 后台入口

超级管理员登录后侧边栏出现：

- Legado 管理
  - 备份管理
  - 书架管理
  - 用户配置
  - 用户管理
  - 书源管理
  - TTS 引擎

菜单由迁移 `0003_seed_menus.py` 自动创建。非超级管理员需要在角色权限里分配对应菜单和接口按钮权限。

## 接口

管理端（JWT 登录）：

```
/api/legado/users/
/api/legado/backups/                 # 支持 download / items / records 动作
/api/legado/books/
/api/legado/user-configs/
/api/legado/book-sources/            # import_json / export_json
/api/legado/tts-engines/             # upload_package 动作
```

APP 端（无 JWT，手机号请求头 `X-User-Phone`）：

```
POST   /api/legado/app/backups/upload
GET    /api/legado/app/backups
GET    /api/legado/app/backups/latest
GET    /api/legado/app/backups/{id}/download
DELETE /api/legado/app/backups/{id}
GET    /api/legado/app/tts/engines
GET    /api/legado/app/tts/engines/{code}/download
GET    /api/legado/app/book-sources
```

APP 的服务器地址填域名根地址即可，例如 `https://api.example.com`；APK 会请求
`/api/legado/app/...`。

## 常用命令

```bash
python manage.py migrate
python manage.py init                       # 初始化后台账号/菜单等
python manage.py import_legado_sources <bookSources.json>
python manage.py runserver 0.0.0.0:8000
```

TTS 引擎包在页面里点“上传包”，文件会传到配置的 OSS；也可以直接编辑下载地址。

## 配置

`config.py` 已配置 RDS `legado`、Redis、OSS。`config_local.py` 仅用于本地 SQLite 开发，
部署时不要带上。上传文件、备份、头像等媒体路径由 `MEDIA_ROOT` 管理；引擎包和备份媒体优先走 OSS。
