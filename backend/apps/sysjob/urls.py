# -*- coding: utf-8 -*-

"""
@Remark: 定时任务管理的路由文件
"""
from django.urls import path
from rest_framework import routers

from apps.sysjob.views import SysJobViewSet, SysJobLogViewSet

sysjob_url = routers.SimpleRouter()
sysjob_url.register(r'jobs', SysJobViewSet, basename='scheduler-job')
sysjob_url.register(r'executions', SysJobLogViewSet, basename='scheduler-execution')

urlpatterns = [
    # 任务管理自定义操作
    path('jobs/<str:pk>/run/', SysJobViewSet.as_view({'post': 'run'})),
    path('jobs/<str:pk>/start/', SysJobViewSet.as_view({'post': 'start'})),
    path('jobs/<str:pk>/stop/', SysJobViewSet.as_view({'post': 'stop'})),

    # 日志管理自定义操作
    path('executions/clear/', SysJobLogViewSet.as_view({'post': 'clear'})),
]
urlpatterns += sysjob_url.urls
