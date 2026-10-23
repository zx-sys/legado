<template>
  <ly-dialog v-model="dialogVisible" :title="dialogTitle" width="600px" :before-close="handleClose">
    <el-form
      :inline="false"
      :model="formData"
      :rules="rules"
      ref="formRef"
      label-position="right"
      label-width="100px"
    >
      <el-form-item label="应用名称" prop="app_name">
        <el-input v-model="formData.app_name" clearable placeholder="如: myapp" />
      </el-form-item>

      <el-form-item label="模型类名称" prop="model_name">
        <el-input v-model="formData.model_name" clearable placeholder="如: Task" />
      </el-form-item>

      <el-form-item label="表名称" prop="table_name">
        <el-input v-model="formData.table_name" clearable placeholder="如: cc_task" />
      </el-form-item>

      <el-form-item label="模型描述" prop="model_comment">
        <el-input v-model="formData.model_comment" clearable placeholder="如: 定时任务" />
      </el-form-item>

      <el-form-item label="父菜单目录" prop="parent_menu">
        <el-select
          v-model="formData.parent_menu"
          clearable
          filterable
          placeholder="选择父菜单目录（可选）"
          style="width: 100%"
        >
          <el-option
            v-for="menu in menuOptions"
            :key="menu.id"
            :label="menu.name"
            :value="menu.id"
          />
        </el-select>
      </el-form-item>

      <el-form-item label="关联子模型" prop="sub_model_name">
        <el-input v-model="formData.sub_model_name" clearable placeholder="可选" />
      </el-form-item>

      <el-form-item label="子模型外键名" prop="sub_model_fk_name">
        <el-input v-model="formData.sub_model_fk_name" clearable placeholder="可选" />
      </el-form-item>

      <el-form-item label="作者" prop="author">
        <el-input v-model="formData.author" clearable />
      </el-form-item>

      <el-form-item label="备注" prop="remark">
        <el-input type="textarea" :rows="3" v-model="formData.remark" />
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="handleClose" :loading="loading">取消</el-button>
      <el-button type="primary" @click="submitForm" :loading="loading">保存</el-button>
    </template>
  </ly-dialog>
</template>

<script setup>
import { ref, reactive, nextTick } from 'vue'
import { deepClone } from '@/utils/util'
import Api from '@/api/api'
import LyDialog from '@/components/dialog/dialog.vue'
import { ElMessage } from 'element-plus'

const emit = defineEmits(['refreshData'])

const dialogVisible = ref(false)
const loading = ref(false)
const dialogTitle = ref('')
const formRef = ref(null)
const menuOptions = ref([])
const menuLoading = ref(false)

const formData = reactive({
  id: null,
  app_name: '',
  model_name: '',
  model_comment: '',
  table_name: '',
  parent_menu: null,
  sub_model_name: '',
  sub_model_fk_name: '',
  author: '',
  remark: ''
})

const rules = reactive({
  app_name: [{ required: true, message: '请输入应用名称', trigger: 'blur' }],
  model_name: [{ required: true, message: '请输入模型类名称', trigger: 'blur' }],
  table_name: [{ required: true, message: '请输入表名称', trigger: 'blur' }]
})

// 获取菜单列表
const fetchMenuList = async () => {
  try {
    menuLoading.value = true
    const res = await Api.apiSystemMenu({ limit: 999 })
    if (res.code === 2000) {
      // 过滤出目录类型的菜单(type === 0)
      menuOptions.value = (res.data.data || res.data || []).filter(menu => menu.type === 0)
    }
  } catch (error) {
    console.error('获取菜单列表失败:', error)
  } finally {
    menuLoading.value = false
  }
}

const addModuleFn = async (item, flag) => {
  // 在首次打开时获取菜单列表
  if (menuOptions.value.length === 0) {
    fetchMenuList()
  }

  dialogVisible.value = true
  dialogTitle.value = flag
  if (item && typeof item === 'object') {
    // 确保 tableId 字段正确处理
    const data = deepClone(item)
    if (data.tableId) {
      formData.id = data.tableId
      delete data.tableId
    }
    Object.assign(formData, data)
  } else {
    resetForm()
  }

  await nextTick(() => {
    if (formRef.value) {
      formRef.value.clearValidate()
    }
  })
}

const resetForm = () => {
  Object.assign(formData, {
    id: null,
    app_name: '',
    model_name: '',
    model_comment: '',
    table_name: '',
    parent_menu: null,
    sub_model_name: '',
    sub_model_fk_name: '',
    author: '',
    remark: ''
  })
}

const handleClose = () => {
  resetForm()
  dialogVisible.value = false
}

const submitForm = async () => {
  try {
    const valid = await formRef.value.validate()
    if (!valid) return

    loading.value = true

    const params = { ...formData }
    console.log('提交参数:', formData.id,  params)
    const apiMethod = formData.id
      ? Api.updateGenTable
      : Api.addGenTable

    const res = await apiMethod(params)

    if (res.code === 2000) {
      ElMessage.success(res.msg || '保存成功')
      emit('refreshData')
      handleClose()
    } else {
      ElMessage.warning(res.msg || '保存失败')
    }
  } catch (error) {
    console.error('提交失败:', error)
    ElMessage.error('提交失败')
  } finally {
    loading.value = false
  }
}

defineExpose({
  addModuleFn
})
</script>

<style scoped>
.el-form-item {
  margin-bottom: 18px;
}

.el-textarea {
  width: 100%;
}
</style>
