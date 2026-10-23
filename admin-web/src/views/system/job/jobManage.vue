<template>
  <div :class="{'ly-is-full': isFull}" class="lycontainer">
    <div class="lycontainer-wrapper">
      <div class="lycontainer-right">
        <!-- 搜索栏 -->
        <el-card
            v-if="crudOptions.searchBar.showSearchBar"
            class="tableSelect"
            shadow="hover"
        >
          <ly-search-bar
              :model="formInline"
              @search="handleSearch"
              @reset="handleReset"
          >
            <template #default>
              <el-form-item label="任务名称">
                <el-input
                    v-model.trim="formInline.job_name"
                    maxlength="60"
                    clearable
                    style="width:150px"
                    placeholder="任务名称"
                />
              </el-form-item>
              <el-form-item label="任务组名">
                <el-select
                    v-model="formInline.job_group"
                    placeholder="请选择"
                    clearable
                    style="width: 120px"
                >
                  <el-option label="DEFAULT" value="DEFAULT"/>
                  <el-option label="SYSTEM" value="SYSTEM"/>
                </el-select>
              </el-form-item>
              <el-form-item label="状态">
                <el-select
                    v-model="formInline.status"
                    placeholder="请选择"
                    clearable
                    style="width: 100px"
                >
                  <el-option label="正常" value="0"/>
                  <el-option label="暂停" value="1"/>
                </el-select>
              </el-form-item>
            </template>
          </ly-search-bar>
        </el-card>

        <!-- 数据表格 -->
        <el-card class="lytable" shadow="hover">
          <ly-table
              ref="tableRef"
              v-bind="tableBindProps"
              @selection-change="handleSelectionChange"
          >
            <template #topbar>
              <el-button
                  type="primary"
                  icon="plus"
                  @click="handleAdd"
              >
                新增
              </el-button>
              <el-button
                  type="danger"
                  plain
                  icon="delete"
                  :disabled="!hasSelectedItems"
                  @click="handleBatchDelete"
              >
                批量删除
              </el-button>
            </template>

            <template #status="{ row }">
              <el-tag :type="row.status === '0' ? 'success' : 'info'">
                {{ row.status === '0' ? '正常' : '暂停' }}
              </el-tag>
            </template>

            <el-table-column
                label="操作"
                :fixed="crudOptions.rowHandle.fixed"
                :width="crudOptions.rowHandle.width"
            >
              <template #header>
                <div class="operation-header">
                  <span>操作</span>
                  <el-tooltip content="全屏" placement="bottom">
                    <el-icon @click="toggleFullScreen" class="fullscreen-icon">
                      <full-screen/>
                    </el-icon>
                  </el-tooltip>
                </div>
              </template>

              <template #default="{ row }">
                                <span
                                    class="table-operate-btn"
                                    @click="handleEdit(row)"
                                >
                                    编辑
                                </span>
                <span
                    class="table-operate-btn"
                    @click="handleExecute(row)"
                >
                                    执行
                                </span>
                <span
                    v-if="row.status === '0'"
                    class="table-operate-btn"
                    @click="handleStop(row)"
                >
                                    停止
                                </span>
                <span
                    v-else
                    class="table-operate-btn"
                    @click="handleStart(row)"
                >
                                    启动
                                </span>
                <span
                    class="table-operate-btn delete"
                    @click="handleDelete(row)"
                >
                                    删除
                                </span>
              </template>
            </el-table-column>
          </ly-table>
        </el-card>
      </div>
    </div>

    <!-- 添加或修改定时任务对话框 -->
    <teleport to="body">
      <el-dialog
        :close-on-click-modal="false"
        :close-on-press-escape="false"
        :title="dialogTitle"
        v-model="isDialogVisible"
        width="700px"
        append-to-body
        @closed="handleClosed"
      >
        <el-form
          ref="formRef"
          :model="formData"
          :rules="rules"
          label-width="120px"
        >
          <el-row>
            <el-col :span="12">
              <el-form-item label="任务名称" prop="job_name">
                <el-input v-model.trim="formData.job_name" placeholder="请输入任务名称" maxlength="100" show-word-limit />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="任务组名" prop="job_group">
                <el-select v-model="formData.job_group" placeholder="请选择任务组名" style="width: 100%">
                  <el-option label="DEFAULT" value="DEFAULT" />
                  <el-option label="SYSTEM" value="SYSTEM" />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="24">
              <el-form-item label="调用目标" prop="invoke_target">
                <el-input v-model.trim="formData.invoke_target" placeholder="请输入调用目标，格式：模块名。函数名（如：apps.tasks.my_task）" />
              </el-form-item>
            </el-col>
            <el-col :span="24">
              <el-form-item label="Cron 表达式" prop="cron_expression">
                <el-input v-model="formData.cron_expression" placeholder="请输入 Cron 表达式">
                  <template #append>
                    <el-button type="primary" @click="handleShowCron">
                      生成表达式
                    </el-button>
                  </template>
                </el-input>
              </el-form-item>
            </el-col>
            <el-col :span="24">
              <el-divider>执行策略</el-divider>
            </el-col>
            <el-col :span="24">
              <el-form-item label="执行异常策略" prop="misfire_policy">
                <el-radio-group v-model="formData.misfire_policy">
                  <el-radio value="1">立即执行</el-radio>
                  <el-radio value="2">执行一次</el-radio>
                  <el-radio value="3">放弃执行</el-radio>
                </el-radio-group>
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="并发执行" prop="concurrent">
                <el-switch
                  v-model="formData.concurrent"
                  active-text="允许"
                  inactive-text="禁止"
                  :active-value="'1'"
                  :inactive-value="'0'"
                />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="状态" prop="status">
                <el-switch
                  v-model="formData.status"
                  active-text="正常"
                  inactive-text="暂停"
                  :active-value="'0'"
                  :inactive-value="'1'"
                />
              </el-form-item>
            </el-col>
          </el-row>
        </el-form>
        <template #footer>
          <el-button @click="isDialogVisible = false">取消</el-button>
          <el-button type="primary" @click="handleSubmit" :loading="submitLoading">确定</el-button>
        </template>
      </el-dialog>

      <!-- Cron 表达式生成器对话框 -->
      <el-dialog
        :close-on-click-modal="false"
        :close-on-press-escape="false"
        title="Cron 表达式生成器"
        v-model="openCron"
        width="800px"
        append-to-body
        destroy-on-close
        class="scrollbar"
      >
        <crontab
          @hide="openCron = false"
          @fill="crontabFill"
          :expression="formData.cron_expression"
        ></crontab>
      </el-dialog>
    </teleport>
  </div>
</template>

<script setup name="JobManage">
import {ref, computed, onMounted, nextTick} from 'vue'
import {ElMessage, ElMessageBox} from 'element-plus'
import {FullScreen} from '@element-plus/icons-vue'
import LySearchBar from '@/components/lySearchBar.vue'
import {createCrudConfig} from './crud.js'
import {useUserState} from '@/store/userState'
import {useRoute} from 'vue-router'
import Crontab from './components/crontab/index.vue'
import api from "./api.js"

// composables
const route = useRoute()
const userState = useUserState()

// 配置和状态
const crudOptions = ref(createCrudConfig().crudOptions)
const isFull = ref(false)
const isDialogVisible = ref(false)
const openCron = ref(false)
const formInline = ref({})
const selection = ref([])
const tableRef = ref(null)
const formRef = ref(null)
const submitLoading = ref(false)
const dialogTitle = ref('新增定时任务')

const formData = ref({
  job_name: '',
  job_group: 'DEFAULT',
  invoke_target: '',
  cron_expression: '0 0 0 * * *',
  misfire_policy: '3',
  concurrent: '0',
  status: '0'
})

const rules = {
  job_name: [
    { required: true, message: '请输入任务名称', trigger: 'blur' },
    { min: 2, max: 100, message: '长度在 2 到 100 个字符', trigger: 'blur' }
  ],
  job_group: [
    { required: true, message: '请选择任务组名', trigger: 'change' }
  ],
  invoke_target: [
    { required: true, message: '请输入调用目标', trigger: 'blur' },
    { pattern: /^[a-zA-Z_][a-zA-Z0-9_.]*$/, message: '格式错误，应为：模块名。函数名', trigger: 'blur' }
  ],
  cron_expression: [
    { required: true, message: '请输入 Cron 表达式', trigger: 'blur' }
  ],
  misfire_policy: [
    { required: true, message: '请选择执行异常策略', trigger: 'change' }
  ]
}

let currentRow = null

// 计算属性
const tableBindProps = computed(() => ({
  ...crudOptions.value.table,
  ...crudOptions.value.pagination,
  apiObj: crudOptions.value.request.list,
  params: formInline.value,
  column: crudOptions.value.columns,
}))

const hasSelectedItems = computed(() => selection.value.length > 0)
const hasBtnPermission = (btnCode) => userState.hasButtonPermission(route.name, btnCode)

// 数据操作方法
const refreshTableData = () => {
  tableRef.value?.getData()
}

const resetSelection = () => {
  selection.value = []
}

const clearSearchForm = () => {
  formInline.value = {}
}

// UI 操作方法
const toggleFullScreen = () => {
  isFull.value = !isFull.value
  window.dispatchEvent(new Event('resize'))
}

const handleSearch = () => {
  tableRef.value?.reload(formInline.value)
}

const handleReset = () => {
  clearSearchForm()
  handleSearch()
}

const handleSelectionChange = (selectedItems) => {
  selection.value = selectedItems
}

// CRUD 操作方法
const handleAdd = () => {
  dialogTitle.value = '新增定时任务'
  currentRow = null
  resetForm()
  isDialogVisible.value = true
}

const handleEdit = (row) => {
  dialogTitle.value = '编辑定时任务'
  currentRow = row
  formData.value = {
    id: row.id,
    job_name: row.job_name,
    job_group: row.job_group || 'DEFAULT',
    invoke_target: row.invoke_target,
    cron_expression: row.cron_expression,
    misfire_policy: String(row.misfire_policy || '3'),
    concurrent: String(row.concurrent || '0'),
    status: String(row.status ?? '0')
  }
  isDialogVisible.value = true
}

const handleDelete = (row) => {
  ElMessageBox.confirm(
      `确定要删除任务 "${row.job_name}" 吗？`,
      '删除确认',
      {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning'
      }
  ).then(async () => {
    try {
      const res = await crudOptions.value.request.del({id: row.id})
      if (res.code === 2000) {
        ElMessage.success(res.msg || '删除成功')
        refreshTableData()
      } else {
        ElMessage.warning(res.msg)
      }
    } catch (error) {
      ElMessage.error('删除失败')
    }
  }).catch(() => {
    // 用户取消操作
  })
}

const handleBatchDelete = () => {
  const count = selection.value.length
  ElMessageBox.confirm(
      `确定删除选中的 ${count} 项吗？`,
      '批量删除确认',
      {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning'
      }
  ).then(async () => {
    try {
      const ids = selection.value.map(item => item.id)
      const res = await crudOptions.value.request.del({id: ids})
      if (res.code === 2000) {
        ElMessage.success('批量删除成功')
        resetSelection()
        refreshTableData()
      } else {
        ElMessage.warning(res.msg)
      }
    } catch (error) {
      ElMessage.error('批量删除失败')
    }
  }).catch(() => {
    // 用户取消操作
  })
}

const handleExecute = (row) => {
  ElMessageBox.confirm(
      `确定要立即执行任务 "${row.job_name}" 吗？`,
      '执行确认',
      {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning'
      }
  ).then(async () => {
    try {
      const res = await crudOptions.value.request.run({id: row.id})
      if (res.code === 2000) {
        ElMessage.success('执行成功')
      } else {
        ElMessage.warning(res.msg)
      }
    } catch (error) {
      ElMessage.error('执行失败')
    }
  }).catch(() => {
    // 用户取消操作
  })
}

const handleStart = (row) => {
  ElMessageBox.confirm(
      `确定要启动任务 "${row.job_name}" 吗？`,
      '启动确认',
      {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning'
      }
  ).then(async () => {
    try {
      const res = await crudOptions.value.request.start({id: row.id})
      if (res.code === 2000) {
        ElMessage.success('启动成功')
        refreshTableData()
      } else {
        ElMessage.warning(res.msg)
      }
    } catch (error) {
      ElMessage.error('启动失败')
    }
  }).catch(() => {
    // 用户取消操作
  })
}

const handleStop = (row) => {
  ElMessageBox.confirm(
      `确定要停止任务 "${row.job_name}" 吗？`,
      '停止确认',
      {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning'
      }
  ).then(async () => {
    try {
      const res = await crudOptions.value.request.stop({id: row.id})
      if (res.code === 2000) {
        ElMessage.success('停止成功')
        refreshTableData()
      } else {
        ElMessage.warning(res.msg)
      }
    } catch (error) {
      ElMessage.error('停止失败')
    }
  }).catch(() => {
    // 用户取消操作
  })
}

// 表单方法
const resetForm = () => {
  formData.value = {
    job_name: '',
    job_group: 'DEFAULT',
    invoke_target: '',
    cron_expression: '0 0 0 * * *',
    misfire_policy: '3',
    concurrent: '0',
    status: '0'
  }
  if (formRef.value) {
    formRef.value.clearValidate()
  }
}

// 显示 cron 生成器
const handleShowCron = () => {
  openCron.value = true
}

// cron 表达式填充
const crontabFill = (value) => {
  formData.value.cron_expression = value
  openCron.value = false
}

const handleSubmit = async () => {
  if (!formRef.value) return

  await formRef.value.validate(async (valid) => {
    if (!valid) return

    submitLoading.value = true
    try {
      const data = { ...formData.value }

      if (currentRow && currentRow.id) {
        data.id = currentRow.id
        const res = await api.edit(data)
        if (res.code === 2000) {
          ElMessage.success('编辑成功')
          refreshTableData()
          isDialogVisible.value = false
        } else {
          ElMessage.error(res.msg || '编辑失败')
        }
      } else {
        const res = await api.add(data)
        if (res.code === 2000) {
          ElMessage.success('新增成功')
          refreshTableData()
          isDialogVisible.value = false
        } else {
          ElMessage.error(res.msg || '新增失败')
        }
      }
    } catch (error) {
      ElMessage.error('操作失败')
      console.error(error)
    } finally {
      submitLoading.value = false
    }
  })
}

const handleClosed = () => {
  resetForm()
}

// 生命周期
onMounted(() => {
  nextTick(() => {
    refreshTableData()
  })
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

.operation-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.fullscreen-icon {
  cursor: pointer;
  color: var(--el-text-color-regular);

  &:hover {
    color: var(--el-color-primary);
  }
}

.table-operate-btn {
  color: var(--el-color-primary);
  cursor: pointer;
  margin-right: 10px;
  user-select: none;

  &:hover {
    opacity: 0.8;
  }

  &.delete {
    color: var(--el-color-danger);
  }
}

/* 响应式设计 */
@media screen and (max-width: 768px) {
  .lycontainer-right {
    padding: 0;
  }

  .el-form-item {
    margin-bottom: 10px;
  }

  .operation-header {
    font-size: 14px;
  }
}
</style>
