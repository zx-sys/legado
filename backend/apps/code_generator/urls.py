# apps/code_generator/urls.py
from django.urls import path
from rest_framework import routers

from . import views

app_name = 'code_generator'

system_url = routers.SimpleRouter()

# 注册标准路由
# /api/generator/tables/ - GenTable 列表/创建
# /api/generator/tables/{id}/ - GenTable 详情/更新/删除
# /api/generator/tables/{id}/generate_code/ - 生成代码（POST）
# /api/generator/tables/{id}/download_code/ - 下载代码（GET）
system_url.register(r'tables', views.GenTableViewSet, basename='gen_table')

# /api/generator/columns/ - GenTableColumn 列表/创建
# /api/generator/columns/{id}/ - GenTableColumn 详情/更新/删除
# /api/generator/columns/batch_save/ - 批量保存字段（POST）
system_url.register(r'columns', views.GenTableColumnViewSet, basename='gen_table_column')

urlpatterns = system_url.urls
