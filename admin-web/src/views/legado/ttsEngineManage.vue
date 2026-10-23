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
          <el-button type="success" icon="Plus" @click="add">新增引擎</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="hover">
      <el-table v-loading="loading" :data="rows" border stripe>
        <el-table-column prop="sort" label="排序" width="70" />
        <el-table-column prop="name" label="名称" min-width="160" show-overflow-tooltip />
        <el-table-column prop="code" label="标识" min-width="150" show-overflow-tooltip />
        <el-table-column prop="engine_type" label="类型" width="110" />
        <el-table-column prop="languages" label="语言" width="100" />
        <el-table-column prop="speaker_count" label="音色" width="70" />
        <el-table-column prop="version" label="版本" width="90" />
        <el-table-column label="包大小" width="100">
          <template #default="{ row }">{{ formatSize(row.package_size) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag v-if="row.is_default" type="warning" style="margin-right: 4px">默认</el-tag>
            <el-tag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '启用' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" fixed="right" width="200">
          <template #default="{ row }">
            <el-button link type="primary" @click="edit(row)">编辑</el-button>
            <el-button link type="primary" @click="pickPackage(row)">上传包</el-button>
            <el-button link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <Pagination :child-msg="pageparm" @callFather="changePage" />
      <input ref="packageInput" type="file" style="display:none" @change="uploadPackage" />
    </el-card>

    <el-dialog v-model="visible" :title="form.id ? '编辑 TTS 引擎' : '新增 TTS 引擎'" width="820px" top="5vh">
      <el-form :model="form" label-width="110px">
        <el-row :gutter="12">
          <el-col :span="12"><el-form-item label="名称"><el-input v-model.trim="form.name" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="标识 code"><el-input v-model.trim="form.code" :disabled="!!form.id" /></el-form-item></el-col>
          <el-col :span="12">
            <el-form-item label="类型">
              <el-select v-model="form.engine_type" style="width:100%">
                <el-option label="sherpa-onnx" value="sherpa_onnx" />
                <el-option label="HTTP TTS" value="http" />
                <el-option label="系统 TTS" value="system" />
                <el-option label="其他" value="other" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12"><el-form-item label="支持语言"><el-input v-model.trim="form.languages" placeholder="zh,en" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="音色数量"><el-input-number v-model="form.speaker_count" :min="1" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="版本"><el-input v-model.trim="form.version" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="排序"><el-input-number v-model="form.sort" :min="0" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="许可证"><el-input v-model.trim="form.license" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="来源地址"><el-input v-model.trim="form.source_url" /></el-form-item></el-col>
          <el-col :span="24"><el-form-item label="下载地址"><el-input v-model.trim="form.package_url" placeholder="可在保存后使用“上传包”自动填入" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="启用"><el-switch v-model="form.enabled" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="默认引擎"><el-switch v-model="form.is_default" /></el-form-item></el-col>
          <el-col :span="24"><el-form-item label="说明"><el-input v-model="form.description" type="textarea" :rows="3" /></el-form-item></el-col>
          <el-col :span="24">
            <el-form-item label="扩展配置 JSON">
              <el-input v-model="form.config_text" type="textarea" :rows="8" spellcheck="false" />
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

<script setup name="ttsEngineManage">
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
  id: null, name: '', code: '', engine_type: 'sherpa_onnx', languages: 'zh,en',
  speaker_count: 1, version: '', license: '', source_url: '', package_url: '',
  description: '', enabled: true, is_default: false, sort: 1, config_text: '{}'
})
const form = reactive(defaultForm())

const load = async () => {
  loading.value = true
  try {
    const res = await api.ttsList({ ...formInline, ...pageparm })
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
const edit = (row) => {
  Object.assign(form, row)
  form.config_text = JSON.stringify(row.config || {}, null, 2)
  visible.value = true
}
const save = async () => {
  let config
  try { config = JSON.parse(form.config_text || '{}') } catch { return ElMessage.error('扩展配置 JSON 格式错误') }
  const payload = {
    id: form.id, name: form.name, code: form.code, engine_type: form.engine_type,
    languages: form.languages, speaker_count: form.speaker_count, version: form.version,
    license: form.license, source_url: form.source_url, package_url: form.package_url,
    description: form.description, enabled: form.enabled, is_default: form.is_default,
    sort: form.sort, config
  }
  const res = form.id ? await api.ttsEdit(payload) : await api.ttsAdd(payload)
  if (res.code === 2000) { ElMessage.success('已保存'); visible.value = false; load() }
  else ElMessage.error(res.msg)
}
const pickPackage = (row) => { uploadTarget.value = row; packageInput.value.click() }
const uploadPackage = async (event) => {
  const file = event.target.files[0]
  if (!file || !uploadTarget.value) return
  const formData = new FormData()
  formData.append('file', file)
  const res = await api.ttsUpload(uploadTarget.value.id, formData)
  if (res.code === 2000) { ElMessage.success('引擎包上传成功'); load() } else ElMessage.error(res.msg)
  event.target.value = ''
  uploadTarget.value = null
}
const remove = async (row) => {
  await ElMessageBox.confirm(`确定删除 TTS 引擎「${row.name}」？`, '提示', { type: 'warning' })
  const res = await api.ttsDelete({ id: row.id })
  if (res.code === 2000) { ElMessage.success('已删除'); load() } else ElMessage.error(res.msg)
}
onMounted(load)
</script>

<style scoped>
.legado-page { padding: 16px; }
.filter-card { margin-bottom: 12px; }
</style>
