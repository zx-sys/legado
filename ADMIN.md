# Legado 后台（项目内）

后台代码已经并入本仓库，不再依赖 `dvlyadmin` 运行；`dvlyadmin` 只作为框架参考。

```
legado/
├── app/                 # Android 客户端
├── backend/             # Django 后台 + API
│   └── apps/legado/     # 手机用户、备份、书架、书源、TTS 引擎
├── admin-web/           # Vue 3 + Element Plus 管理界面
└── server/              # 早期 FastAPI 原型，已弃用，仅保留参考
```

## 启动后台

```bash
cd backend
python -m venv .venv
.venv\Scripts\python.exe -m pip install -r requirements.txt -i https://pypi.tuna.tsinghua.edu.cn/simple
.venv\Scripts\python.exe manage.py migrate
.venv\Scripts\python.exe manage.py init
.venv\Scripts\python.exe manage.py runserver 0.0.0.0:8000
```

`config.py` 只保留非敏感的默认配置，数据库密码、Redis 密码、OSS AccessKey 等敏感项从
环境变量读取（`LEGADO_DATABASE_PASSWORD`、`LEGADO_REDIS_PASSWORD`、`LEGADO_OSS_ACCESS_KEY_ID`、
`LEGADO_OSS_ACCESS_KEY_SECRET` 等）。本地开发可创建 `config_local.py` 覆盖任意配置（包括切换到
SQLite），该文件已被忽略，不要提交。`init` 会初始化后台账号和菜单，首次登录后请修改密码。

## 启动管理界面

```bash
cd admin-web
npm install --registry=https://registry.npmmirror.com
npm start
```

开发地址默认 `http://localhost:8081`，接口默认连 `http://127.0.0.1:8000`。
生产可执行 `npm run build:backend`，静态文件会输出到 `backend/frontend/lyadmin`。

## 已内置的管理模块

- 用户管理（手机号）
- 书架管理（阅读进度、阅读时长、书签/标注子表）
- 用户配置
- 备份管理（快照下载、还原到当前模块）
- 书源管理（JSON 导入/导出）
- TTS 引擎（OSS 包上传/下载）

APP 端备份接口：

```
POST /api/legado/app/backups/upload
```

请求头 `X-User-Phone`，服务器地址填后端根地址即可。
