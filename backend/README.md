## 📌 注意事项
- 目前安装 `django==6.0` 可支持 MySQL 5.7
- 如需支持 MySQL 8.x 及以上版本，请使用最新版 Django（修改 `requirements.txt`）
- 再config.py中可配置支持SQLITE3、POSTGRESQL（安装psycopg2库）
- 如需集成部署后端前端页面，请在前端项目根目录下执行 npm run build:backend，会自动打包静态文件到```backend/frontend/admin```目录中

## 安装依赖环境

```
pip install -r requirements.txt -i https://mirrors.aliyun.com/pypi/simple/
```

## 🛠️执行迁移命令：(使用sql脚本直接导入可忽略本步骤)

```
# 生成迁移文件
python manage.py makemigrations
# 执行迁移
python manage.py migrate
```

## 📊初始化数据：(使用sql脚本直接导入可忽略本步骤)

```
python manage.py init
```

## 🚦启动项目（初始账号：superadmin 密码：123456）

```
#开发服务器方式
python manage.py runserver 127.0.0.1:8000

#ASGI 部署方式（支持 WebSocket）
或使用 daphne (需要使用此asgi方式部署来支持websocket):

daphne -b 0.0.0.0 -p 8000 --proxy-headers application.asgi:application

```