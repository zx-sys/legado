# serializers.py

from rest_framework import serializers
from .models import GenTable, GenTableColumn


# -----------------------------
# GenTable Serializers
# -----------------------------

class GenTableSimpleSerializer(serializers.ModelSerializer):
    """用于列表显示的简化版 GenTable 序列化器"""
    # 添加一个自定义字段来显示列的数量
    columns_count = serializers.SerializerMethodField()
    parent_menu_name = serializers.CharField(source='parent_menu.name', read_only=True, allow_null=True)

    class Meta:
        model = GenTable
        # 根据最新的 GenTable 模型字段定义进行调整
        # 注意：CoreModel 的字段 ('create_by', 'create_time', 'update_by', 'update_time', 'dept_belong') 会自动包含在 '__all__' 或 fields 中
        fields = (
            'id', 'app_name', 'model_name', 'model_comment', 'table_name',
            'parent_menu', 'parent_menu_name',
            'sub_model_name', 'sub_model_fk_name',
            'author', 'remark', 'columns_count',
            'create_time', 'update_time', 'create_by', 'update_by'  # CoreModel 字段
        )
        read_only_fields = ('create_time', 'update_time', 'create_by', 'update_by')  # 如果这些不应被前端修改

    @staticmethod
    def get_columns_count(obj):
        """获取并返回关联列的数量"""
        # 使用 prefetch_related('columns') 可以优化性能
        return obj.columns.count()


class GenTableDetailSerializer(serializers.ModelSerializer):
    """用于详情显示和更新的 GenTable 序列化器，包含列信息"""

    # 可以在这里添加嵌套序列化器来显示关联的列
    # columns = GenTableColumnListSerializer(many=True, read_only=True)

    class Meta:
        model = GenTable
        fields = '__all__'  # 包含 GenTable 的所有字段
        # CoreModel 的审计字段也会被包含在 '__all__' 中
        # read_only_fields = ('create_time', 'update_time') # 如果这些不应被前端修改


class GenTableCreateSerializer(serializers.ModelSerializer):
    """
    用于创建 GenTable 的序列化器。
    不包含 columns 字段（关联列后续添加）和 CoreModel 的审计字段。
    字段列表严格基于最新的 GenTable 模型定义。
    """

    class Meta:
        model = GenTable
        # 明确列出创建时需要用户提供的字段
        # 排除 'id', 'columns' 以及 CoreModel 的审计字段 ('create_by', 'create_time', 'update_by', 'update_time', 'dept_belong')
        fields = [
            'app_name',  # CharField, 必填
            'model_name',  # CharField, 必填
            'model_comment',  # CharField, blank=True, 可选
            'table_name',  # CharField, unique=True, 必填
            'parent_menu',  # ForeignKey, null=True, blank=True, 可选
            'sub_model_name',  # CharField, blank=True, null=True, 可选
            'sub_model_fk_name',  # CharField, blank=True, null=True, 可选
            'author',  # CharField, blank=True, null=True, 可选
            'remark',  # CharField, blank=True, null=True, 可选
        ]

    def validate_table_name(self, value):
        """验证表名唯一性"""
        if GenTable.objects.filter(table_name=value).exists():
            raise serializers.ValidationError("具有此表名的配置已存在。")
        return value

    def validate_model_name(self, value):
        """验证模型名唯一性 (可选，但推荐)"""
        if GenTable.objects.filter(model_name=value).exists():
            raise serializers.ValidationError("具有此模型名的配置已存在。")
        return value


# -----------------------------
# GenTableColumn Serializers
# -----------------------------

# --- 基础序列化器 ---
class GenTableColumnBaseSerializer(serializers.ModelSerializer):
    """
    GenTableColumn 模型的基础序列化器。
    包含大部分通用字段，排除 CoreModel 审计字段。
    """

    class Meta:
        model = GenTableColumn

        # 显式列出字段 (更推荐，更清晰):
        fields = [
            'id', 'model', 'column_name', 'column_comment', 'column_type',
            'django_field_type', 'is_pk', 'is_increment', 'is_required',
            'is_insert', 'is_edit', 'is_list', 'is_query', 'query_type',
            'show_type', 'dict_json', 'sort'
        ]
        read_only_fields = ('id',)  # id 通常由数据库生成


# --- 列表序列化器 ---
class GenTableColumnListSerializer(GenTableColumnBaseSerializer):
    """
    用于列出 GenTableColumn 的序列化器。
    """
    # 可选：在列表中包含关联模型名和应用名
    app_name = serializers.CharField(source='model.app_name', read_only=True)
    model_name = serializers.CharField(source='model.model_name', read_only=True)

    class Meta(GenTableColumnBaseSerializer.Meta):
        # 可以选择性地覆盖 fields 或 exclude 来控制列表显示的字段
        # 例如，列表可能不需要 dict_json
        # fields = ['id', 'column_name', 'column_comment', 'column_type', 'is_pk', 'app_name', 'model_name']
        pass  # 继承父类 Meta


# --- 详情序列化器 ---
class GenTableColumnDetailSerializer(GenTableColumnBaseSerializer):
    """
    用于显示单个 GenTableColumn 详细信息的序列化器。
    """

    class Meta(GenTableColumnBaseSerializer.Meta):
        # Detail 通常包含所有非敏感字段，可以继承 Base 的 Meta
        pass  # 继承父类 Meta


# --- 创建序列化器 ---
class GenTableColumnCreateSerializer(GenTableColumnBaseSerializer):
    """
    用于创建 GenTableColumn 的序列化器。
    'model' 字段需要在创建时提供（通常是外键 ID）。
    """

    class Meta(GenTableColumnBaseSerializer.Meta):
        # fields 已经在 BaseSerializer.Meta.exclude 中定义好了
        # 这里不需要再重复定义 fields，除非你想覆盖
        # 注意：'model' 字段必须包含在内，以便创建时关联
        # 如果使用 exclude，则 'model' 字段会被包含进来
        pass


# --- 更新序列化器 ---
class GenTableColumnUpdateSerializer(GenTableColumnBaseSerializer):
    """
    用于更新 GenTableColumn 的序列化器。
    可以选择性地将某些字段设为只读。
    """

    class Meta(GenTableColumnBaseSerializer.Meta):
        # 示例：禁止更新关联模型和列名 (根据需要启用/禁用)
        # read_only_fields = GenTableColumnBaseSerializer.Meta.read_only_fields + (
        #     'model', 'column_name'
        # )
        pass  # 默认允许更新除 id 外的所有字段 (由 exclude 控制)
