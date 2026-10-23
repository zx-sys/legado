<template>
    <div :class="{'ly-is-full':isFull}" class="lycontainer">
        <div class="lycontainer-wrapper">
            <div class="lycontainer-right">
                <el-card class="tableSelect" ref="tableSelect" shadow="hover" v-if="crudOptions.searchBar.showSearchBar">
                    <lySearchBar :model="formInline" @search="search" @reset="handleReset">
                        <template #default>
                            <el-form-item label="任务名称">
                                <el-input v-model.trim="formInline.job_name" maxlength="60" clearable style="width:150px" placeholder="任务名称"></el-input>
                            </el-form-item>
                            <el-form-item label="执行状态">
                                <el-select v-model="formInline.status" placeholder="请选择" clearable style="width: 100px">
                                    <el-option label="成功" value="0" />
                                    <el-option label="失败" value="1" />
                                </el-select>
                            </el-form-item>
                            <el-form-item label="执行时间">
                                <el-date-picker
                                    v-model="execTimeRange"
                                    type="datetimerange"
                                    range-separator="至"
                                    start-placeholder="开始日期"
                                    end-placeholder="结束日期"
                                    format="YYYY-MM-DD HH:mm:ss"
                                    value-format="YYYY-MM-DD HH:mm:ss"
                                    style="width: 280px;"
                                />
                            </el-form-item>
                        </template>
                    </lySearchBar>
                </el-card>
                <el-card class="lytable" shadow="hover">
                    <ly-table v-bind="tableBindProps" ref="tableref" @selection-change="selectionChange">
                        <template v-slot:topbar>
                            <el-button type="danger" plain icon="delete" :disabled="selection.length==0" title="批量删除" @click="batch_del">删除已选</el-button>
                            <el-button type="danger" plain icon="delete-filled" title="清空日志" @click="clear_logs">清空所有</el-button>
                        </template>
                        <template #status="scope">
                            <el-tag v-if="scope.row.status === '0'" type="success">成功</el-tag>
                            <el-tag v-else type="danger">失败</el-tag>
                        </template>
                        <template #duration="scope">
                            <span>{{ scope.row.duration }} ms</span>
                        </template>
                        <el-table-column label="操作" :fixed="crudOptions.rowHandle.fixed" :width="crudOptions.rowHandle.width">
                            <template #header>
                                <div style="display: flex;justify-content: space-between;align-items: center;">
                                    <div>操作</div>
                                    <div @click="setFull">
                                        <el-tooltip content="全屏" placement="bottom">
                                            <el-icon style="cursor: pointer;"><full-screen /></el-icon>
                                        </el-tooltip>
                                    </div>
                                </div>
                            </template>
                            <template #default="scope">
                                <span class="table-operate-btn" @click="viewDetail(scope.row)">详情</span>
                                <span class="table-operate-btn delete" @click="handleDelete(scope.row)">删除</span>
                            </template>
                        </el-table-column>
                    </ly-table>
                </el-card>
            </div>
        </div>
        <logDetailModal ref="logDetailModalRef" v-if="isDetailDialogVisible" @closed="isDetailDialogVisible = false"></logDetailModal>
    </div>
</template>

<script setup name="jobLog">
    import { ref, onMounted, onUnmounted, nextTick, computed, watch } from 'vue'
    import { ElMessage, ElMessageBox } from 'element-plus'
    import { FullScreen } from '@element-plus/icons-vue'
    import logDetailModal from "../components/LogDetailModal.vue"
    import lySearchBar from '@/components/lySearchBar.vue'
    import Api from '@/api/api.js'
    import { useUserState } from '@/store/userState' 
    import { useRoute } from 'vue-router'

    const route = useRoute()
    const userState = useUserState()

    // 检查权限的方法
    const hasBtnPermission = (btnCode) => {
        return userState.hasButtonPermission(route.name, btnCode)
    }

    // 状态管理
    const isFull = ref(false)
    const isDetailDialogVisible = ref(false)
    const formInline = ref({})
    const tableSelect = ref(null)
    const tableref = ref(null)
    const logDetailModalRef = ref(null)
    let selection = ref([])
    const execTimeRange = ref([]) // 执行时间范围

    // 监听时间范围变化，同步到查询参数
    watch(execTimeRange, (newVal) => {
        if (newVal && newVal.length === 2) {
            formInline.value.begin_at = newVal[0]
            formInline.value.end_at = newVal[1]
        } else {
            delete formInline.value.begin_at
            delete formInline.value.end_at
        }
    })

    // 表格配置
    const crudOptions = ref({
        //搜索栏目配置
        searchBar:{
            showSearchBar:true,//显示搜索栏目
        },
        //分页
        pagination:{
            hidePagination:false,
        },
        //crud按钮配置
        rowHandle:{
            width: 150,//操作列宽度,0表示不显示表格操作列
            fixed:"right",//固定操作列在右侧
        },
        //table属性
        table: {
            tableName: "jobLogTable",
            pageSize: 10,
            rowKey: 'id',
            lazy: false,
            border: true,
            showSelectable: true,//表格显示复选项框
            hideImport: true,
            hideExport: true,
        },
        //table列字段
        columns: [
            {
                label: "任务名称",
                prop: "job_name",
                minWidth: "150"
            },
            {
                label: "任务组名",
                prop: "job_group",
                minWidth: "120"
            },
            {
                label: "调用目标",
                prop: "invoke_target",
                minWidth: "200"
            },
            {
                label: "执行状态",
                prop: "status",
                width: "100"
            },
            {
                label: "创建时间",
                prop: "create_time",
                minWidth: "180"
            },
            {
                label: "日志信息",
                prop: "job_message",
                minWidth: "200"
            }
        ]
    })

    function selectionChange(selections){
        selection.value = selections;
    }

    let tableBindProps = computed(() => (
        {
            ...crudOptions.value.table,
            ...crudOptions.value.pagination,
            apiObj: Api.jobLogList,
            params: formInline.value,
            column: crudOptions.value.columns,
        }
    ))

    // 方法
    const setFull = () => {
        isFull.value = !isFull.value
        window.dispatchEvent(new Event('resize'))
    }

    function batch_del(){
        ElMessageBox.confirm(`确定删除选中的 ${selection.value.length} 项吗？`, '提示', {
            confirmButtonText: "确定",
            cancelButtonText: "取消",
            type: 'warning'
        }).then(() => {
            let ids = selection.value.map(item => item.id);
            Api.jobLogDelete({id:ids}).then(res=>{
                if(res.code == 2000) {
                    search()
                    ElMessage.success("操作成功")
                } else {
                    ElMessage.warning(res.msg)
                }
            })
        }).catch(() => {

        })
    }

    function clear_logs() {
        ElMessageBox.confirm('此操作将永久清空所有执行日志，是否继续？', '提示', {
            confirmButtonText: "确定",
            cancelButtonText: "取消",
            type: 'warning'
        }).then(() => {
            Api.jobLogClear().then(res => {
                if(res.code == 2000) {
                    search()
                    ElMessage.success("清空成功")
                } else {
                    ElMessage.warning(res.msg)
                }
            })
        }).catch(() => {

        })
    }

    const handleDelete = (row) => {
        ElMessageBox.confirm('您确定要删除这条执行日志吗？', "警告", {
            closeOnClickModal: false,
            type: "warning",
        }).then(() => {
            Api.jobLogDelete({ id: row.id }).then(res => {
                if (res.code == 2000) {
                    ElMessage.success(res.msg)
                    search()
                } else {
                    ElMessage.warning(res.msg)
                }
            })
        }).catch(() => {})
    }

    const viewDetail = (row) => {
        isDetailDialogVisible.value = true
        nextTick(() => {
            logDetailModalRef.value.handleOpen(row)
        })
    }

    const handleReset = () => {
        formInline.value = {}
        execTimeRange.value = []
        search()
    }

    const search = () => {
        tableref.value.reload(formInline.value)
    }

    const getData = () => {
        tableref.value.getData()
    }

    onMounted(() => {
    })

    onUnmounted(() => {
    })
</script>

<style lang="scss" scoped>
.lycontainer {
    display: flex;
    flex-direction: column;
    height: 100%;
    width: 100%;
}

.lycontainer-wrapper {
    display: flex;
    flex: 1;
    overflow-x: hidden;
    flex-direction: row;
    gap: 10px;
}

.lycontainer-right {
    flex: 1;
    display: flex;
    flex-direction: column;
    overflow: auto;
}

.tableSelect {
    margin-bottom: 10px;
}

.table-operate-btn {
    color: var(--el-color-primary);
    cursor: pointer;
    margin-right: 10px;
}

.table-operate-btn.delete {
    color: var(--el-color-danger);
}

/* 移动端适配 */
@media screen and (max-width: 768px) {
    .lycontainer-right {
        padding: 0;
    }
    
    .el-form-item {
        margin-bottom: 10px;
    }
}
</style>
