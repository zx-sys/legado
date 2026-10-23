<template>
  <div class="legado-page">
    <el-card shadow="hover" class="filter-card">
      <el-form :inline="true" :model="formInline" @submit.prevent>
        <el-form-item label="手机号">
          <el-input v-model.trim="formInline.search" clearable placeholder="手机号/设备/文件名" style="width: 200px" />
        </el-form-item>
        <el-form-item label="设备">
          <el-input v-model.trim="formInline.device_name" clearable placeholder="设备名称" style="width: 160px" />
        </el-form-item>
        <el-form-item label="触发">
          <el-select v-model="formInline.trigger" clearable placeholder="全部" style="width: 110px">
            <el-option label="自动" value="auto" />
            <el-option label="手动" value="manual" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" icon="Search" @click="search">查询</el-button>
          <el-button icon="Refresh" @click="reset">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="hover" class="table-card">
      <el-table v-loading="loading" :data="rows" border stripe>
        <el-table-column prop="user_phone" label="手机号" min-width="130" />
        <el-table-column prop="device_name" label="设备" min-width="130" show-overflow-tooltip />
        <el-table-column prop="file_name" label="备份文件" min-width="180" show-overflow-tooltip />
        <el-table-column label="触发" width="80">
          <template #default="{ row }">{{ row.trigger === 'auto' ? '自动' : '手动' }}</template>
        </el-table-column>
        <el-table-column label="大小" width="100">
          <template #default="{ row }">{{ formatSize(row.size_bytes) }}</template>
        </el-table-column>
        <el-table-column prop="item_count" label="文件数" width="80" />
        <el-table-column prop="create_time" label="备份时间" min-width="165" />
        <el-table-column label="操作" fixed="right" width="260">
          <template #default="{ row }">
            <el-button link type="primary" @click="showDetail(row)">明细</el-button>
            <el-button link type="primary" @click="download(row)">下载</el-button>
            <el-button link type="warning" @click="restore(row)">还原模块</el-button>
            <el-button link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <Pagination :child-msg="pageparm" @callFather="changePage" />
    </el-card>

    <el-dialog v-model="detailVisible" title="备份明细" width="760px">
      <div v-loading="detailLoading">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="手机号">{{ current.user_phone }}</el-descriptions-item>
          <el-descriptions-item label="设备">{{ current.device_name }}</el-descriptions-item>
          <el-descriptions-item label="文件">{{ current.file_name }}</el-descriptions-item>
          <el-descriptions-item label="时间">{{ current.create_time }}</el-descriptions-item>
        </el-descriptions>
        <el-table :data="detailItems" border size="small" style="margin-top: 12px">
          <el-table-column prop="path" label="路径" show-overflow-tooltip />
          <el-table-column prop="storage" label="存储" width="80" />
          <el-table-column label="大小" width="100">
            <template #default="{ row }">{{ formatSize(row.size_bytes) }}</template>
          </el-table-column>
        </el-table>
      </div>
    </el-dialog>
  </div>
</template>

<script setup name="backupManage">
import { onMounted, reactive, ref } from 'vue'
import axios from 'axios'
import { ElMessage, ElMessageBox } from 'element-plus'
import Pagination from '@/components/Pagination.vue'
import sysConfig from '@/config'
import { getToken } from '@/utils/util'
import api from './api.js'

const loading = ref(false)
const rows = ref([])
const formInline = reactive({ search: '', device_name: '', trigger: '' })
const pageparm = reactive({ page: 1, limit: 20, total: 0 })
const detailVisible = ref(false)
const detailLoading = ref(false)
const detailItems = ref([])
const current = ref({})

const load = async () => {
  loading.value = true
  try {
    const res = await api.backupList({ ...formInline, ...pageparm })
    if (res.code === 2000) {
      rows.value = res.data.data
      pageparm.total = res.data.total
    } else ElMessage.error(res.msg)
  } finally {
    loading.value = false
  }
}
const search = () => { pageparm.page = 1; load() }
const reset = () => { Object.assign(formInline, { search: '', device_name: '', trigger: '' }); search() }
const changePage = (p) => { pageparm.page = p.page; pageparm.limit = p.limit; load() }
const formatSize = (n) => {
  if (!n) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB']
  let i = 0
  while (n >= 1024 && i < units.length - 1) { n /= 1024; i++ }
  return `${n.toFixed(1)} ${units[i]}`
}
const showDetail = async (row) => {
  current.value = row
  detailVisible.value = true
  detailLoading.value = true
  try {
    const res = await api.backupItems(row.id)
    detailItems.value = res.code === 2000 ? res.data : []
  } finally { detailLoading.value = false }
}
const download = async (row) => {
  const res = await axios.get(`${sysConfig.API_URL}/api/legado/backups/${row.id}/download/`, {
    responseType: 'blob',
    headers: { Authorization: sysConfig.TOKEN_PREFIX + getToken() }
  })
  const url = URL.createObjectURL(res.data)
  const a = document.createElement('a')
  a.href = url
  a.download = row.file_name || `backup-${row.id}.zip`
  a.click()
  URL.revokeObjectURL(url)
}
const restore = async (row) => {
  await ElMessageBox.confirm(
    `将把这份备份中的书架和用户配置覆盖到 ${row.user_phone} 的当前数据，确定？`,
    '还原到管理模块',
    { type: 'warning' }
  )
  const res = await api.backupRestore(row.id)
  if (res.code === 2000) {
    const result = res.data?.data || res.data
    ElMessage.success(`已还原：书架 ${result.books} 本，配置 ${result.configs} 项`)
    load()
  } else ElMessage.error(res.msg)
}
const remove = async (row) => {
  await ElMessageBox.confirm(`确定删除 ${row.user_phone} 的这份备份？`, '提示', { type: 'warning' })
  const res = await api.backupDelete({ id: row.id })
  if (res.code === 2000) { ElMessage.success('已删除'); load() } else ElMessage.error(res.msg)
}
onMounted(load)
</script>

<style scoped>
.legado-page { padding: 16px; }
.filter-card { margin-bottom: 12px; }
</style>
