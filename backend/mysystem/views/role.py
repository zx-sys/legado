# -*- coding: utf-8 -*-

"""
@Remark: 角色管理
"""
import django_filters
from django.db import transaction
from mysystem.models import Role, Menu, RoleMenuPermission, RoleMenuButtonPermission, FieldPermission
from mysystem.views.menu_button import MenuButtonSerializer
from mysystem.views.menu_field import MenuFieldSerializer
from rest_framework import serializers
from rest_framework.validators import UniqueValidator
from utils.common import get_parameter_dic, ast_convert
from utils.jsonResponse import DetailResponse, ErrorResponse
from utils.serializers import CustomModelSerializer
from utils.viewset import CustomModelViewSet


class RoleFilterSet(django_filters.rest_framework.FilterSet):
    """
    角色管理 过滤器
    """
    # 开始时间
    beginAt = django_filters.DateTimeFilter(field_name='create_time', lookup_expr='gte')  # 指定过滤的字段
    # 结束时间
    endAt = django_filters.DateTimeFilter(field_name='create_time', lookup_expr='lte')
    name = django_filters.CharFilter(field_name='name', lookup_expr='icontains')
    status = django_filters.CharFilter(field_name='status')

    class Meta:
        model = Role
        fields = ['beginAt', 'endAt', 'name', 'status']


class RoleSerializer(CustomModelSerializer):
    """
    角色-序列化器
    """

    class Meta:
        model = Role
        fields = "__all__"
        read_only_fields = ["id"]


class RoleMenuPermissionSerializer(CustomModelSerializer):
    """
    角色菜单权限-序列化器
    """

    class Meta:
        model = RoleMenuPermission
        fields = "__all__"
        read_only_fields = ["id"]


class RoleMenuButtonPermissionSerializer(CustomModelSerializer):
    """
    角色菜单按钮权限-序列化器
    """

    class Meta:
        model = RoleMenuButtonPermission
        fields = "__all__"
        read_only_fields = ["id"]


class FieldPermissionSerializer(CustomModelSerializer):
    """
    角色菜单列权限-序列化器
    """
    field_name = serializers.SerializerMethodField()

    @staticmethod
    def get_field_name(obj):
        return obj.field.field_name

    class Meta:
        model = FieldPermission
        fields = "__all__"
        read_only_fields = ["id"]


class RoleCreateUpdateSerializer(CustomModelSerializer):
    """
    角色管理 创建/更新时的列化器
    """
    key = serializers.CharField(max_length=50, validators=[UniqueValidator(queryset=Role.objects.all(), message="权限字符必须唯一")])
    name = serializers.CharField(max_length=50, validators=[UniqueValidator(queryset=Role.objects.all(), message="角色名称必须唯一")])

    def validate(self, attrs: dict):
        return super().validate(attrs)

    def save(self, **kwargs):
        data = super().save(**kwargs)
        return data

    class Meta:
        model = Role
        fields = '__all__'


class MenuPermissionSerializer(CustomModelSerializer):
    """
    菜单的按钮权限
    """
    menu_buttons = MenuButtonSerializer(many=True, read_only=True)
    menu_fields = MenuFieldSerializer(many=True, read_only=True)

    class Meta:
        model = Menu
        fields = '__all__'


class RoleViewSet(CustomModelViewSet):
    """
    角色管理接口
    list:查询
    create:新增
    update:修改
    retrieve:单例
    destroy:删除
    """
    queryset = Role.objects.all().order_by("id")
    serializer_class = RoleSerializer
    create_serializer_class = RoleCreateUpdateSerializer
    update_serializer_class = RoleCreateUpdateSerializer
    filterset_class = RoleFilterSet
    search_fields = ('name', 'key')

    def set_status(self, request):
        """禁用/启用"""
        reqData = get_parameter_dic(request)
        id = reqData.get("id", "")
        queryset = self.filter_queryset(self.get_queryset())
        instance = queryset.filter(id=id).first()
        if instance:
            instance.status = False if instance.status else True
            instance.save()
            return DetailResponse(data=None, msg="设置成功")
        else:
            return ErrorResponse(msg="未获取到数据")

    def role_id_to_menu(self, request, pk=None):
        """通过角色id获取该角色用于的菜单
        
        优化：使用 select_related 和 prefetch_related 避免N+1查询
        """
        role = self.get_object()
        
        # 使用 select_related 和 prefetch_related 优化查询
        queryset = Menu.objects.filter(
            status=True
        ).select_related(
            'parent'
        ).prefetch_related(
            'menu_buttons',
            'menu_fields'
        ).distinct().order_by("sort")
        
        serializer = MenuPermissionSerializer(queryset, many=True)
        return DetailResponse(data=serializer.data)


class RolePermissionSerializer(CustomModelSerializer):
    """
    菜单的按钮权限
    """
    role_menu_permission = RoleMenuPermissionSerializer(many=True, read_only=True)
    role_button_permission = RoleMenuButtonPermissionSerializer(many=True, read_only=True)
    role_field_permission = FieldPermissionSerializer(many=True, read_only=True)

    class Meta:
        model = Role
        fields = '__all__'


class RolePermissionViewSet(CustomModelViewSet):
    """
    角色权限管理接口
    list:查询
    create:新增
    update:修改
    retrieve:单例
    destroy:删除
    """
    queryset = Role.objects.filter(status=True).order_by("id")
    serializer_class = RolePermissionSerializer
    filterset_class = RoleFilterSet
    search_fields = ('name', 'key')

    @transaction.atomic
    def save_permission(self, request):
        """保存权限"""
        req_data = get_parameter_dic(request)
        role_id = req_data.get("role_id", "")
        queryset = self.filter_queryset(self.get_queryset())
        instance = queryset.filter(id=role_id).first()
        if instance:
            role_menu_permission_list = ast_convert(req_data.get("RoleMenuPermission", []))
            role_menu_button_permission_list = ast_convert(req_data.get("RoleMenuButtonPermission", []))
            field_permission_list = ast_convert(req_data.get("FieldPermission", []))

            try:
                # 1. 删除旧权限
                self._delete_old_permissions(role_id)

                # 2. 批量创建新权限
                stats = {
                    'menu': self._bulk_create_menu_permissions(role_menu_permission_list, request),
                    'button': self._bulk_create_button_permissions(role_menu_button_permission_list, request),
                    'field': self._bulk_create_field_permissions(field_permission_list, request)
                }

                return DetailResponse(data=stats, msg="保存成功")

            except serializers.ValidationError as e:
                raise ValueError(f"数据验证失败:{e.detail}")
            except Exception as e:
                raise ValueError(f"服务器错误:{str(e)}")

        else:
            return ErrorResponse(msg="未获取到数据")

    def _delete_old_permissions(self, role_id):
        """删除角色所有旧权限"""
        RoleMenuPermission.objects.filter(role_id=role_id).delete()
        RoleMenuButtonPermission.objects.filter(role_id=role_id).delete()
        FieldPermission.objects.filter(role_id=role_id).delete()

    def _bulk_create_menu_permissions(self, data, request):
        """批量创建菜单权限"""
        if not data:
            return 0

        serializer = RoleMenuPermissionSerializer(
            data=data,
            many=True,
            request=request
        )
        serializer.is_valid(raise_exception=True)
        serializer.save()
        return len(serializer.validated_data)

    def _bulk_create_button_permissions(self, data, request):
        """批量创建按钮权限"""
        if not data:
            return 0

        serializer = RoleMenuButtonPermissionSerializer(
            data=data,
            many=True,
            request=request
        )
        serializer.is_valid(raise_exception=True)
        serializer.save()
        return len(serializer.validated_data)

    def _bulk_create_field_permissions(self, data, request):
        """批量创建字段权限"""
        if not data:
            return 0
        serializer = FieldPermissionSerializer(
            data=data,
            many=True,
            request=request
        )
        serializer.is_valid(raise_exception=True)
        serializer.save()
        return len(serializer.validated_data)
