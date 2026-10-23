# -*- coding: utf-8 -*-

"""
@Remark: 菜单模块
"""
from rest_framework import serializers

from mysystem.models import Menu, MenuButton, Button, RoleMenuPermission
from mysystem.views.menu_button import MenuButtonSerializer
from utils.jsonResponse import SuccessResponse, DetailResponse, ErrorResponse
from utils.serializers import CustomModelSerializer
from utils.viewset import CustomModelViewSet
from utils.common import get_parameter_dic, ast_convert


class MenuSerializer(CustomModelSerializer):
    """
    菜单表的简单序列化器
    """

    class Meta:
        model = Menu
        fields = "__all__"
        # exclude = ('description', 'creator', 'modifier')
        read_only_fields = ["id"]


class MenuCreateSerializer(CustomModelSerializer):
    """
    菜单表的创建序列化器
    """
    name = serializers.CharField(required=False)

    class Meta:
        model = Menu
        fields = "__all__"
        #exclude = ('description', 'creator', 'modifier')
        read_only_fields = ["id"]


class MenuTreeSerializer(CustomModelSerializer):
    """
    菜单表的树形序列化器
    """
    children = serializers.SerializerMethodField(read_only=True)
    menu_buttons_name = serializers.SerializerMethodField(read_only=True)
    menu_buttons = MenuButtonSerializer(read_only=True,many=True)

    def get_children(self, instance):
        queryset = Menu.objects.filter(parent=instance.id).filter(status=1)
        if queryset:
            serializer = MenuTreeSerializer(queryset, many=True)
            return serializer.data
        else:
            return None

    def get_menu_buttons_name(self, instance):
        queryset = MenuButton.objects.filter(menu=instance.id).values_list('name', flat=True)
        if queryset:
            return queryset
        else:
            return None

    class Meta:
        model = Menu
        fields = "__all__"
        read_only_fields = ["id"]


class WebRouterSerializer(CustomModelSerializer):
    """
    前端菜单路由的简单序列化器
    """

    class Meta:
        model = Menu
        fields = ('id', 'parent', 'icon', 'sort', 'web_path', 'name', 'type','link_url', 'component','component_name', 'cache', 'visible', 'status')
        read_only_fields = ["id"]

class MenuViewSet(CustomModelViewSet):
    """
    菜单管理接口
    list:查询
    create:新增
    update:修改
    retrieve:详情
    destroy:删除
    """
    queryset = Menu.objects.all().order_by('sort')
    serializer_class = MenuSerializer
    create_serializer_class = MenuCreateSerializer
    update_serializer_class = MenuCreateSerializer
    filterset_fields = ['name', 'status','visible']
    search_fields = ['name','web_path']

    def update_sort(self,request):
        """菜单排序（上移、下移、拖拽）"""
        reqData = get_parameter_dic(request)
        menu_list = ast_convert(reqData.get("menus", []))
        if not menu_list:return ErrorResponse(msg="没有提供数据")
        menu_ids = [item['id'] for item in menu_list]
        if not menu_ids:return ErrorResponse(msg="参数错误")
        menus = Menu.objects.filter(id__in=menu_ids)
        menu_dict = {menu.id: menu for menu in menus}

        # 更新 sort 字段
        updated_menus = []
        for item in menu_list:
            menu_id = item['id']
            sort_value = item['sort']
            parent_value = item['parent'] if item['parent'] else None
            if menu_id in menu_dict:
                menu_dict[menu_id].sort = sort_value
                menu_dict[menu_id].parent_id = parent_value
                updated_menus.append(menu_dict[menu_id])

        if not updated_menus:
            return ErrorResponse(msg="无此菜单") 

        Menu.objects.bulk_update(updated_menus, ['sort','parent_id'])

        return DetailResponse(msg="操作成功")

    def menu_tree(self, request):
        """用于菜单添加修改中获取父级菜单
        
        优化：一次性加载所有菜单后在内存中构建树形结构，避免 N+1 查询
        """
        # 一次性加载所有启用状态的菜单
        all_menus = list(Menu.objects.filter(status=1).order_by('sort'))
        
        if not all_menus:
            return SuccessResponse(data=[], msg="获取成功")
        
        # 使用字典构建树形结构
        menu_dict = {m.id: m for m in all_menus}
        
        # 构建递归函数，在内存中构建子菜单
        def build_tree(menu, serialized=True):
            """构建菜单树
            
            Args:
                menu: 菜单实例
                serialized: 是否序列化输出
            
            Returns:
                菜单数据（序列化或原始实例）
            """
            # 查找当前菜单的子菜单
            children = [m for m in all_menus if m.parent_id == menu.id]
            
            if children:
                # 序列化子菜单
                children_data = []
                for child in children:
                    child_data = build_tree(child, serialized)
                    children_data.append(child_data)
                
                if serialized:
                    # 如果需要序列化，只返回需要的字段
                    return {
                        'id': menu.id,
                        'name': menu.name,
                        'icon': menu.icon,
                        'sort': menu.sort,
                        'status': menu.status,
                        'visible': menu.visible,
                        'type': menu.type,
                        'web_path': menu.web_path,
                        'component': menu.component,
                        'children': children_data,
                        'menu_buttons': MenuButtonSerializer(menu.menu_buttons.all(), many=True).data
                    }
                else:
                    menu.children = [build_tree(c, serialized) for c in children]
                    return menu
            else:
                if serialized:
                    return {
                        'id': menu.id,
                        'name': menu.name,
                        'icon': menu.icon,
                        'sort': menu.sort,
                        'status': menu.status,
                        'visible': menu.visible,
                        'type': menu.type,
                        'web_path': menu.web_path,
                        'component': menu.component,
                        'children': None,
                        'menu_buttons': MenuButtonSerializer(menu.menu_buttons.all(), many=True).data
                    }
                else:
                    menu.children = None
                    return menu
        
        # 找到根菜单（parent_id 为 None 或 0 的菜单）
        root_menus = [m for m in all_menus if not m.parent_id]
        
        # 构建树形数据
        tree_data = [build_tree(menu, serialized=True) for menu in root_menus]
        
        return SuccessResponse(data=tree_data, msg="获取成功")

    # @action(methods=['get'],extra_filter_backends=[],detail=False,step_permission=True)#会自动生成/api/system/menu/web_router/的路由
    def web_router(self, request):
        """用于前端获取当前角色的路由"""
        user = request.user
        if user.is_superuser:
            queryset = self.queryset.filter(status=True).order_by("sort")
        else:
            role_ids = user.role.values_list('id', flat=True)
            menuIds  = RoleMenuPermission.objects.filter(role__in=role_ids).values_list('menu_id', flat=True)
            queryset = self.filter_queryset(Menu.objects.filter(id__in=menuIds, status=True))
        serializer = WebRouterSerializer(queryset, many=True, request=request)
        return DetailResponse(data=serializer.data, msg="获取成功")
