# Legado 备份后端

轻量 FastAPI 服务，用于接收 Legado 阅读 APP 的备份包，按手机号隔离用户数据，并把备份内容拆分到 MySQL。媒体文件可放阿里云 OSS，缓存可接 Redis，LLM 预留 DeepSeek。

## 功能

- 手机号直接作为用户标识（`X-User-Phone` 请求头），无登录/鉴权，只做数据隔离
- 上传 APP 现有的 `backup.zip`，自动解析 JSON/XML/媒体文件
- 备份元数据 + 每个文件内容入库；JSON 数组额外拆成 `backup_records` 行
- 下载时按原 ZIP 结构还原
- 默认每个用户 + 设备只保留最新备份，可关闭
- 媒体文件优先 OSS，未配置时落本地 `storage/`
- Redis 缓存“最新备份”查询（可选）
- DeepSeek 润色接口骨架，后续接章节流水线

## 快速开始

```bash
cd server
python -m venv .venv
.venv/Scripts/activate        # Linux: source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env          # 填入 MySQL / OSS / DeepSeek 配置
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

Docker 方式（本地 MySQL/Redis）：

```bash
cp .env.example .env
docker compose up -d --build
```

如果使用外部 MySQL/RDS 和 Redis（`.env` 里已配置好远程地址），只启动 API：

```bash
docker compose -f docker-compose.external.yml up -d --build
```

注意：`docker-compose.yml` 会把数据库地址强制指向 compose 内的 `mysql`；使用外部
RDS 时请用 `docker-compose.external.yml`，不要混用。

启动后：

- Swagger: `http://127.0.0.1:8000/docs`
- 健康检查: `GET /health`

## APK 对接

APP 备份触发逻辑不变（手动 / 每日自动），只把上传目标从 WebDAV 改成：

```
POST /api/v1/backups/upload
Header: X-User-Phone: 13800000000
multipart/form-data:
  file=backup.zip
  device_name=Pixel 8
  trigger=auto
```

APP 端在“备份设置”里只需填写手机号（服务器地址在构建配置里指定），列表、下载恢复分别调用：

- `GET /api/v1/backups?device_name=...`
- `GET /api/v1/backups/latest`
- `GET /api/v1/backups/{id}/download`

## API 一览

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/health` | 服务/DB/Redis/OSS 状态 |
| GET | `/api/v1/users/me` | 当前手机号用户（首次自动创建） |
| PATCH | `/api/v1/users/me` | 修改昵称 |
| POST | `/api/v1/backups/upload` | 上传备份 ZIP |
| GET | `/api/v1/backups` | 备份列表（可按设备过滤） |
| GET | `/api/v1/backups/latest` | 最新备份 |
| GET | `/api/v1/backups/{id}` | 备份详情（文件列表、数据集计数） |
| GET | `/api/v1/backups/{id}/download` | 下载还原 ZIP |
| GET | `/api/v1/backups/{id}/items/{path}` | 读取单个文件（JSON/图片等） |
| GET | `/api/v1/backups/{id}/records?dataset=bookshelf` | 查询拆分后的记录 |
| DELETE | `/api/v1/backups/{id}` | 删除备份及媒体文件 |
| POST | `/api/v1/llm/polish` | DeepSeek 文本润色 |

所有 `/api/v1` 接口都必须带：

```
X-User-Phone: 13800000000
```

## 数据库初始化

SQL 文件：[`sql/schema.sql`](sql/schema.sql)，包含数据库 `legado` 和全部表/索引/外键。
可手动执行：

```bash
mysql -h <host> -u <user> -p < sql/schema.sql
```

后端启动时也会用 SQLAlchemy `create_all` 自动补建缺失表；SQL 文件适合在服务器上
用 DBA 账号预先创建或做结构审查。

## 数据表

- `users`：手机号、昵称
- `backups`：一次备份（用户、设备、触发方式、大小、sha256、时间）
- `backup_items`：ZIP 内每个文件；JSON/XML 存 DB，图片等存 OSS/本地
- `backup_records`：JSON 数组拆出的行（书架、书源、阅读记录等），供后续查询/LLM 使用

## 配置

全部配置见 `.env.example`。重点：

- `MYSQL_*` 或 `DATABASE_URL`
- `REDIS_URL`（留空关闭）
- `OSS_*`（留空则媒体落 `LOCAL_STORAGE_DIR`）
- `KEEP_LATEST_BACKUPS`（默认 true）
- `MAX_BACKUP_MB`（默认 512）
- `DEEPSEEK_API_KEY` / `DEEPSEEK_BASE_URL` / `DEEPSEEK_MODEL`
- `MYSQL_HOST_PORT` / `REDIS_HOST_PORT`：仅 Docker 宿主机映射端口，默认只绑定 `127.0.0.1`

Docker 部署时请只修改 `.env`：compose 会把 `MYSQL_USER` / `MYSQL_PASSWORD` /
`MYSQL_DATABASE` 同时注入 MySQL 和 API，并强制 API 使用容器内地址；不要单独改
`docker-compose.yml` 里的密码。API 会等待 MySQL/Redis 健康检查通过后启动。

## 部署注意

- 服务启动时用 `Base.metadata.create_all` 建表，暂未引入 Alembic；后续改表结构请先备份数据库并补充迁移。
- 若前面套 Nginx，需放大上传限制，例如 `client_max_body_size 512m;`，并配置 HTTPS。
- MySQL/Redis 端口默认只在宿主机 `127.0.0.1` 暴露；API 的 8000 端口不要直接暴露公网，建议放在反向代理或内网后面。
- `.env` 含数据库/OSS/DeepSeek 密钥，已被 `.gitignore` 忽略，不要提交。
- 测试：`pip install -r requirements-dev.txt && pytest`。

## 安全提示

按需求未做鉴权：知道手机号的人即可读写该手机号的备份。请至少：

- 不要直接暴露公网 8000 端口，放在反向代理/内网后面
- 用 HTTPS
- 定期备份 MySQL 与 `storage/`（若未用 OSS）
