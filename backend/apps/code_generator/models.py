# apps/code_generator/models.py (假设你的 app 名为 code_generator)
from django.db import models
from django.utils.translation import gettext_lazy as _
from utils.models import CoreModel


class GenTable(CoreModel):
    # 数据库层面的信息
    app_name = models.CharField(max_length=64, verbose_name=_("应用名称"))
    model_name = models.CharField(max_length=100, verbose_name=_("模型类名"))
    model_comment = models.CharField(max_length=500, blank=True, verbose_name=_("模型描述"))
    table_name = models.CharField(max_length=200, unique=True, verbose_name=_("表名称"))  # 数据库中的表名，应唯一

    # 父菜单关联
    parent_menu = models.ForeignKey(
        to='mysystem.Menu',
        on_delete=models.SET_NULL,
        null=True,
        blank=True,
        db_constraint=False,
        verbose_name=_("父菜单目录"),
        help_text=_("选择模块的父菜单目录")
    )

    # 子模型关联信息 (如果需要支持树形或主从表结构)
    sub_model_name = models.CharField(max_length=64, blank=True, null=True, verbose_name=_("关联子模型"))
    sub_model_fk_name = models.CharField(max_length=64, blank=True, null=True, verbose_name=_("子模型关联的外键名"))

    # 审计字段
    author = models.CharField(max_length=50, blank=True, null=True, verbose_name=_("作者"))
    remark = models.CharField(max_length=500, blank=True, null=True, verbose_name=_("备注"))

    class Meta:
        db_table = 'gen_table'  # 明确指定数据库表名
        verbose_name = _("代码生成配置表")
        verbose_name_plural = _("代码生成配置表")

    def __str__(self):
        # 更清晰的字符串表示
        return f"[{self.model_name}] {self.model_comment}"


class GenTableColumn(CoreModel):
    # 外键关联到 GenTable
    model = models.ForeignKey(
        GenTable,
        on_delete=models.CASCADE,
        related_name='columns',  # 反向查询: gen_table_instance.columns.all()
        verbose_name=_("归属类名")
    )

    # 数据库列信息
    column_name = models.CharField(max_length=200, verbose_name=_("列名称"))  # 数据库中的列名, e.g., 'first_name'
    column_comment = models.CharField(max_length=500, blank=True, verbose_name=_("列描述"))  # 列的注释
    column_type = models.CharField(
        max_length=100,
        verbose_name=_("数据库列类型"),
        choices=[
            # --- 字符串 / 文本 类型 ---
            ('VARCHAR', 'VARCHAR'),  # 可变长度字符串 (最常用)
            ('TEXT', 'TEXT'),  # 长文本 (常用)

            # --- 整数类型 ---
            ('TINYINT', 'TINYINT'),  # 非常小的整数 (常用于布尔)
            ('INT', 'INT'),  # 标准整数 (最常用)

            # --- 浮点数 / 十进制数 类型 ---
            ('DOUBLE', 'DOUBLE'),  # 双精度浮点数
            ('DECIMAL', 'DECIMAL'),  # 精确的小数 (常用)

            # --- 日期 / 时间 类型 ---
            ('DATE', 'DATE'),  # 日期 (YYYY-MM-DD)
            ('DATETIME', 'DATETIME'),  # 日期和时间 (最常用)
            ('TIMESTAMP', 'TIMESTAMP'),  # 时间戳 (常用)

            # --- 其他常用类型 ---
            ('BOOLEAN', 'BOOLEAN'),  # 布尔值 (TINYINT(1) 的别名)
            ('JSON', 'JSON'),  # JSON 数据 (MySQL 5.7+)
        ])  # 数据库原始类型, e.g., 'VARCHAR', 'INT'

    # Django 模型字段映射 (修正命名以匹配 Django)
    django_field_type = models.CharField(
        max_length=100,
        verbose_name=_("Django模型字段类型"),
        choices=[
            ('CharField', _('字符串 (String)')),  # 对应 String/Text
            ('IntegerField', _('整数 (Integer)')),  # 对应 Integer
            ('BigIntegerField', _('大整数 (Long)')),  # 对应 Long
            ('FloatField', _('浮点数 (Double)')),  # 对应 Double
            ('DecimalField', _('十进制数 (BigDecimal)')),  # 对应 BigDecimal
            ('DateTimeField', _('日期时间 (Date)')),  # 对应 Date
            ('BooleanField', _('布尔值 (Boolean)')),  # 对应 Boolean
        ]
    )  # 映射到的 Django Field 类型, e.g., 'CharField', 'IntegerField', 'DateTimeField'

    # 布尔标志位 (使用 BooleanField 更符合 Django 和数据库习惯)
    is_pk = models.BooleanField(default=False, verbose_name=_("是否主键"))
    is_increment = models.BooleanField(default=False, verbose_name=_("是否自增"))
    is_required = models.BooleanField(default=False, verbose_name=_("是否必填"))
    is_insert = models.BooleanField(default=True, verbose_name=_("是否为插入字段"))
    is_edit = models.BooleanField(default=True, verbose_name=_("是否编辑字段"))
    is_list = models.BooleanField(default=False, verbose_name=_("是否列表字段"))
    is_query = models.BooleanField(default=False, verbose_name=_("是否查询字段"))

    # 查询和前端显示配置
    query_type = models.CharField(
        max_length=20,
        choices=[
            ('exact', _('等于 (=)')),  # 精确匹配
            ('not_exact', _('不等于 (!=)')),  # 不等于
            ('gt', _('大于 (>)')),  # 大于
            ('gte', _('大于等于 (>=)')),  # 大于等于
            ('lt', _('小于 (<)')),  # 小于
            ('lte', _('小于等于 (<=)')),  # 小于等于
            ('contains', _('包含 (LIKE)')),  # 模糊查询（如 %value%）
            ('startswith', _('以...开头 (LIKE)')),  # 如 value%
            ('endswith', _('以...结尾 (LIKE)')),  # 如 %value
            ('in', _('IN 查询')),  # 多值匹配（如 IN (1,2,3)）
            ('is_null', _('是否为空 (IS NULL)')),  # 判断是否为 NULL
        ],
        default='exact',
        verbose_name=_("查询方式")
    )

    show_type = models.CharField(
        max_length=50,
        choices=[
            ('input', _('文本框 (Input)')),
            ('textarea', _('文本域 (Textarea)')),
            ('select', _('下拉框 (Select)')),
            ('radio', _('单选框 (Radio)')),
            ('checkbox', _('复选框 (Checkbox)')),
            ('datetime', _('日期时间 (DateTime)')),
            ('uploadImg', _('上传图片 (UploadImg)')),
            ('uploadFile', _('上传文件 (UploadFile)')),
            ('editor', _('富文本 (Editor)'))
        ],
        default='input',
        verbose_name=_("显示类型 (前端组件)")
    )

    dict_json = models.CharField(
        max_length=500,
        blank=True,
        default='',
        verbose_name=_("字典项json")
        # 代码生成器可根据此字段生成选项来渲染下拉框/单选框/复选框。
    )

    sort = models.IntegerField(default=0, verbose_name=_("排序"))

    class Meta:
        db_table = 'gen_table_column'  # 明确指定数据库表名
        verbose_name = _("代码生成字段配置")
        verbose_name_plural = _("代码生成字段配置")
        ordering = ['sort', 'id']  # 默认按排序和ID排列

    def __str__(self):
        return f"{self.column_name} ({self.column_comment}) on {self.model.model_comment}"

    # 辅助方法 (直接返回布尔值)
    def is_primary_key(self):
        return self.is_pk

    def is_auto_increment(self):
        return self.is_increment

    def is_mandatory(self):
        return self.is_required

    def can_insert(self):
        return self.is_insert

    def can_update(self):
        return self.is_edit

    def show_in_list(self):
        return self.is_list

    def is_searchable(self):
        return self.is_query
