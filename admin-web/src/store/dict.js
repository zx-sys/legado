// src/store/modules/dict.js
import {defineStore} from 'pinia';
import Api from "@/api/api.js"
import {ElMessage} from 'element-plus'; // 用于提示

export const useDictStore = defineStore('dict', {
    state: () => ({
        dict: {}, // 存储格式: { 'dictType1': [{label: '启用', value: '1'}, ...], ... }
        isLoaded: false, // 标记是否已完成初始加载
        dictTypes: {},
    }),
    actions: {
        async initDict() {
            // 防止重复初始化
            if (this.isLoaded) {
                console.log('[Dict Store] Dictionaries already loaded, skipping init.');
                return;
            }

            try {
                // 1. 直接调用 API 获取所有已按 parentName 分组、组内按 sort 排序的字典项
                const allDictListResponse = await Api.getDictionaryNameList();

                // 2. 检查响应是否成功
                if (allDictListResponse.code === 2000) {

                    // 3. 从响应中提取核心数据数组
                    // 根据你的后端返回，数据在 allDictListResponse.data
                    const rawDataArray = allDictListResponse.data.data || [];
                    console.log('[Dict Store] Raw data array:', rawDataArray);
                    // 4. 转换数据格式以适应 this.dict 的结构 { 'dictType1': [...items...], ... }
                    const newDict = {};
                    const newDictTypes = {};

                    // 5. 遍历后端返回的数组，填充 newDict 对象
                    // 后端结构正好匹配前端需要的结构，直接赋值即可
                    if (Array.isArray(rawDataArray)) {
                        rawDataArray.forEach(dictGroup => {

                            const dictType = dictGroup.parentName; // 后端字段名
                            const dictLabel = dictGroup.parentLabel;
                            const dictItems = dictGroup.data || []; // 后端字段名

                            if (dictType) { // 确保 parentName 存在
                                newDict[dictType] = dictItems; // 直接赋值，保持组内排序
                                newDictTypes[dictType] = dictLabel;
                            }
                        });
                    }

                    // 6. 更新 Store 状态
                    this.dict = newDict;
                    this.dictTypes = newDictTypes;
                    console.log('[Dict Store] Updated dict:', newDict);
                    console.log('[Dict Store] Updated dictTypes:', newDictTypes);
                    this.isLoaded = true; // 标记为已加载

                } else {
                    console.error('[Dict Store] Failed to fetch dictionaries, API response code:', allDictListResponse.code, allDictListResponse.msg);
                    // 可以提示用户或记录日志
                    // ElMessage.error(`获取字典失败: ${allDictListResponse.msg}`);
                }
            } catch (error) {
                console.error('[Dict Store] Exception during dictionary initialization:', error);
                // 可以在这里添加用户提示
                // ElMessage.error('初始化字典时发生异常');
            }
        },

        /**
         * 加载指定类型的字典数据
         * @param {string[]} dictTypes - 需要加载的字典类型
         */
        async loadDict(dictTypes) {
            const typesToLoad = Array.isArray(dictTypes) ? dictTypes : [dictTypes];
            if (typesToLoad.length === 0) return;

            try {
                const requests = typesToLoad.map(type => Api.getDictionaryByParentName({'type': type}));
                const responses = await Promise.allSettled(requests); // 使用 allSettled 防止一个失败导致全部失败

                const newDict = {};
                responses.forEach((result, index) => {
                    const type = typesToLoad[index];
                    if (result.value.code === 2000) {
                        newDict[type] = result.value.data?.data || [];
                    } else {
                        console.error(`[Dict Store] Failed to load dict '${type}':`, result.reason || result.value?.msg);
                        newDict[type] = []; // 失败也存空数组，避免后续 undefined 错误
                        ElMessage.warning(`字典 ${type} 加载失败`);
                    }
                });

                // 更新状态
                this.dict = {...this.dict, ...newDict};
                // 只有在初始加载时才标记为 loaded
                // this.isLoaded = true; // 注意：这个逻辑移到 App.vue 更合适
            } catch (error) {
                console.error('[Dict Store] Batch load dicts error:', error);
                ElMessage.error('批量加载字典时发生错误');
            }
        },

        /**
         * 根据字典类型和值获取标签
         * @param {string} dictType - 字典类型
         * @param {*} value - 字典值
         * @param {string} defaultValue - 默认值
         * @returns {string} 标签
         */
        getDictLabel(dictType, value, defaultValue = '') {
            const dictItems = this.dict[dictType];
            if (!dictItems || !Array.isArray(dictItems)) {
                return String(value) || defaultValue;
            }
            const item = dictItems.find(i => i.value === String(value));
            return item ? item.label : (value !== undefined ? String(value) : defaultValue);
        },

        /**
         * 获取某个字典类型的完整列表
         * @param {string} dictType - 字典类型
         * @returns {Array} 字典项列表
         */
        getDictItems(dictType) {
            return this.dict[dictType] || [];
        },

        /**
         * 处理来自 WebSocket 的字典更新通知
         * @param {string[]}payload - WebSocket 消息体返回值，期望是字典名称的数组 string[]
         */
        handleDictUpdated(payload) {
            console.log("[Dict Store] Received dict update notification:", payload);
            if (Array.isArray(payload) && payload.length > 0) {
                // 重新加载被更新的字典
                this.loadDict(payload).then(() => {
                    console.log("[Dict Store] Updated dicts reloaded:", payload);
                    ElMessage.info(`字典 ${payload.join(', ')} 已更新`);
                    // 可以在这里 emit 一个事件，通知相关组件刷新视图
                    // this.$emit('dictRefreshed', types); // 如果 Store 支持 emit 或使用 mitt
                });
            } else {
                console.warn("[Dict Store] Invalid dict update payload:", payload);
            }
        },
    }
});
