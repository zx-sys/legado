# apps/code_generator/admin.py

from django.contrib import admin
from .models import GenTable, GenTableColumn


class GenTableColumnInline(admin.TabularInline):
    """
    内联编辑 GenTable 的字段 (GenTableColumn)
    """
    model = GenTableColumn
    # 显示常用且关键的字段，方便在表配置页直接编辑
    fields = (
        'column_name',
        'column_comment',
        'column_type',         # 数据库列类型
        'django_field_type',   # 映射到的 Django 字段类型
        'is_pk',
        'is_required',
        'is_insert',
        'is_edit',
        'is_list',
        'is_query',
        'query_type',
        'show_type',           # 显示类型 (前端组件)
        'dict_json',           # 字典项配置
        'sort'
    )
    # ordering = ('sort',)
    extra = 1


@admin.register(GenTable)
class GenTableAdmin(admin.ModelAdmin):
    """
    管理代码生成主表 (GenTable) 的后台界面
    """
    list_display = (
        'model_name',      # 更符合 Django 模型概念
        'model_comment',   # 显示模型描述
        'app_name',        # 显示所属 App
        'table_name',      # 显示数据库表名
        'author',          # 显示作者
        'create_time'
    )
    list_filter = (
        'app_name',        # 按 App 筛选
        'author',          # 按作者筛选
        'create_time',
        # 'tpl_category', # 如果需要，可以添加其他筛选字段
    )
    search_fields = (
        'model_name',      # 搜索模型名
        'model_comment',   # 搜索模型描述
        'app_name',        # 搜索 App 名
        'table_name',      # 搜索表名
        'author',          # 搜索作者
        'remark'           # 搜索备注
    )
    # fieldsets 可根据需要配置，例如分组显示基本信息和关联信息
    # fieldsets = (
    #     ("基本信息", {
    #         'fields': ('app_name', 'model_name', 'model_comment', 'table_name', 'author')
    #     }),
    #     ("关联配置", {
    #         'fields': ('sub_model_name', 'sub_model_fk_name'),
    #         'classes': ('collapse',) # 可折叠
    #     }),
    #     ("其他信息", {
    #         'fields': ('remark',),
    #     }),
    # )
    inlines = [GenTableColumnInline]


@admin.register(GenTableColumn)
class GenTableColumnAdmin(admin.ModelAdmin):
    """
    管理代码生成字段配置 (GenTableColumn) 的后台界面
    """
    list_display = (
        'column_name',
        'column_comment',
        'model',              # 关联的模型 (会显示为链接)
        'column_type',        # 数据库列类型
        'django_field_type',  # 映射到的 Django 字段类型
        'is_pk',
        'is_required',
        'show_type',          # 显示类型 (前端组件)
        'query_type',         # 查询类型
        'sort'
    )

    list_filter = (
        'model',              # 按关联模型筛选
        'is_pk',
        'is_required',
        'is_insert',
        'is_edit',
        'is_list',
        'is_query',
        'show_type',
        'query_type',
        'django_field_type',
        'column_type',
    )

    search_fields = (
        'column_name',
        'column_comment',
        'model__model_name',     # 支持搜索关联模型的名称
        'model__app_name',       # 支持搜索关联模型所属的 App 名称
        'model__table_name',     # 支持搜索关联模型对应的表名
    )
    # 可以根据需要添加 date_hierarchy
    # date_hierarchy = 'create_time' # 如果需要按日期筛选

    ordering = ('model', 'sort')