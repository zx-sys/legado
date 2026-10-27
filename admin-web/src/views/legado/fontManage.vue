<template>
  <div class="legado-page">
    <el-card shadow="hover" class="filter-card">
      <el-form :inline="true" :model="formInline" @submit.prevent>
        <el-form-item label="关键词">
          <el-input v-model.trim="formInline.search" clearable placeholder="名称/标识/语言" style="width: 220px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" icon="Search" @click="search">查询</el-button>
          <el-button icon="Refresh" @click="reset">重置</el-button>
          <el-button type="success" icon="Plus" @click="add">新增字体</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="hover">
      <el-table v-loading="loading" :data="rows" border stripe>
        <el-table-column prop="sort" label="排序" width="70" />
        <el-table-column prop="name" label="名称" min-width="150" show-overflow-tooltip />
        <el-table-column prop="code" label="标识" min-width="130" show-overflow-tooltip />
        <el-table-column prop="languages" label="语言" width="90" />
        <el-table-column prop="version" label="版本" width="90" />
        <el-table-column prop="package_file_name" label="字体文件" min-width="170" show-overflow-tooltip />
        <el-table-column label="大小" width="100">
          <template #default="{ row }">{{ formatSize(row.package_size) }}</template>
        </el-table-column>
        <el-table-column prop="download_count" label="下载数" width="80" />
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '启用' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" fixed="right" width="210">
          <template #default="{ row }">
            <el-button link type="primary" @click="edit(row)">编辑</el-button>
            <el-button link type="primary" @click="pickPackage(row)">上传字体</el-button>
            <el-button link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <Pagination :child-msg="pageparm" @callFather="changePage" />
      <input ref="packageInput" type="file" accept=".ttf,.otf,.ttc" style="display:none" @change="uploadPackage" />
    </el-card>

    <el-dialog v-model="visible" :title="form.id ? '编辑字体' : '新增字体'" width="820px" top="5vh">
      <el-form :model="form" label-width="110px">
        <el-row :gutter="12">
          <el-col :span="12"><el-form-item label="名称"><el-input v-model.trim="form.name" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="标识 code"><el-input v-model.trim="form.code" :disabled="!!form.id" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="语言"><el-input v-model.trim="form.languages" placeholder="zh,en" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="版本"><el-input v-model.trim="form.version" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="排序"><el-input-number v-model="form.sort" :min="0" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="许可证"><el-input v-model.trim="form.license" placeholder="如 SIL OFL 1.1" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="来源地址"><el-input v-model.trim="form.source_url" /></el-form-item></el-col>
          <el-col :span="24"><el-form-item label="下载地址"><el-input v-model.trim="form.package_url" placeholder="保存后使用“上传字体”自动填入" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="启用"><el-switch v-model="form.enabled" /></el-form-item></el-col>
          <el-col :span="12">
            <el-form-item label="文件信息">
              <span class="file-info">{{ form.package_file_name || '-' }} · {{ formatSize(form.package_size) }}</span>
            </el-form-item>
          </el-col>
          <el-col :span="24"><el-form-item label="说明"><el-input v-model="form.description" type="textarea" :rows="3" /></el-form-item></el-col>
        </el-row>
      </el-form>
      <template #footer>
        <el-button @click="visible = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup name="fontManage">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import Pagination from '@/components/Pagination.vue'
import api from './api.js'

const loading = ref(false)
const rows = ref([])
const formInline = reactive({ search: '' })
const pageparm = reactive({ page: 1, limit: 20, total: 0 })
const visible = ref(false)
const packageInput = ref()
const uploadTarget = ref(null)
const defaultForm = () => ({
  id: null, name: '', code: '', languages: 'zh', version: '', license: '',
  source_url: '', package_url: '', package_file_name: '', package_size: 0,
  description: '', enabled: true, sort: 1
})
const form = reactive(defaultForm())

const load = async () => {
  loading.value = true
  try {
    const res = await api.fontList({ ...formInline, ...pageparm })
    if (res.code === 2000) { rows.value = res.data.data; pageparm.total = res.data.total }
    else ElMessage.error(res.msg)
  } finally { loading.value = false }
}
const search = () => { pageparm.page = 1; load() }
const reset = () => { formInline.search = ''; search() }
const changePage = (p) => { pageparm.page = p.page; pageparm.limit = p.limit; load() }
const formatSize = (n) => {
  if (!n) return '-'
  const units = ['B', 'KB', 'MB', 'GB']
  let i = 0
  while (n >= 1024 && i < units.length - 1) { n /= 1024; i++ }
  return `${n.toFixed(1)} ${units[i]}`
}
const add = () => { Object.assign(form, defaultForm()); visible.value = true }
const edit = (row) => { Object.assign(form, defaultForm(), row); visible.value = true }
const save = async () => {
  if (!form.name || !form.code) return ElMessage.error('名称和标识不能为空')
  const payload = {
    id: form.id, name: form.name, code: form.code, languages: form.languages,
    version: form.version, license: form.license, source_url: form.source_url,
    package_url: form.package_url, description: form.description,
    enabled: form.enabled, sort: form.sort
  }
  const res = form.id ? await api.fontEdit(payload) : await api.fontAdd(payload)
  if (res.code === 2000) { ElMessage.success('已保存'); visible.value = false; load() }
  else ElMessage.error(res.msg)
}
const pickPackage = (row) => { uploadTarget.value = row; packageInput.value.click() }
const uploadPackage = async (event) => {
  const file = event.target.files[0]
  if (!file || !uploadTarget.value) return
  const formData = new FormData()
  formData.append('file', file)
  const res = await api.fontUpload(uploadTarget.value.id, formData)
  if (res.code === 2000) { ElMessage.success('字体上传成功'); load() } else ElMessage.error(res.msg)
  event.target.value = ''
  uploadTarget.value = null
}
const remove = async (row) => {
  await ElMessageBox.confirm(`确定删除字体「${row.name}」？`, '提示', { type: 'warning' })
  const res = await api.fontDelete({ id: row.id })
  if (res.code === 2000) { ElMessage.success('已删除'); load() } else ElMessage.error(res.msg)
}
onMounted(load)
</script>

<style scoped>
.legado-page { padding: 16px; }
.filter-card { margin-bottom: 12px; }
.file-info { color: var(--el-text-color-secondary); font-size: 13px; }
</style>
