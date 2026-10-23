# -*- coding: utf-8 -*-

"""
@Remark: 字典管理
"""
import logging
from itertools import groupby
from operator import itemgetter

import django_filters
from asgiref.sync import async_to_sync
from channels.layers import get_channel_layer
from mysystem.models import Dictionary
from rest_framework.views import APIView
from utils.common import get_parameter_dic
from utils.jsonResponse import SuccessResponse, ErrorResponse, DetailResponse
from utils.serializers import CustomModelSerializer
from utils.viewset import CustomModelViewSet


class DictionaryFilterset(django_filters.rest_framework.FilterSet):
    """
    过滤器
    """
    parent_isnull = django_filters.BooleanFilter(field_name='parent', lookup_expr="isnull")

    class Meta:
        model = Dictionary
        fields = ['id', 'label', 'status', 'parent_isnull', 'parent', 'value']


class DictionarySerializer(CustomModelSerializer):
    """
    字典-序列化器
    """

    class Meta:
        model = Dictionary
        fields = "__all__"
        read_only_fields = ["id"]


class DictionaryViewSet(CustomModelViewSet):
    """
    字典管理接口
    list:查询
    create:新增
    update:修改
    retrieve:单例
    destroy:删除
    """
    queryset = Dictionary.objects.all()
    serializer_class = DictionarySerializer
    filterset_class = DictionaryFilterset
    search_fields = ('label', 'value')

    @staticmethod
    def determine_dict_type_for_notification(instance):
        """
        确定用于通知前端更新的字典类型。
        规则：优先使用 parent_name，如果为空，则使用 value。
        """
        dict_type = getattr(instance, 'parent_name', None)
        if not dict_type:
            dict_type = getattr(instance, 'value', None)
        return dict_type

    @staticmethod
    def broadcast_dict_update(dict_type):
        """
        通过 WebSocket 广播字典更新通知。
        """
        if not dict_type:
            print("[Warning] Could not determine dict_type for broadcasting update.")
            return

        channel_layer = get_channel_layer()
        if channel_layer:
            try:
                message = {
                    "types": [dict_type],
                    "msg_type": 'dictUpdated'
                }
                print(f"[Info] Broadcasting dict update for type: {dict_type}")

                # 发送到 'dict_updates' 组 (需要消费者已加入此组)
                # 使用 async_to_sync 包装异步的 channel_layer 方法，使其能在同步视图中调用
                async_to_sync(channel_layer.group_send)(
                    "notifications_all_users",
                    {
                        'type': 'push_message_to_all',
                        'json': message
                    }
                )
            except Exception as e:
                print(f"[Error] Failed to broadcast dict update via WebSocket: {e}")
        else:
            print("[Warning] Channel layer is not configured. Skipping WebSocket broadcast.")

    def perform_create(self, serializer):
        """
        保存新建的字典项，并广播更新通知。
        """
        # 1. 保存对象到数据库
        instance = serializer.save()

        # 2. 确定需要通知更新的字典类型
        dict_type = self.determine_dict_type_for_notification(instance)

        # 3. 调用广播方法
        self.broadcast_dict_update(dict_type)

        # 注意：perform_create 本身不需要返回值，保存由父类处理

    def perform_update(self, serializer):
        """
        保存更新的字典项，并广播更新通知。
        """
        # 1. 保存更新的对象到数据库
        instance = serializer.save()

        # 更新子项的parent_name
        instance.sublist.update(parent_name=instance.value)

        # 2. 确定需要通知更新的字典类型
        dict_type = self.determine_dict_type_for_notification(instance)

        # 3. 调用广播方法
        self.broadcast_dict_update(dict_type)

    def set_status(self, request):
        """禁用/启用"""
        req_data = get_parameter_dic(request)
        dict_id = req_data.get("id", "")
        queryset = self.filter_queryset(self.get_queryset())
        instance = queryset.filter(id=dict_id).first()
        if instance:
            instance.status = False if instance.status else True
            instance.save()
            return DetailResponse(data=None, msg="设置成功")
        else:
            return ErrorResponse(msg="未获取到数据")

    def getDictionaryByParentName(self, request, *args, **kwargs):
        req_data = get_parameter_dic(request)
        parent_name = req_data.get('type')
        # 2. 基本校验
        if not parent_name:
            return ErrorResponse(msg="缺少必要的参数 type")

        try:
            # 3. 查询数据库 - 查找 parent_name 等于传入值的所有启用状态的字典项
            # 假设你有一个 status 字段，'1' 表示启用，'0' 表示禁用
            dictionaries = Dictionary.objects.filter(parent_name=parent_name, status='1')

            # 4. 序列化数据
            serializer = DictionarySerializer(dictionaries, many=True)  # many=True 因为是查询集

            # 5. 返回成功响应 (遵循你前端期望的格式)
            return SuccessResponse(data=serializer.data)

        except Exception as e:
            # 6. 异常处理
            print(f"[Error] 获取字典项失败 (parent_name={parent_name}): {e}")  # 记录日志
            return ErrorResponse(msg=f"获取字典项时发生错误: {str(e)}")

    def getAllDictList(self, request):
        """
        获取所有启用(status=1)的字典项，并按父项分组。
        返回格式: [
            {
                "parentName": <父项的 value>,
                "parentLabel": <父项的 label>,
                "data": [<子项1>, <子项2>, ...]
            },
            ...
        ]
        """
        try:
            # 1. 获取所有启用(status=1)的字典项
            all_dictionaries = Dictionary.objects.filter(status=True)

            # 2. 在 Python 内存中分离父节点和子节点
            # 使用 select_related 可以优化后续 parent_name 访问？在这里可能不太适用，
            # 因为我们是在序列化后处理。但如果 parent_name 是 CharField 存值，
            # select_related('parent') 可能有助于减少查询（如果 parent_name 来自 parent.value）。
            # 但根据你的描述，parent_name 是独立存储的 CharField，所以不需要复杂的关联查询。

            # --- 修改点 1: 获取所有数据 ---
            serializer = DictionarySerializer(all_dictionaries, many=True)
            serialized_data = serializer.data

            # --- 修改点 2: 分离父节点和子节点 ---
            # 使用字典推导和 filter 更清晰
            parent_dict_map = {
                item['value']: {'name': item['value'], 'label': item['label']}
                for item in serialized_data
                if item.get('parent_name') is None or item.get('parent_name') == ''  # 根据你的数据库实际情况调整判断
            }

            # 筛选出子节点 (parent_name 不为空)
            child_items = [
                item for item in serialized_data
                if item.get('parent_name') is not None and item.get('parent_name') != ''
            ]

            # --- 修改点 3: 对子节点按 parent_name 和 sort 排序 ---
            # 先按 parent_name 排序，再按 sort 排序
            sorted_child_items = sorted(child_items, key=itemgetter('parent_name', 'sort'))

            # --- 修改点 4: 使用 groupby 分组 ---
            grouped_result = []
            # groupby 需要数据已经按 key 排序
            for parent_name_key, group_iter in groupby(sorted_child_items, key=itemgetter('parent_name')):
                # 从父节点映射中获取 label
                parent_info = parent_dict_map.get(parent_name_key, {'name': parent_name_key, 'label': '未知父项'})

                grouped_result.append({
                    "parentName": parent_info['name'],  # 父项的 value
                    "parentLabel": parent_info['label'],  # 父项的 label
                    "data": list(group_iter)  # 当前 parent_name 下的所有子项
                })

            # --- 可选：如果需要保证父项的顺序（比如按 sort 或 label），可以对 grouped_result 再排序 ---
            # 例如，如果你想按父项的 value (parentName) 排序:
            # grouped_result.sort(key=itemgetter('parentName'))

            return SuccessResponse(data=grouped_result)

        except Exception as e:
            logger = logging.getLogger(__name__)  # 建议使用 logger 而不是 print
            logger.error(f"[Error] 获取所有字典类型失败: {e}", exc_info=True)  # exc_info=True 记录堆栈
            return ErrorResponse(msg=f"获取字典类型时发生错误: {str(e)}")


class GetDictionaryInfoView(APIView):
    """
    get:
    获取字典信息
    参数：code 分组编码（value）
    """
    authentication_classes = []
    permission_classes = []

    def get(self, request):
        code = get_parameter_dic(request)['code']
        if not all([code]):
            return ErrorResponse(msg="params error")
        queryset = Dictionary.objects.filter(parent__value=code, status=True).order_by("sort")
        data = []
        if queryset:
            for m in queryset:
                data.append({
                    'id': m.id,
                    'label': m.label,
                    'value': m.value
                })
        return DetailResponse(data=data)


class GetDictionaryAllView(APIView):
    """
    获取字典
    """
    authentication_classes = []
    permission_classes = []

    def get(self, request):
        queryset = Dictionary.objects.filter(parent__isnull=True, status=True).order_by("sort")
        data = []
        for instance in queryset:
            data.append(
                {
                    "id": instance.id,
                    "label": instance.label,
                    "value": instance.value,
                    "children": list(
                        Dictionary.objects.filter(parent=instance.id).filter(status=True).order_by("sort").values("id", "label", "value")
                    ),
                }
            )
        return SuccessResponse(data=data, msg="获取成功", total=len(data))
