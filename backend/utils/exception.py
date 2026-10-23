# -*- coding: utf-8 -*-

"""
@Author:lybbn
@QQ:1042594286
@Remark: 自定义异常处理
"""
import logging
import traceback

from django.db.models import ProtectedError
from django.http.response import Http404
from rest_framework import exceptions
from rest_framework.exceptions import APIException as DRFAPIException, AuthenticationFailed, NotAuthenticated
from rest_framework.views import set_rollback, exception_handler
from utils.jsonResponse import ErrorResponse

logger = logging.getLogger(__name__)


def CustomExceptionHandler(ex, context):
    """
    统一异常拦截处理
    目的:(1)取消所有的500异常响应,统一响应为标准错误返回
        (2)准确显示错误信息
    :param ex:
    :param context:
    :return:
    """
    msg = ''
    code = 4000
    response = exception_handler(ex, context)
    if isinstance(ex, AuthenticationFailed):
        code = 4001
        if 'User is inactive' in str(ex.detail):
            msg = "该账号已被禁用,请联系管理员"
        elif response and response.data.get("detail") == "Given token not valid for any token type":
            msg = "身份认证已过期"
        else:
            msg = ex.detail
    elif isinstance(ex, NotAuthenticated):
        code = 4001
        msg = ex.detail
    elif isinstance(ex, exceptions.ValidationError):
        msg = str(ex.detail)
        error_msg = ex.detail  # 直接使用ex.detail而不是转换为字符串
        try:
            # 处理字典类型的错误信息
            if isinstance(error_msg, dict):
                for key in error_msg:
                    if key:
                        msg = '%s:%s' % (key, error_msg[key][0])
                    else:
                        msg = error_msg[key][0]
                    break  # 只取第一个错误信息
            # 处理列表类型的错误信息
            elif isinstance(error_msg, list):
                msg = error_msg[0]
            # 处理其他情况
            else:
                msg = str(error_msg)
        except Exception as e:
            # 如果上述处理都失败，则尝试原始的处理逻辑
            try:
                if isinstance(error_msg, list):
                    msg = error_msg[0]
                elif isinstance(error_msg, dict):
                    values_list = list(error_msg.values())
                    keys_list = list(error_msg.keys())
                    if values_list and isinstance(values_list[0], dict) and 'non_field_errors' in values_list[0]:
                        msg = keys_list[0] + ":" + values_list[0]['non_field_errors'][0]
                    elif values_list and isinstance(values_list[0], list):
                        msg = keys_list[0] + ":" + values_list[0][0]
                    else:
                        msg = str(error_msg)
                else:
                    msg = str(error_msg)
            except:
                msg = str(error_msg)
    elif 'django.db.utils.IntegrityError' in str(type(ex)):
        msg=str(ex)
        res = msg.split(', ')
        if res[0] == '(1062':
            msg="数据有重复，请检查后重试:%s"%msg
    elif isinstance(ex,Http404):
        code = 404
        msg = "404错误：您访问的地址不存在"
    elif isinstance(ex, DRFAPIException):
        set_rollback()
        msg = str(ex.detail)
    elif isinstance(ex, exceptions.APIException):
        set_rollback()
        msg = str(ex.detail)
    elif isinstance(ex, ProtectedError):
        set_rollback()
        msg = "删除失败:该条数据与其他数据有相关绑定"
    elif isinstance(ex, Exception):
        logger.error(traceback.format_exc())
        msg = str(ex)#原样输出错误

    return ErrorResponse(msg=msg, code=code)


class APIException(Exception):
    """
    通用异常:(1)用于接口请求是抛出移除, 此时code会被当做标准返回的code, message会被当做标准返回的msg
    """

    def __init__(self, code=201, message='API异常', args=('API异常',)):
        self.args = args
        self.code = code
        self.message = message

    def __str__(self):
        return self.message