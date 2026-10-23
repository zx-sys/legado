<template>
  <div class="legado-page">
    <el-card shadow="hover" class="filter-card">
      <el-form :inline="true" :model="formInline" @submit.prevent>
        <el-form-item label="手机号">
          <el-input v-model.trim="formInline.search" clearable placeholder="手机号/书名/作者" style="width: 220px" />
        </el-form-item>
        <el-form-item label="分组">
          <el-input v-model.trim="formInline.group" clearable placeholder="分组" style="width: 130px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" icon="Search" @click="search">查询</el-button>
          <el-button icon="Refresh" @click="reset">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="hover">
      <el-table v-loading="loading" :data="rows" border stripe>
        <el-table-column prop="user_phone" label="手机号" min-width="130" />
        <el-table-column prop="name" label="书名" min-width="180" show-overflow-tooltip />
        <el-table-column prop="author" label="作者" min-width="110" show-overflow-tooltip />
        <el-table-column prop="origin_name" label="书源" min-width="120" show-overflow-tooltip />
        <el-table-column prop="dur_chapter_title" label="当前章节" min-width="180" show-overflow-tooltip />
        <el-table-column label="进度" width="100">
          <template #default="{ row }">
            {{ row.dur_chapter_index + 1 }}<span v-if="row.total_chapter_num"> / {{ row.total_chapter_num }}</span>
          </template>
        </el-table-column>
        <el-table-column label="阅读时长" width="110">
          <template #default="{ row }">{{ formatDuration(row.read_time) }}</template>
        </el-table-column>
        <el-table-column label="最后阅读" width="165">
          <template #default="{ row }">{{ formatTime(row.last_read_time) }}</template>
        </el-table-column>
        <el-table-column label="书签/标注" width="100">
          <template #default="{ row }">{{ row.bookmark_count || 0 }} / {{ row.highlight_count || 0 }}</template>
        </el-table-column>
        <el-table-column prop="update_time" label="更新时间" min-width="165" />
        <el-table-column label="操作" fixed="right" width="150">
          <template #default="{ row }">
            <el-button link type="primary" @click="showDetail(row)">详情</el-button>
            <el-button link type="danger" @click="remove(row)">移除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <Pagination :child-msg="pageparm" @callFather="changePage" />
    </el-card>

    <el-dialog v-model="detailVisible" :title="`${current.name || ''} · 书签与标注`" width="860px">
      <el-tabs v-model="detailTab">
        <el-tab-pane label="书签" name="bookmark">
          <el-table :data="bookmarks" border size="small">
            <el-table-column prop="chapter_name" label="章节" min-width="180" show-overflow-tooltip />
            <el-table-column prop="book_text" label="书签文本" min-width="220" show-overflow-tooltip />
            <el-table-column prop="content" label="备注" min-width="180" show-overflow-tooltip />
            <el-table-column label="时间" width="165">
              <template #default="{ row }">{{ formatTime(row.time) }}</template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
        <el-tab-pane label="标注" name="highlight">
          <el-table :data="highlights" border size="small">
            <el-table-column prop="chapter_name" label="章节" min-width="180" show-overflow-tooltip />
            <el-table-column prop="book_text" label="标注文本" min-width="220" show-overflow-tooltip />
            <el-table-column prop="note" label="笔记" min-width="160" show-overflow-tooltip />
            <el-table-column label="时间" width="165">
              <template #default="{ row }">{{ formatTime(row.time) }}</template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-dialog>
  </div>
</template>

<script setup name="bookshelfManage">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import Pagination from '@/components/Pagination.vue'
import api from './api.js'

const loading = ref(false)
const rows = ref([])
const formInline = reactive({ search: '', group: '' })
const pageparm = reactive({ page: 1, limit: 20, total: 0 })
const detailVisible = ref(false)
const detailTab = ref('bookmark')
const bookmarks = ref([])
const highlights = ref([])
const current = ref({})

const load = async () => {
  loading.value = true
  try {
    const res = await api.bookList({ ...formInline, ...pageparm })
    if (res.code === 2000) {
      rows.value = res.data.data
      pageparm.total = res.data.total
    } else ElMessage.error(res.msg)
  } finally { loading.value = false }
}
const search = () => { pageparm.page = 1; load() }
const reset = () => { Object.assign(formInline, { search: '', group: '' }); search() }
const changePage = (p) => { pageparm.page = p.page; pageparm.limit = p.limit; load() }
const formatTime = (ms) => {
  if (!ms) return '-'
  const d = new Date(Number(ms))
  const pad = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}
const showDetail = async (row) => {
  current.value = row
  detailVisible.value = true
  detailTab.value = 'bookmark'
  bookmarks.value = []
  highlights.value = []
  const [b, h] = await Promise.all([api.bookBookmarks(row.id), api.bookHighlights(row.id)])
  bookmarks.value = b.code === 2000 ? (b.data.data || b.data) : []
  highlights.value = h.code === 2000 ? (h.data.data || h.data) : []
}
const formatDuration = (ms) => {
  if (!ms) return '0分'
  const minutes = Math.floor(ms / 60000)
  if (minutes < 60) return `${minutes}分`
  return `${Math.floor(minutes / 60)}时${minutes % 60}分`
}
const remove = async (row) => {
  await ElMessageBox.confirm(`确定从书架移除《${row.name}》？`, '提示', { type: 'warning' })
  const res = await api.bookDelete({ id: row.id })
  if (res.code === 2000) { ElMessage.success('已移除'); load() } else ElMessage.error(res.msg)
}
onMounted(load)
</script>

<style scoped>
.legado-page { padding: 16px; }
.filter-card { margin-bottom: 12px; }
</style>
