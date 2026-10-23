# -*- coding: utf-8 -*-
"""
代码生成器 Views
"""

import logging
import os
import zipfile
import io
import re
from datetime import datetime

from django.conf import settings
from django.db import transaction
from django.http import FileResponse, JsonResponse, Http404
from django.shortcuts import get_object_or_404
from django_filters import rest_framework as filters
from rest_framework import status
from rest_framework.decorators import action
from rest_framework.exceptions import ValidationError
from rest_framework.response import Response

from utils.viewset import CustomModelViewSet
from utils.serializers import CustomModelSerializer

from .models import GenTable, GenTableColumn

logger = logging.getLogger(__name__)

# 尝试导入 Jinja2，如果不可用则使用 Django 模板
try:
    from jinja2 import Environment, FileSystemLoader, select_autoescape
    JINJA2_AVAILABLE = True
except ImportError:
    JINJA2_AVAILABLE = False
    from django.template import Template, Context


# 数据库类型到 Django 字段类型的映射
DB_TYPE_TO_DJANGO = {
    'VARCHAR': 'CharField',
    'CHAR': 'CharField',
    'TEXT': 'TextField',
    'LONGTEXT': 'TextField',
    'MEDIUMTEXT': 'TextField',
    'TINYINT': 'BooleanField',
    'INT': 'IntegerField',
    'INTEGER': 'IntegerField',
    'BIGINT': 'BigIntegerField',
    'SMALLINT': 'SmallIntegerField',
    'DOUBLE': 'FloatField',
    'FLOAT': 'FloatField',
    'DECIMAL': 'DecimalField',
    'NUMERIC': 'DecimalField',
    'DATE': 'DateField',
    'DATETIME': 'DateTimeField',
    'TIMESTAMP': 'DateTimeField',
    'BOOLEAN': 'BooleanField',
    'BOOL': 'BooleanField',
    'JSON': 'JSONField',
}

# 数据库类型到 JavaScript 类型的映射
DB_TYPE_TO_JS = {
    'VARCHAR': 'string',
    'CHAR': 'string',
    'TEXT': 'string',
    'LONGTEXT': 'string',
    'MEDIUMTEXT': 'string',
    'TINYINT': 'number',
    'INT': 'number',
    'INTEGER': 'number',
    'BIGINT': 'number',
    'SMALLINT': 'number',
    'DOUBLE': 'number',
    'FLOAT': 'number',
    'DECIMAL': 'number',
    'NUMERIC': 'number',
    'DATE': 'string',
    'DATETIME': 'string',
    'TIMESTAMP': 'string',
    'BOOLEAN': 'boolean',
    'BOOL': 'boolean',
    'JSON': 'object',
}


class TemplateRenderError(Exception):
    """模板渲染异常"""
    pass


def render_template(template_name: str, context: dict) -> str:
    """
    渲染模板文件
    
    Args:
        template_name: 模板文件名
        context: 模板上下文
        
    Returns:
        str: 渲染后的内容
        
    Raises:
        TemplateRenderError: 模板不存在或渲染失败时抛出
    """
    templates_dir = getattr(settings, 'CODE_GENERATOR_TEMPLATES_DIR', 
                           os.path.join(settings.BASE_DIR, 'apps', 'code_generator', 'templates', 'code_templates'))
    
    # 检查模板目录是否存在
    if not os.path.exists(templates_dir):
        raise TemplateRenderError(f'模板目录不存在: {templates_dir}')
    
    template_path = os.path.join(templates_dir, template_name)
    
    # 检查模板文件是否存在
    if not os.path.exists(template_path):
        raise TemplateRenderError(f'模板文件不存在: {template_name}')
    
    try:
        if JINJA2_AVAILABLE:
            env = Environment(
                loader=FileSystemLoader(templates_dir),
                autoescape=select_autoescape(['html', 'xml'])
            )
            template = env.get_template(template_name)
            return template.render(**context)
        else:
            # Django 模板回退方案
            from django.template.loader import get_template
            template = get_template(f'code_templates/{template_name}')
            return template.render(context)
    except Exception as e:
        logger.error(f'模板渲染失败 [{template_name}]: {str(e)}', exc_info=True)
        raise TemplateRenderError(f'模板渲染失败 [{template_name}]: {str(e)}')


def get_pk_column(columns):
    """获取主键列"""
    for col in columns:
        if col.is_pk:
            return col.column_name
    # 如果没有明确的主键，返回第一个列或'id'
    return columns[0].column_name if columns else 'id'


def camel_to_snake(name):
    """将驼峰命名转换为下划线命名"""
    s1 = re.sub('(.)([A-Z][a-z]+)', r'\1_\2', name)
    return re.sub('([a-z0-9])([A-Z])', r'\1_\2', s1).lower()


def snake_to_camel(name):
    """将下划线命名转换为驼峰命名（首字母大写）"""
    parts = name.split('_')
    return ''.join(word.capitalize() for word in parts)


# 独立视图函数：根据 table_id 生成代码并返回 ZIP 下载流
def generate_code_view(request, pk=None):
    """
    为指定的 GenTable 生成代码并返回 ZIP 下载流或预览
    URL: POST /api/generator/tables/{table_id}/generate_code/

    入参:
    - table_id (URL path参数): GenTable 的主键 ID
    - module_name (POST body, 可选): 模块名称，覆盖数据库中的 app_name
    - author (POST body, 可选): 作者名称，覆盖数据库中的 author
    - preview (POST body/Query parameter, 可选): 是否仅预览代码返回JSON

    出参:
    - 成功: FileResponse - ZIP 文件流 (Content-Type: application/zip)
    - 预览模式: JsonResponse - 包含所有生成文件内容的JSON
    - 失败: JsonResponse - 错误信息
    """
    try:
        # 1. 根据 table_id 查询表
        gen_table = get_object_or_404(GenTable, pk=pk)

        # 2. 获取 POST Body 中的可选配置
        request_data = getattr(request, 'data', {})
        if hasattr(request, 'POST'):
            request_data.update(request.POST.dict())

        # 支持通过 Query Parameters 传递配置
        query_params = {
            'module_name': request.GET.get('module_name'),
            'author': request.GET.get('author'),
            'preview': request.GET.get('preview'),
        }

        # 优先级: POST Body > Query Parameters > 数据库配置
        module_name = (
            request_data.get('module_name') or
            query_params.get('module_name') or
            gen_table.app_name
        )
        author = (
            request_data.get('author') or
            query_params.get('author') or
            gen_table.author or
            'AutoGenerator'
        )
        # 检查是否为预览模式
        is_preview = (
            str(request_data.get('preview', '')).lower() == 'true' or
            str(query_params.get('preview', '')).lower() == 'true'
        )

        # 3. 获取表的所有字段
        columns = list(gen_table.columns.all().order_by('sort', 'id'))
        if not columns:
            return JsonResponse({
                'code': 4000,
                'msg': f'表 {gen_table.model_name} 没有配置字段',
                'data': None
            }, status=400)

        # 4. 获取主键列名
        pk_column_name = get_pk_column(columns)

        # 5. 准备模板上下文（使用传入的配置）
        context = {
            'table': gen_table,
            'columns': columns,
            'pk_column_name': pk_column_name,
            'class_name': gen_table.model_name,
            'table_name': gen_table.table_name,
            'table_comment': gen_table.model_comment or gen_table.model_name,
            'app_name': gen_table.app_name,
            'module_name': module_name,  # 使用传入的配置
            'business_name': camel_to_snake(gen_table.model_name),
            'function_name': gen_table.model_comment or gen_table.model_name,
            'author': author,  # 使用传入的配置
            'datetime': datetime.now().strftime('%Y-%m-%d %H:%M:%S'),
        }

        # 6. 定义要生成的文件列表（模板名 -> 输出路径）
        files_to_generate = [
            # 后端文件
            ('python_model.j2', f"{context['business_name']}/models.py"),
            ('python_serializer.j2', f"{context['business_name']}/serializers.py"),
            ('python_viewset.j2', f"{context['business_name']}/views.py"),
            ('api.j2', f"{context['business_name']}/api.js"),
            # 前端文件
            ('vue_component.j2', f"{context['business_name']}/index.vue"),
        ]

        # 7. 生成文件内容
        generated_files = {}
        for template_name, output_path in files_to_generate:
            try:
                # 渲染模板
                content = render_template(template_name, context)
                generated_files[output_path] = content
                logger.info(f"Generated: {output_path}")

            except Exception as e:
                logger.error(f"Failed to generate {template_name}: {str(e)}")

        # 8. 根据模式返回结果
        if is_preview:
            # 预览模式：返回JSON格式的所有文件内容
            return JsonResponse({
                'code': 2000,
                'msg': '预览成功',
                'data': {
                    'files': generated_files,
                    'table_name': context['table_name'],
                    'business_name': context['business_name']
                }
            }, status=200)
        else:
            # 下载模式：返回ZIP文件
            zip_buffer = io.BytesIO()
            with zipfile.ZipFile(zip_buffer, 'w', zipfile.ZIP_DEFLATED) as zip_file:
                for output_path, content in generated_files.items():
                    try:
                        zip_file.writestr(output_path, content.encode('utf-8'))
                    except Exception as e:
                        logger.error(f"Failed to add {output_path} to ZIP: {str(e)}")

            # 准备返回 ZIP 文件
            zip_buffer.seek(0)
            filename = f"{context['business_name']}_generated_code.zip"

            response = FileResponse(
                zip_buffer,
                as_attachment=True,
                filename=filename
            )
            response['Content-Type'] = 'application/zip'
            response['Content-Disposition'] = f'attachment; filename="{filename}"'

            return response

    except Http404:
        return JsonResponse({
            'code': 4004,
            'msg': f'找不到 ID 为 {pk} 的表配置',
            'data': None
        }, status=404)
    except Exception as e:
        logger.error(f"生成代码时发生错误：{str(e)}", exc_info=True)
        return JsonResponse({
            'code': 5000,
            'msg': f'生成代码失败：{str(e)}',
            'data': None
        }, status=500)


# === 1. GenTable 相关 ViewSet ===

class GenTableFilterSet(filters.FilterSet):
    """GenTable 过滤器"""
    model_name = filters.CharFilter(lookup_expr='icontains')
    table_name = filters.CharFilter(lookup_expr='icontains')
    table_comment = filters.CharFilter(lookup_expr='icontains')

    class Meta:
        model = GenTable
        fields = ['app_name', 'model_name', 'table_name']


class GenTableSerializer(CustomModelSerializer):
    """生成表 - 序列化器"""

    class Meta:
        model = GenTable
        fields = "__all__"
        read_only_fields = ["id"]


class GenTableViewSet(CustomModelViewSet):
    """
    代码生成配置主表接口
    list: 查询所有配置
    create: 新增配置
    update: 修改配置
    retrieve: 单例详情
    destroy: 删除配置
    """
    queryset = GenTable.objects.all().order_by('-create_time')
    serializer_class = GenTableSerializer
    filterset_class = GenTableFilterSet
    search_fields = ('model_name', 'table_name', 'model_comment')

    @action(methods=['post'], detail=True, url_path='generate_code')
    def generate_code(self, request, pk=None):
        """
        为指定的 GenTable 生成代码并返回 ZIP 下载流或预览
        URL: POST /api/generator/tables/{table_id}/generate_code/

        入参:
        - pk (URL path参数): GenTable 的主键 ID
        - module_name (POST body, 可选): 模块名称，覆盖数据库中的 app_name
        - author (POST body, 可选): 作者名称，覆盖数据库中的 author
        - preview (POST body, 可选): 是否仅预览代码返回JSON

        出参:
        - 预览模式 (preview=true): JsonResponse - 包含所有生成文件内容的JSON
        - 下载模式 (preview=false/absent): FileResponse - ZIP 文件流 (Content-Type: application/zip)
        """
        gen_table = self.get_object()

        # 获取 POST Body 中的可选配置
        request_data = request.data

        # 获取可选参数
        module_name = request_data.get('module_name') or gen_table.app_name
        author = request_data.get('author') or gen_table.author or 'AutoGenerator'
        is_preview = str(request_data.get('preview', '')).lower() == 'true'

        try:
            # 1. 获取表的所有字段
            columns = list(gen_table.columns.all().order_by('sort', 'id'))
            if not columns:
                return Response({
                    'code': 4000,
                    'msg': f'表 {gen_table.model_name} 没有配置字段'
                }, status=status.HTTP_400_BAD_REQUEST)

            # 2. 获取主键列名
            pk_column_name = get_pk_column(columns)

            # 3. 准备模板上下文
            context = {
                'table': gen_table,
                'columns': columns,
                'pk_column_name': pk_column_name,
                'class_name': gen_table.model_name,
                'table_name': gen_table.table_name,
                'table_comment': gen_table.model_comment or gen_table.model_name,
                'app_name': gen_table.app_name,
                'module_name': module_name,
                'business_name': camel_to_snake(gen_table.model_name),
                'function_name': gen_table.model_comment or gen_table.model_name,
                'author': author,
                'datetime': datetime.now().strftime('%Y-%m-%d %H:%M:%S'),
            }

            # 4. 定义要生成的文件列表（模板名 -> 输出路径，路径为 ZIP 包内相对路径）
            app_name = context['business_name']
            module_name = context.get('module_name', 'system')
            
            files_to_generate = [
                # 后端文件 (backend/apps/<app_name>/)
                ('python_model.j2', f"backend/apps/{app_name}/models.py"),
                ('python_serializer.j2', f"backend/apps/{app_name}/serializers.py"),
                ('python_viewset.j2', f"backend/apps/{app_name}/views.py"),
                ('python_urls.j2', f"backend/apps/{app_name}/urls.py"),
                # 前端文件 (frontend/src/views/system/<app_name>/)
                ('vue_component.j2', f"frontend/src/views/system/{module_name}/{app_name}/index.vue"),
                ('vue_api.j2', f"frontend/src/views/system/{module_name}/{app_name}/api.js"),
                ('vue_crud.j2', f"frontend/src/views/system/{module_name}/{app_name}/crud.js"),
                ('vue_form.j2', f"frontend/src/views/system/{module_name}/{app_name}/components/{app_name}Save.vue"),
                # 菜单 SQL
                ('menu_sql.j2', "menu.sql"),
            ]
            
            # 生成空 __init__.py 文件
            generated_files = {
                f"backend/apps/{app_name}/__init__.py": "# -*- coding: utf-8 -*-\n# {{ table.table_comment or table.class_name }} App\n"
            }

            # 5. 生成文件内容
            generation_errors = []
            for template_name, output_path in files_to_generate:
                try:
                    content = render_template(template_name, context)
                    generated_files[output_path] = content
                    logger.info(f"Generated: {output_path}")
                except TemplateRenderError as e:
                    error_msg = f"模板渲染失败 [{template_name}]: {str(e)}"
                    logger.error(error_msg)
                    generation_errors.append(error_msg)
                except Exception as e:
                    error_msg = f"生成文件失败 [{template_name}]: {str(e)}"
                    logger.error(error_msg, exc_info=True)
                    generation_errors.append(error_msg)

            # 如果有生成错误，返回错误信息
            if generation_errors:
                return Response({
                    'code': 5001,
                    'msg': '代码生成部分失败',
                    'data': {
                        'errors': generation_errors,
                        'generated_files': list(generated_files.keys())
                    }
                }, status=status.HTTP_500_INTERNAL_SERVER_ERROR)

            # 6. 根据模式返回结果
            if is_preview:
                # 预览模式：返回JSON格式的所有文件内容
                return Response({
                    'code': 2000,
                    'msg': '预览成功',
                    'data': {
                        'files': generated_files,
                        'table_name': context['table_name'],
                        'business_name': context['business_name']
                    }
                }, status=status.HTTP_200_OK)
            else:
                # 下载模式：返回ZIP文件
                zip_buffer = io.BytesIO()

                with zipfile.ZipFile(zip_buffer, 'w', zipfile.ZIP_DEFLATED) as zip_file:
                    for output_path, content in generated_files.items():
                        try:
                            zip_file.writestr(output_path, content.encode('utf-8'))
                        except Exception as e:
                            logger.error(f"Failed to add {output_path} to ZIP: {str(e)}")

                # 准备返回 ZIP 文件
                zip_buffer.seek(0)
                filename = f"{context['business_name']}_generated_code.zip"

                response = FileResponse(
                    zip_buffer,
                    as_attachment=True,
                    filename=filename
                )
                response['Content-Type'] = 'application/zip'
                response['Content-Disposition'] = f'attachment; filename="{filename}"'

                return response

        except Exception as e:
            logger.error(f"生成代码时发生错误：{str(e)}", exc_info=True)
            return Response({
                'code': 5000,
                'msg': f'生成代码失败：{str(e)}'
            }, status=status.HTTP_500_INTERNAL_SERVER_ERROR)

    @action(methods=['get'], detail=True)
    def download_code(self, request, pk=None):
        """
        下载为指定 GenTable 生成的代码压缩包
        URL: GET /api/generator/gen-table/{id}/download_code/
        """
        gen_table = self.get_object()

        base_output_dir = getattr(settings, 'CODE_GENERATOR_OUTPUT_DIR',
                                  os.path.join(settings.BASE_DIR, 'generated_code'))
        table_output_dir = os.path.join(base_output_dir, str(gen_table.id))
        zip_filename = f"{gen_table.model_name}_generated_code.zip"
        zip_filepath = os.path.join(base_output_dir, zip_filename)

        if not os.path.exists(table_output_dir):
            return Response({
                'code': 4004,
                'msg': '未找到生成的代码文件，请先生成代码'
            }, status=status.HTTP_404_NOT_FOUND)

        try:
            with zipfile.ZipFile(zip_filepath, 'w', zipfile.ZIP_DEFLATED) as zipf:
                for root, dirs, files in os.walk(table_output_dir):
                    for file in files:
                        file_path = os.path.join(root, file)
                        arc_name = os.path.relpath(file_path, table_output_dir)
                        zipf.write(file_path, arcname=os.path.join(gen_table.model_name, arc_name))

            response = FileResponse(open(zip_filepath, 'rb'), as_attachment=True, filename=zip_filename)
            return response
        except Exception as e:
            logger.error(f'打包文件时出错：{str(e)}', exc_info=True)
            return Response({
                'code': 5000,
                'msg': f'打包文件时出错：{str(e)}'
            }, status=status.HTTP_500_INTERNAL_SERVER_ERROR)


# === 2. GenTableColumn 相关 ViewSet ===

class GenTableColumnFilterSet(filters.FilterSet):
    """GenTableColumn 过滤器"""
    column_name = filters.CharFilter(lookup_expr='icontains')
    column_comment = filters.CharFilter(lookup_expr='icontains')
    table_id = filters.NumberFilter(field_name='model')

    is_pk = filters.BooleanFilter()
    is_increment = filters.BooleanFilter()
    is_required = filters.BooleanFilter()
    is_insert = filters.BooleanFilter()
    is_edit = filters.BooleanFilter()
    is_list = filters.BooleanFilter()
    is_query = filters.BooleanFilter()

    class Meta:
        model = GenTableColumn
        fields = [
            'column_name', 'column_type', 'django_field_type',
            'is_pk', 'is_increment', 'is_required',
            'is_insert', 'is_edit', 'is_list', 'is_query',
            'query_type', 'show_type', 'model'
        ]


class GenTableColumnSerializer(CustomModelSerializer):
    """GenTableColumn 的序列化器"""

    class Meta:
        model = GenTableColumn
        fields = "__all__"
        read_only_fields = ["id"]


class GenTableColumnViewSet(CustomModelViewSet):
    """
    代码生成配置字段接口
    list: 查询指定表的所有字段 (可通过 ?table_id=<id> 过滤)
    create: 为指定表新增字段
    update: 修改字段
    retrieve: 字段详情
    destroy: 删除字段
    batch_save: 批量保存字段
    """
    serializer_class = GenTableColumnSerializer
    filterset_class = GenTableColumnFilterSet
    search_fields = ('column_name', 'column_comment')

    def get_queryset(self):
        queryset = GenTableColumn.objects.select_related('model').all()
        return queryset.order_by('sort', 'id')

    def perform_create(self, serializer):
        table_id = serializer.validated_data.get('model')
        if not table_id:
            raise ValidationError({"model": "This field is required when creating a column."})
        table_instance = get_object_or_404(GenTable, pk=table_id.id if hasattr(table_id, 'id') else table_id)
        serializer.save()

    @action(methods=['post'], detail=False, url_path='batch_save')
    def batch_save(self, request):
        """
        批量保存字段：先删除表所有字段，再批量插入
        URL: POST /api/generator/columns/batch_save/
        """
        try:
            table_id = request.data.get('table_id')
            columns_data = request.data.get('columns', [])

            if not table_id:
                raise ValidationError({"table_id": "This field is required."})

            if not isinstance(columns_data, list):
                raise ValidationError({"columns": "Must be a list."})

            gen_table = get_object_or_404(GenTable, pk=table_id)

            with transaction.atomic():
                GenTableColumn.objects.filter(model=gen_table).delete()

                columns_to_create = []
                for idx, col_data in enumerate(columns_data):
                    col_data['model'] = gen_table.id
                    serializer = GenTableColumnSerializer(data=col_data)
                    if not serializer.is_valid():
                        if settings.DEBUG:
                            print(f"Column {idx} validation failed: {col_data}")
                            print(f"Errors: {serializer.errors}")
                    serializer.is_valid(raise_exception=True)
                    columns_to_create.append(GenTableColumn(**serializer.validated_data))

                GenTableColumn.objects.bulk_create(columns_to_create)

            return Response({
                'code': 2000,
                'msg': f'成功保存 {len(columns_to_create)} 个字段',
                'data': {'count': len(columns_to_create)}
            }, status=status.HTTP_200_OK)

        except ValidationError as e:
            logger.warning(f"批量保存字段验证失败: {e}")
            if settings.DEBUG:
                logger.debug(f"Request data: {request.data}")
            return Response({
                'code': 4000,
                'msg': f'数据验证失败：{str(e)}',
                'data': None
            }, status=status.HTTP_400_BAD_REQUEST)
        except Exception as e:
            logger.error(f"批量保存字段出错：{str(e)}", exc_info=True)
            return Response({
                'code': 5000,
                'msg': f'保存失败：{str(e)}',
                'data': None
            }, status=status.HTTP_500_INTERNAL_SERVER_ERROR)
