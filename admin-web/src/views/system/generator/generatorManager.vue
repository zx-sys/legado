<template>
  <div class="gen-table-container">
    <!-- 左右 1:6 布局 -->
    <el-container class="main-container">
      <!-- 左侧：表管理面板 (14.28%) -->
      <el-aside width="220px" class="table-panel">
        <div class="panel-header">
          <el-input
            v-model="filterText"
            placeholder="输入关键字过滤..."
            clearable
            :prefix-icon="Search"
            @input="handleFilterInput"
          />
          <el-button
            type="primary"
            :icon="Plus"
            size="small"
            @click="addTable"
            style="margin-top: 10px; width: 100%"
            v-auth="'Create'"
          >
            新增表
          </el-button>
          <el-checkbox v-model="batchMode" style="margin-top: 8px; font-size: 12px" @change="handleBatchModeChange">
            批量模式
          </el-checkbox>
        </div>

        <el-scrollbar class="table-list">
          <div v-if="batchMode" style="padding: 8px">
            <div style="font-size: 12px; color: #909399; margin-bottom: 8px">
              已选择 {{ selectedTablesForBatch.length }} 个表
            </div>
            <el-button
              type="success"
              :icon="Download"
              size="small"
              @click="batchGenerateCode"
              :disabled="selectedTablesForBatch.length === 0"
              style="margin-bottom: 10px; width: 100%"
            >
              批量生成代码
            </el-button>
          </div>
          <div
            v-for="table in filteredTables"
            :key="table.id"
            :class="['table-item', { active: selectedTable?.id === table.id, selected: selectedTablesForBatch.includes(table.id) }]"
            @click="handleTableClick(table)"
          >
            <div class="table-info">
              <div class="table-name">{{ table.model_comment || table.model_name }}</div>
              <div class="table-db-name">{{ table.table_name }}</div>
            </div>
            <div class="table-actions">
              <el-checkbox v-if="batchMode" :model-value="selectedTablesForBatch.includes(table.id)" @change="(v) => toggleTableSelection(table.id)" size="small" style="margin-right: 4px" />
              <el-button
                :icon="Download"
                size="small"
                text
                @click.stop="generateCodeForTable(table)"
                type="success"
                title="生成代码"
              />
              <el-button
                :icon="View"
                size="small"
                text
                @click.stop="previewCodeForTable(table)"
                type="primary"
                title="预览代码"
              />
              <el-button
                :icon="Edit"
                size="small"
                text
                @click.stop="editTable(table)"
                v-auth="'Update'"
                type="primary"
              />
              <el-button
                :icon="Delete"
                size="small"
                text
                @click.stop="deleteTable(table)"
                v-auth="'Delete'"
                type="danger"
              />
            </div>
          </div>
        </el-scrollbar>
      </el-aside>

      <!-- 右侧：列信息编辑器 (85.72%) -->
      <el-main class="column-panel">
        <div class="panel-header">
          <div class="header-title">
            <span>{{ selectedTable ? `${selectedTable.model_comment} (${selectedTable.table_name})` : '请选择一个表' }}</span>
          </div>
          <div class="header-actions">
            <template v-if="!previewMode">
              <el-button
                type="primary"
                :icon="Plus"
                @click="addColumn"
                :disabled="!selectedTable"
                v-auth="'Create'"
              >
                新增列
              </el-button>
              <el-button
                type="danger"
                :icon="Delete"
                @click="batchDeleteColumns"
                :disabled="!selectedTable || selectedColumns.length === 0"
                v-auth="'Delete'"
              >
                删除列
              </el-button>
              <el-button
                type="success"
                :icon="DocumentChecked"
                @click="batchSaveColumns"
                :disabled="!selectedTable || columnData.length === 0"
                v-auth="'Update'"
              >
                保存全部
              </el-button>
              <el-button
                type="primary"
                :icon="Download"
                @click="generateCode"
                :disabled="!selectedTable"
              >
                生成代码
              </el-button>
              <el-button
                type="info"
                :icon="View"
                @click="previewCode"
                :disabled="!selectedTable"
              >
                预览代码
              </el-button>
            </template>
            <template v-else>
              <el-button
                type="default"
                :icon="Edit"
                @click="exitPreviewMode"
              >
                返回编辑
              </el-button>
              <el-button
                type="primary"
                :icon="DocumentCopy"
                @click="copyCode(activePreviewFile)"
              >
                复制代码
              </el-button>
              <el-button
                type="success"
                :icon="Download"
                @click="generateCode"
              >
                生成代码
              </el-button>
            </template>
          </div>
        </div>

        <!-- 列编辑模式 -->
        <el-scrollbar v-if="!previewMode" class="column-list">
          <el-table
            ref="columnTableRef"
            :data="columnData"
            border
            stripe
            row-key="temp_id"
            v-loading="tableLoading"
            @selection-change="handleSelectionChange"
          >
            <el-table-column type="selection" width="30" align="center" />

            <!-- 拖拽排序手柄 -->
            <el-table-column width="30" align="center">
              <template #default>
                <el-icon class="drag-handle">
                  <Expand />
                </el-icon>
              </template>
            </el-table-column>

            <!-- 可编辑的字段 -->
            <el-table-column prop="column_name" label="列名" min-width="70">
              <template #default="{ row }">
                <el-input v-model="row.column_name" size="small" placeholder="列名" />
              </template>
            </el-table-column>

            <el-table-column prop="column_comment" label="描述" min-width="100">
              <template #default="{ row }">
                <el-input v-model="row.column_comment" size="small" placeholder="描述" />
              </template>
            </el-table-column>

            <el-table-column prop="column_type" label="DB类型" width="100">
              <template #default="{ row }">
                <el-select v-model="row.column_type" size="small" placeholder="DB类型">
                  <el-option
                    v-for="item in dbColumnTypeOptions"
                    :key="item.value"
                    :label="item.label"
                    :value="item.value"
                  />
                </el-select>
              </template>
            </el-table-column>

            <el-table-column prop="django_field_type" label="Django字段" width="130">
              <template #default="{ row }">
                <el-select v-model="row.django_field_type" size="small" placeholder="Django字段">
                  <el-option
                    v-for="item in djangoFieldTypeOptions"
                    :key="item.value"
                    :label="item.label"
                    :value="item.value"
                  />
                </el-select>
              </template>
            </el-table-column>

            <!-- 布尔选项，使用绿色标签 -->
            <el-table-column prop="is_pk" label="主键" width="40" align="center">
              <template #default="{ row }">
                <el-check-tag
                  :checked="row.is_pk"
                  @change="row.is_pk = !row.is_pk"
                  type="success"
                >
                  是
                </el-check-tag>
              </template>
            </el-table-column>

            <el-table-column prop="is_increment" label="自增" width="40" align="center">
              <template #default="{ row }">
                <el-check-tag
                  :checked="row.is_increment"
                  @change="row.is_increment = !row.is_increment"
                  type="success"
                >
                  是
                </el-check-tag>
              </template>
            </el-table-column>

            <el-table-column prop="is_required" label="必填" width="40" align="center">
              <template #default="{ row }">
                <el-check-tag
                  :checked="row.is_required"
                  @change="row.is_required = !row.is_required"
                  type="success"
                >
                  是
                </el-check-tag>
              </template>
            </el-table-column>

            <el-table-column prop="is_insert" label="插入" width="40" align="center">
              <template #default="{ row }">
                <el-check-tag
                  :checked="row.is_insert"
                  @change="row.is_insert = !row.is_insert"
                  type="success"
                >
                  是
                </el-check-tag>
              </template>
            </el-table-column>

            <el-table-column prop="is_edit" label="编辑" width="40" align="center">
              <template #default="{ row }">
                <el-check-tag
                  :checked="row.is_edit"
                  @change="row.is_edit = !row.is_edit"
                  type="success"
                >
                  是
                </el-check-tag>
              </template>
            </el-table-column>

            <el-table-column prop="is_list" label="列表" width="40" align="center">
              <template #default="{ row }">
                <el-check-tag
                  :checked="row.is_list"
                  @change="row.is_list = !row.is_list"
                  type="success"
                >
                  是
                </el-check-tag>
              </template>
            </el-table-column>

            <el-table-column prop="is_query" label="查询" width="40" align="center">
              <template #default="{ row }">
                <el-check-tag
                  :checked="row.is_query"
                  @change="row.is_query = !row.is_query"
                  type="success"
                >
                  是
                </el-check-tag>
              </template>
            </el-table-column>

            <el-table-column prop="query_type" label="查询方式" width="90">
              <template #default="{ row }">
                <el-select v-model="row.query_type" size="small" placeholder="查询方式">
                  <el-option
                    v-for="item in queryTypeOptions"
                    :key="item.value"
                    :label="item.label"
                    :value="item.value"
                  />
                </el-select>
              </template>
            </el-table-column>

            <el-table-column prop="show_type" label="显示类型" width="120">
              <template #default="{ row }">
                <el-select v-model="row.show_type" size="small" placeholder="显示类型">
                  <el-option
                    v-for="item in showTypeOptions"
                    :key="item.value"
                    :label="item.label"
                    :value="item.value"
                  />
                </el-select>
              </template>
            </el-table-column>

            <el-table-column prop="dict_json" label="字典项" min-width="100">
              <template #default="{ row }">
                <el-select
                  v-model="row.dict_json"
                  size="small"
                  placeholder="选择字典"
                  clearable
                  filterable
                  :disabled="!['select', 'radio', 'checkbox'].includes(row.show_type)"
                >
                  <el-option
                    v-for="(label, type) in dictStore.dictTypes"
                    :key="type"
                    :label="`${label} (${type})`"
                    :value="type"
                  />
                </el-select>
              </template>
            </el-table-column>
          </el-table>
        </el-scrollbar>

        <!-- 代码预览模式 -->
        <div v-else class="preview-container">
          <div class="preview-header">
            <el-tabs v-model="activePreviewFile" type="card" class="preview-tabs">
              <el-tab-pane
                v-for="(content, filename) in previewFiles"
                :key="filename"
                :label="filename"
                :name="filename"
              />
            </el-tabs>
            <el-button type="primary" :icon="DocumentCopy" @click="copyCode" size="small">
              复制
            </el-button>
          </div>
          <div class="code-editor-wrapper">
            <Codemirror
              v-model="currentCode"
              :options="cmOptions"
              :readonly="true"
              class="code-editor"
            />
          </div>
        </div>
      </el-main>
    </el-container>

    <!-- GenTable 弹窗 -->
    <GenTableModule ref="tableDialog" @refreshData="fetchTableTree" v-if="isDialogTableVisible" @closed="isDialogTableVisible = false" />
  </div>
</template>

<script setup name="genTable">
import { ref, computed, onMounted, nextTick, onUnmounted, watch } from 'vue'
import { Search, Edit, Delete, Plus, DocumentChecked, Menu, Download, View, DocumentCopy } from '@element-plus/icons-vue'
import Sortable from 'sortablejs'
import GenTableModule from './component/GenTableModule.vue'
import Api from '@/api/api'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useDictStore } from '@/store/dict.js'
import { downloadFile } from '@/api/request'
import { Codemirror } from 'vue-codemirror'
import { javascript } from '@codemirror/lang-javascript'
import { python } from '@codemirror/lang-python'
import { java } from '@codemirror/lang-java'
import { xml } from '@codemirror/lang-xml'
import { sql } from '@codemirror/lang-sql'
import { vue } from '@codemirror/lang-vue'

const dictStore = useDictStore()

// 数据库列类型选项
const dbColumnTypeOptions = computed(() => [
  { label: 'VARCHAR', value: 'VARCHAR' },
  { label: 'TEXT', value: 'TEXT' },
  { label: 'TINYINT', value: 'TINYINT' },
  { label: 'INT', value: 'INT' },
  { label: 'DOUBLE', value: 'DOUBLE' },
  { label: 'DECIMAL', value: 'DECIMAL' },
  { label: 'DATE', value: 'DATE' },
  { label: 'DATETIME', value: 'DATETIME' },
  { label: 'TIMESTAMP', value: 'TIMESTAMP' },
  { label: 'BOOLEAN', value: 'BOOLEAN' },
  { label: 'JSON', value: 'JSON' }
])

// Django字段类型选项
const djangoFieldTypeOptions = computed(() => [
  { label: 'CharField (字符串)', value: 'CharField' },
  { label: 'IntegerField (整数)', value: 'IntegerField' },
  { label: 'BigIntegerField (大整数)', value: 'BigIntegerField' },
  { label: 'FloatField (浮点数)', value: 'FloatField' },
  { label: 'DecimalField (十进制)', value: 'DecimalField' },
  { label: 'DateTimeField (日期时间)', value: 'DateTimeField' },
  { label: 'BooleanField (布尔值)', value: 'BooleanField' }
])

// 查询方式选项
const queryTypeOptions = computed(() => [
  { label: '等于 (=)', value: 'exact' },
  { label: '不等于 (!=)', value: 'not_exact' },
  { label: '大于 (>)', value: 'gt' },
  { label: '大于等于 (>=)', value: 'gte' },
  { label: '小于 (<)', value: 'lt' },
  { label: '小于等于 (<=)', value: 'lte' },
  { label: '包含 (LIKE)', value: 'contains' },
  { label: '以...开头 (LIKE)', value: 'startswith' },
  { label: '以...结尾 (LIKE)', value: 'endswith' },
  { label: 'IN 查询', value: 'in' },
  { label: '是否为空 (IS NULL)', value: 'is_null' }
])

// 显示类型选项
const showTypeOptions = computed(() => [
  { label: '文本框 (Input)', value: 'input' },
  { label: '文本域 (Textarea)', value: 'textarea' },
  { label: '下拉框 (Select)', value: 'select' },
  { label: '单选框 (Radio)', value: 'radio' },
  { label: '复选框 (Checkbox)', value: 'checkbox' },
  { label: '日期时间 (DateTime)', value: 'datetime' },
  { label: '上传图片 (UploadImg)', value: 'uploadImg' },
  { label: '上传文件 (UploadFile)', value: 'uploadFile' },
  { label: '富文本 (Editor)', value: 'editor' }
])

// 响应式变量
const filterText = ref('')
const tableTree = ref([])
const columnData = ref([])
const selectedTable = ref(null)
const selectedColumns = ref([])
const treeLoading = ref(false)
const tableLoading = ref(false)
const tableDialog = ref(null)
const isDialogTableVisible = ref(false)
const columnTableRef = ref(null)
const batchMode = ref(false)
const selectedTablesForBatch = ref([])
const previewFiles = ref({})
const activePreviewFile = ref('')
const previewMode = ref(false) // 代码预览模式
const currentCode = ref('') // 当前显示的代码

let sortableInstance = null

// 文件扩展名到 CodeMirror 语言扩展的映射
const getLanguageExtension = (filename) => {
  const ext = filename.split('.').pop()?.toLowerCase()
  const langMap = {
    'js': javascript(),
    'vue': vue(),
    'py': python(),
    'java': java(),
    'xml': xml(),
    'html': xml(),
    'sql': sql(),
    'json': javascript(),
    'ts': javascript(),
    'tsx': javascript(),
    'jsx': javascript(),
  }
  return langMap[ext] || javascript()
}

// CodeMirror 配置（浅色主题）
const cmOptions = computed(() => ({
  tabSize: 2,
  lineWrapping: true,
  lineNumbers: true,
  extensions: [
    getLanguageExtension(activePreviewFile.value)
  ]
}))

// 过滤后的表列表
const filteredTables = computed(() => {
  if (!filterText.value) return tableTree.value
  const searchText = filterText.value.toLowerCase()
  return tableTree.value.filter(table => {
    return (
      (table.model_comment && table.model_comment.toLowerCase().includes(searchText)) ||
      (table.model_name && table.model_name.toLowerCase().includes(searchText)) ||
      (table.table_name && table.table_name.toLowerCase().includes(searchText))
    )
  })
})

// 搜索过滤
const handleFilterInput = () => {
  // 实时过滤，使用 computed 自动处理
}

// 批量模式切换
const handleBatchModeChange = () => {
  selectedTablesForBatch.value = []
}

// 切换表选择
const toggleTableSelection = (tableId) => {
  const index = selectedTablesForBatch.value.indexOf(tableId)
  if (index === -1) {
    selectedTablesForBatch.value.push(tableId)
  } else {
    selectedTablesForBatch.value.splice(index, 1)
  }
}

// 获取表列表
const fetchTableTree = async () => {
  try {
    treeLoading.value = true
    const res = await Api.getGenTableList({ page: 1, limit: 999 })

    if (res.code === 2000) {
      tableTree.value = res.data.data
      // 默认选中第一个
      if (tableTree.value.length > 0 && !selectedTable.value && !batchMode.value) {
        handleTableClick(tableTree.value[0])
      }
    }
  } catch (error) {
    console.error('获取表列表失败:', error)
    ElMessage.error('获取表列表失败')
  } finally {
    treeLoading.value = false
  }
}

// 获取列列表
const fetchTableColumns = async (tableId) => {
  if (!tableId) return

  try {
    tableLoading.value = true
    const res = await Api.genTableColumnList({
      model: tableId,
      page: 1,
      limit: 999
    })

    if (res.code === 2000) {
      // 为每列添加临时 ID 用于拖拽
      columnData.value = res.data.data.map((item, index) => ({
        ...item,
        temp_id: item.id || `temp_${Date.now()}_${index}`,
        sort: item.sort || index
      }))

      // 初始化拖拽排序
      await nextTick()
      destroySortable()
      initSortable()
    }
  } catch (error) {
    console.error('获取列列表失败:', error)
    ElMessage.error('获取列列表失败')
  } finally {
    tableLoading.value = false
  }
}

// 表点击事件
const handleTableClick = (table) => {
  if (batchMode.value) {
    toggleTableSelection(table.id)
    return
  }
  selectedTable.value = table
  fetchTableColumns(table.id)
}

// 新增表
const addTable = () => {
  isDialogTableVisible.value = true
  nextTick(() => {
    tableDialog.value.addModuleFn(null, '新增')
  })
}

// 编辑表
const editTable = (table) => {
  isDialogTableVisible.value = true
  nextTick(() => {
    tableDialog.value.addModuleFn(table, '编辑')
  })
}

// 删除表
const deleteTable = async (table) => {
  try {
    await ElMessageBox.confirm(
      `确认删除表"${table.model_comment || table.model_name}"及其所有字段吗？`,
      '提示',
      {
        confirmButtonText: '确认',
        cancelButtonText: '取消',
        type: 'warning'
      }
    )

    const res = await Api.deleteGenTable({ tableIds: table.id })
    if (res.code === 2000) {
      ElMessage.success(res.msg)
      // 从列表中移除
      const index = tableTree.value.findIndex(t => t.id === table.id)
      if (index !== -1) {
        tableTree.value.splice(index, 1)
      }
      // 如果删除的是当前选中的表
      if (selectedTable.value?.id === table.id) {
        selectedTable.value = null
        columnData.value = []
        // 选中剩余的第一个表
        if (tableTree.value.length > 0 && !batchMode.value) {
          handleTableClick(tableTree.value[0])
        }
      }
    } else {
      ElMessage.error(res.msg || '删除失败')
    }
  } catch (error) {
    if (error !== 'cancel') {
      ElMessage.error('删除失败')
    }
  }
}

// 新增列
const addColumn = () => {
  if (!selectedTable.value) {
    ElMessage.info('请先选择一个表')
    return
  }

  const newColumn = {
    id: null, // 新增列没有 ID
    temp_id: `temp_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`,
    column_name: '',
    column_comment: '',
    column_type: 'VARCHAR',
    django_field_type: 'CharField',
    is_pk: false,
    is_increment: false,
    is_required: false,
    is_insert: true,
    is_edit: true,
    is_list: false,
    is_query: false,
    query_type: 'exact',
    show_type: 'input',
    dict_json: '',
    sort: columnData.value.length > 0 ? Math.max(...columnData.value.map(c => c.sort || 0)) + 1 : 0,
    remark: ''
  }

  columnData.value.push(newColumn)
}

// 批量删除列
const batchDeleteColumns = () => {
  if (selectedColumns.value.length === 0) {
    ElMessage.info('请选择要删除的列')
    return
  }

  const ids = selectedColumns.value.map(col => col.temp_id)
  columnData.value = columnData.value.filter(col => !ids.includes(col.temp_id))
  selectedColumns.value = []
  ElMessage.success(`已选择删除，点击"保存全部"后生效`)
}

// 批量保存列
const batchSaveColumns = async () => {
  if (!selectedTable.value) {
    ElMessage.info('请先选择一个表')
    return
  }

  if (columnData.value.length === 0) {
    ElMessage.info('没有需要保存的数据')
    return
  }

  try {
    await ElMessageBox.confirm(
      '确认保存当前所有列配置吗？这将先删除原表所有列，再批量插入新配置。',
      '提示',
      {
        confirmButtonText: '确认',
        cancelButtonText: '取消',
        type: 'warning'
      }
    )

    tableLoading.value = true

    // 过滤掉无效的列数据（至少要有列名）
    const validColumns = columnData.value.filter(col => col.column_name)

    if (validColumns.length === 0) {
      ElMessage.warning('请至少填写列名')
      return
    }

    // 构造后端接口所需的数据格式
    const columnsPayload = validColumns.map((col, index) => ({
      column_name: col.column_name,
      column_comment: col.column_comment || '',
      column_type: col.column_type || 'VARCHAR',
      django_field_type: col.django_field_type || 'CharField',
      is_pk: col.is_pk || false,
      is_increment: col.is_increment || false,
      is_required: col.is_required || false,
      is_insert: col.is_insert !== false,
      is_edit: col.is_edit !== false,
      is_list: col.is_list || false,
      is_query: col.is_query || false,
      query_type: col.query_type || 'exact',
      show_type: col.show_type || 'input',
      dict_json: col.dict_json || '',
      sort: col.sort !== undefined ? col.sort : index
    }))
    console.log('columnsPayload:', {
      table_id: selectedTable.value.id,
      columns: columnsPayload
    })

    const res = await Api.batchSaveGenTableColumns({
      table_id: selectedTable.value.id,
      columns: columnsPayload
    })

    if (res.code === 2000 || res.status === 'success') {
      ElMessage.success('保存成功')
      // 刷新列列表
      fetchTableColumns(selectedTable.value.id)
    } else {
      ElMessage.error(res.msg || '保存失败')
    }
  } catch (error) {
    if (error !== 'cancel') {
      console.error('保存失败:', error)
      ElMessage.error('保存失败')
    }
  } finally {
    tableLoading.value = false
  }
}

// 表格选择变化
const handleSelectionChange = (selection) => {
  selectedColumns.value = selection
}

// 初始化拖拽排序
const initSortable = () => {
  const table = columnTableRef.value?.$el.querySelector('.el-table__body-wrapper tbody')
  if (!table) return

  sortableInstance = Sortable.create(table, {
    handle: '.drag-handle',
    animation: 150,
    ghostClass: 'sortable-ghost',
    onEnd: (evt) => {
      const { oldIndex, newIndex } = evt
      if (oldIndex !== newIndex) {
        const movedItem = columnData.value.splice(oldIndex, 1)[0]
        columnData.value.splice(newIndex, 0, movedItem)

        // 更新排序值
        columnData.value.forEach((col, index) => {
          col.sort = index
        })
      }
    }
  })
}

// 销毁拖拽
const destroySortable = () => {
  if (sortableInstance) {
    sortableInstance.destroy()
    sortableInstance = null
  }
}

// 生成代码按钮点击
const generateCode = async () => {
  if (!selectedTable.value) {
    ElMessage.warning('请先选择一个表')
    return
  }
  await downloadSingleTableCode(selectedTable.value.id, selectedTable.value.table_name || selectedTable.value.model_name)
}

// 预览代码按钮点击
const previewCode = async () => {
  if (!selectedTable.value) {
    ElMessage.warning('请先选择一个表')
    return
  }
  await fetchPreviewCode()
}

// 单表生成代码（左侧列表）
const generateCodeForTable = async (table) => {
  await downloadSingleTableCode(table.id, table.table_name || table.model_name)
}

// 单表预览代码（左侧列表）
const previewCodeForTable = async (table) => {
  selectedTable.value = table
  await fetchPreviewCode()
}

// 批量生成代码
const batchGenerateCode = async () => {
  if (selectedTablesForBatch.value.length === 0) {
    ElMessage.warning('请至少选择一个表')
    return
  }

  try {
    await ElMessageBox.confirm(
      `确认批量生成 ${selectedTablesForBatch.value.length} 个表的代码吗？`,
      '批量生成',
      {
        confirmButtonText: '确认',
        cancelButtonText: '取消',
        type: 'warning'
      }
    )

    // 逐个下载（前端限制，无法一次下载多个文件）
    for (const tableId of selectedTablesForBatch.value) {
      const table = tableTree.value.find(t => t.id === tableId)
      if (table) {
        await downloadSingleTableCode(tableId, table.table_name || table.model_name)
      }
    }

    ElMessage.success('批量生成完成')
  } catch (error) {
    if (error !== 'cancel') {
      ElMessage.error('批量生成失败')
    }
  }
}

// 下载单个表的代码
const downloadSingleTableCode = async (tableId, tableName) => {
  try {
    const res = await downloadFile({
      url: `/api/generator/tables/${tableId}/generate_code/`,
      params: {}
    })

    // 从响应头获取文件名
    const contentDisposition = res.headers['content-disposition']
    let fileName = `${tableName}_generated_code.zip`
    if (contentDisposition) {
      const matches = /filename[^;=\n]*=((['"]).*?\2|[^;\n]*)/.exec(contentDisposition)
      if (matches && matches[1]) {
        fileName = matches[1].replace(/['"]/g, '')
      }
    }

    // 创建下载链接
    const blob = new Blob([res.data], { type: 'application/zip' })
    const url = window.URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = fileName
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    window.URL.revokeObjectURL(url)

    ElMessage.success(`${tableName} 代码生成成功`)
  } catch (error) {
    console.error('下载代码失败:', error)
    ElMessage.error(`${tableName} 代码生成失败`)
  }
}

// 获取预览代码
const fetchPreviewCode = async () => {
  if (!selectedTable.value) {
    return
  }

  try {
    // 使用表中的默认配置
    const params = {
      module_name: selectedTable.value.app_name || '',
      author: selectedTable.value.author || '',
      preview: true
    }
    const res = await Api.previewCode(selectedTable.value.id, params)

    if (res.code === 2000 && res.data) {
      previewFiles.value = res.data.files || {}
      const fileNames = Object.keys(previewFiles.value)
      if (fileNames.length > 0) {
        activePreviewFile.value = fileNames[0]
        currentCode.value = previewFiles.value[fileNames[0]]
      }
      previewMode.value = true  // 切换到预览模式
    } else {
      ElMessage.error('预览代码失败')
    }
  } catch (error) {
    console.error('预览代码失败:', error)
    ElMessage.error('预览代码失败')
  }
}

// 退出预览模式
const exitPreviewMode = () => {
  previewMode.value = false
}

// 复制代码
const copyCode = () => {
  if (currentCode.value) {
    navigator.clipboard.writeText(currentCode.value).then(() => {
      ElMessage.success('代码已复制到剪贴板')
    }).catch(() => {
      ElMessage.error('复制失败')
    })
  }
}

// 监听标签页切换，更新代码内容
watch(activePreviewFile, (newFile) => {
  if (newFile && previewFiles.value[newFile]) {
    currentCode.value = previewFiles.value[newFile]
  }
})

onMounted(() => {
  // 初始化字典Store
  dictStore.initDict()
  fetchTableTree()
})

onUnmounted(() => {
  destroySortable()
})
</script>

<style scoped lang="scss">
.gen-table-container {
  height: 100%;
  padding: 16px;
  background-color: var(--el-bg-color-page);

  // 缩小相邻按钮间距（仅本页面生效）
  :deep(.el-button + .el-button) {
    margin-left: 6px !important;
  }
}

.main-container {
  height: 100%;
  background-color: white;
  border-radius: 8px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.1);
  overflow: hidden;
}

// 左侧表管理面板
.table-panel {
  background-color: #f5f7fa;
  border-right: 1px solid #dcdfe6;
  display: flex;
  flex-direction: column;

  .panel-header {
    padding: 16px;
    border-bottom: 1px solid #dcdfe6;
    background-color: white;
  }

  .table-list {
    flex: 1;
    padding: 8px;
  }

  .table-item {
    padding: 8px 10px;
    margin-bottom: 6px;
    background-color: white;
    border-radius: 4px;
    cursor: pointer;
    transition: all 0.2s;
    border: 1px solid transparent;
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 6px;

    &:hover {
      background-color: #ecf5ff;
      border-color: #409eff;
    }

    &.active {
      background-color: #409eff;
      color: white;
      border-color: #409eff;

      .table-db-name {
        color: rgba(255, 255, 255, 0.8);
      }
    }

    &.selected {
      background-color: #ecf5ff;
      border-color: #67c23a;
    }

    .table-info {
      flex: 1;
      display: flex;
      align-items: center;
      gap: 6px;
      min-width: 0;
      overflow: hidden;

      .table-name {
        font-size: 14px;
        font-weight: 500;
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
        flex: 1;
        min-width: 0;
      }

      .table-db-name {
        font-size: 11px;
        color: #909399;
        flex-shrink: 0;
        white-space: nowrap;
      }

      &.active .table-db-name {
        color: rgba(255, 255, 255, 0.8);
      }
    }

    .table-actions {
      display: flex;
      gap: 2px;
      opacity: 1;
      flex-shrink: 0;
      margin-left: auto;

      :deep(.el-button) {
        padding: 3px 3px;
        font-size: 13px;
      }
    }
  }
}

// 右侧列面板
.column-panel {
  padding: 0;
  display: flex;
  flex-direction: column;

  .panel-header {
    padding: 16px 24px;
    border-bottom: 1px solid #dcdfe6;
    display: flex;
    align-items: center;
    justify-content: space-between;

    .header-title {
      font-size: 16px;
      font-weight: 500;
      color: #303133;
    }

    .header-actions {
      display: flex;
      gap: 8px;
    }
  }

  .column-list {
    flex: 1;
    padding: 16px 24px;
    overflow-x: hidden;
  }

  :deep(.drag-handle) {
    cursor: move;
    font-size: 18px;
    color: #909399;

    &:hover {
      color: #409eff;
    }
  }

  // 减少表格单元格的padding
  :deep(.el-table--default .cell) {
    padding: 0 3px !important;
  }

  // 减少check-tag的padding
  :deep(.el-check-tag) {
    padding: 4px 8px !important;
    font-size: 12px;
  }
}

// 拖拽幽灵样式
:deep(.sortable-ghost) {
  opacity: 0.5;
  background-color: #ecf5ff;
}

// 代码预览弹窗
.code-preview {
  background-color: #f5f7fa;
  padding: 16px;
  border-radius: 4px;
  font-size: 13px;
  line-height: 1.6;
  overflow-x: auto;
}

// 预览模式容器
.preview-container {
  height: 100%;
  display: flex;
  flex-direction: column;
  background-color: #fff;
  border-radius: 4px;

  .preview-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    border-bottom: 1px solid #e4e7ed;
    padding: 0 16px;
    background-color: #f5f7fa;

    .preview-tabs {
      flex: 1;

      :deep(.el-tabs__nav) {
        border: none;
      }

      :deep(.el-tabs__item) {
        border: none;
        padding: 0 20px;
        height: 40px;
        line-height: 40px;
        font-size: 13px;
        color: #606266;

        &.is-active {
          color: #409eff;
          background-color: #ecf5ff;
        }
      }

      :deep(.el-tabs__content) {
        display: none;
      }
    }

    .el-button {
      margin-left: 12px;
    }
  }

  .code-editor-wrapper {
    flex: 1;
    overflow: hidden;
    background-color: #fff;
    border-top: 1px solid #e4e7ed;

    .code-editor {
      height: 100%;
      font-size: 13px;
      font-family: 'JetBrains Mono', 'Fira Code', Consolas, 'Courier New', monospace;

      :deep(.cm-editor) {
        height: 100%;
        background-color: #fff;
      }

      :deep(.cm-scroller) {
        overflow: auto;
        font-family: 'JetBrains Mono', 'Fira Code', Consolas, 'Courier New', monospace;
      }

      :deep(.cm-content) {
        padding: 8px 0;
        font-size: 13px;
      }

      :deep(.cm-gutters) {
        background-color: #f5f7fa;
        border-right: 1px solid #e4e7ed;
        color: #909399;
        font-size: 12px;
      }

      :deep(.cm-activeLineGutter) {
        background-color: #ecf5ff;
      }

      :deep(.cm-line) {
        line-height: 1.6;
      }
    }
  }
}
</style>