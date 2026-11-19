# AI 正文清洗 + 节假日任务 部署包

包内只含新增/改动文件，不含 `config_local.py`、`application/settings.py` 全文、`SECRET_KEY` 或 API Key。

## 1. 上传与解压

```bash
scp "D:\Project\legado\build\deploy\legado-backend-ai-polish-20261118.tar.gz" root@118.31.79.174:/tmp/
ssh root@118.31.79.174 "cd /opt/legado/backend && tar xzf /tmp/legado-backend-ai-polish-20261118.tar.gz"
ssh root@118.31.79.174 "cd /opt/legado/backend && .venv/bin/python apply_ai_polish.py"
```

`apply_ai_polish.py` 只做两件事，重复执行安全：

- 在 `application/settings.py` 的 `INSTALLED_APPS` 加入 `'apps.polish'`
- 在 `config.py` 末尾追加 AI 清洗配置块（已存在则跳过）

不会改 `SECRET_KEY`、`config_local.py`。

## 2. 配置 Key

在 `/opt/legado/backend/config_local.py` 或 systemd 环境加入：

```python
DEEPSEEK_API_KEY = "<你的 Key>"
# 可选
POLISH_MODEL = "deepseek-flash"
POLISH_DAILY_BUDGET_CNY = 2.0
# POLISH_HOLIDAY_URL = "https://你的镜像/{year}.json"
```

来源可以是环境变量 `LEGADO_DEEPSEEK_API_KEY`，不要把 Key 写进仓库或 APK。

## 3. 迁移与注册任务

```bash
cd /opt/legado/backend
.venv/bin/python manage.py migrate
.venv/bin/python manage.py seed_polish_job
.venv/bin/python manage.py seed_polish_holiday_job
.venv/bin/python manage.py refresh_polish_holidays
/usr/bin/systemctl restart legado.service
```

- `migrate` 会创建 `polish.0001` / `polish.0002` 的表。
- `seed_polish_job`：清洗队列，每 5 分钟。
- `seed_polish_holiday_job`：节假日刷新，每月 1 号 03:30。
- `refresh_polish_holidays`：立即拉一次当年+次年节假日，写入 `SystemConfig.polish_holidays`。

## 4. 验证

```bash
curl -s "http://127.0.0.1:9666/api/legado/app/polish/status?bookUrl=x" \
  -H "X-User-Phone: <手机号>"

journalctl -u legado.service -f | grep -iE "polish|节假日"
```

预期：

- `/polish/status` 返回 `queued/done/contaminated/...` 字段；
- 空闲窗口内 worker 每 5 分钟执行；
- 节假日刷新日志含年份和条数。

> 不要在生产库直接跑 `polish_smoke`（会写入测试用户/任务）；它只用于临时库。生产验证用 `/polish/status`。

## 5. 回滚

- 删除 `apps/polish/`；
- 从 `application/settings.py` 移除 `'apps.polish',`；
- 可选：删除 `config.py` 末尾的 `# ************** DeepSeek 正文清洗` 段；
- `cc_polish_*` 表可保留，不影响现有功能。

## 包内容

- `apps/polish/`（含 `0001`、`0002` 迁移、管理命令、fixtures，不含 tests/`__pycache__`）
- `apps/legado/urls.py`（新增 4 个 polish 路由）
- `apply_ai_polish.py`
- `DEPLOY_AI_POLISH.md`
