<template>
  <div class="legado-page">
    <el-card shadow="hover" class="filter-card">
      <el-form :inline="true" :model="formInline" @submit.prevent>
        <el-form-item label="关键词">
          <el-input v-model.trim="formInline.search" clearable placeholder="名称/URL/分组" style="width: 220px" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="formInline.enabled" clearable placeholder="全部" style="width: 100px">
            <el-option label="启用" :value="true" />
            <el-option label="停用" :value="false" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" icon="Search" @click="search">查询</el-button>
          <el-button icon="Refresh" @click="reset">重置</el-button>
          <el-button type="success" icon="Plus" @click="add">新增</el-button>
          <el-button icon="Upload" @click="fileRef.click()">导入 JSON</el-button>
          <input ref="fileRef" type="file" accept=".json" style="display:none" @change="importFile" />
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="hover">
      <el-table v-loading="loading" :data="rows" border stripe>
        <el-table-column prop="name" label="书源名称" min-width="180" show-overflow-tooltip />
        <el-table-column prop="source_url" label="地址" min-width="220" show-overflow-tooltip />
        <el-table-column prop="group" label="分组" min-width="120" show-overflow-tooltip />
        <el-table-column prop="weight" label="权重" width="70" />
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '启用' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="update_time" label="更新时间" min-width="165" />
        <el-table-column label="操作" fixed="right" width="140">
          <template #default="{ row }">
            <el-button link type="primary" @click="edit(row)">编辑</el-button>
            <el-button link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <Pagination :child-msg="pageparm" @callFather="changePage" />
    </el-card>

    <el-dialog v-model="visible" :title="form.id ? '编辑书源' : '新增书源'" width="820px" top="5vh">
      <el-form :model="form" label-width="100px">
        <el-row :gutter="12">
          <el-col :span="12"><el-form-item label="名称"><el-input v-model.trim="form.name" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="分组"><el-input v-model.trim="form.group" /></el-form-item></el-col>
          <el-col :span="24"><el-form-item label="书源 URL"><el-input v-model.trim="form.source_url" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="权重"><el-input-number v-model="form.weight" :min="0" /></el-form-item></el-col>
          <el-col :span="8">
            <el-form-item label="启用"><el-switch v-model="form.enabled" /></el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="类型"><el-input-number v-model="form.source_type" :min="0" /></el-form-item>
          </el-col>
          <el-col :span="24"><el-form-item label="搜索 URL"><el-input v-model="form.search_url" type="textarea" :rows="2" /></el-form-item></el-col>
          <el-col :span="24"><el-form-item label="备注"><el-input v-model="form.comment" type="textarea" :rows="3" /></el-form-item></el-col>
          <el-col :span="24">
            <el-form-item label="完整 JSON">
              <el-input v-model="form.raw_json" type="textarea" :rows="16" spellcheck="false" placeholder="粘贴 Legado 书源 JSON" />
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
      <template #footer>
        <el-button @click="visible = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup name="bookSourceManage">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import Pagination from '@/components/Pagination.vue'
import api from './api.js'

const loading = ref(false)
const rows = ref([])
const formInline = reactive({ search: '', enabled: '' })
const pageparm = reactive({ page: 1, limit: 20, total: 0 })
const visible = ref(false)
const fileRef = ref()
const defaultForm = () => ({
  id: null, name: '', source_url: '', group: '', source_type: 0,
  enabled: true, weight: 0, search_url: '', comment: '', raw_json: '{}'
})
const form = reactive(defaultForm())

const load = async () => {
  loading.value = true
  try {
    const res = await api.bookSourceList({ ...formInline, ...pageparm })
    if (res.code === 2000) { rows.value = res.data.data; pageparm.total = res.data.total }
    else ElMessage.error(res.msg)
  } finally { loading.value = false }
}
const search = () => { pageparm.page = 1; load() }
const reset = () => { Object.assign(formInline, { search: '', enabled: '' }); search() }
const changePage = (p) => { pageparm.page = p.page; pageparm.limit = p.limit; load() }
const add = () => { Object.assign(form, defaultForm()); visible.value = true }
const edit = (row) => {
  Object.assign(form, row)
  form.raw_json = JSON.stringify(row.raw_json || {}, null, 2)
  visible.value = true
}
const save = async () => {
  let raw
  try { raw = JSON.parse(form.raw_json || '{}') } catch { return ElMessage.error('完整 JSON 格式错误') }
  if (typeof raw !== 'object' || Array.isArray(raw)) return ElMessage.error('完整 JSON 必须是对象')
  Object.assign(raw, {
    bookSourceName: form.name,
    bookSourceUrl: form.source_url,
    bookSourceGroup: form.group,
    bookSourceType: form.source_type,
    enabled: form.enabled,
    weight: form.weight,
    searchUrl: form.search_url,
    bookSourceComment: form.comment
  })
  const payload = {
    id: form.id, name: form.name, source_url: form.source_url, group: form.group,
    source_type: form.source_type, enabled: form.enabled, weight: form.weight,
    search_url: form.search_url, comment: form.comment, raw_json: raw
  }
  const res = form.id ? await api.bookSourceEdit(payload) : await api.bookSourceAdd(payload)
  if (res.code === 2000) { ElMessage.success('已保存'); visible.value = false; load() }
  else ElMessage.error(res.msg)
}
const remove = async (row) => {
  await ElMessageBox.confirm(`确定删除书源「${row.name}」？`, '提示', { type: 'warning' })
  const res = await api.bookSourceDelete({ id: row.id })
  if (res.code === 2000) { ElMessage.success('已删除'); load() } else ElMessage.error(res.msg)
}
const importFile = async (event) => {
  const file = event.target.files[0]
  if (!file) return
  const formData = new FormData()
  formData.append('file', file)
  const res = await api.bookSourceImport(formData)
  if (res.code === 2000) { ElMessage.success(res.msg); load() } else ElMessage.error(res.msg)
  event.target.value = ''
}
onMounted(load)
</script>

<style scoped>
.legado-page { padding: 16px; }
.filter-card { margin-bottom: 12px; }
</style>
