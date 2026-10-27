<template>
  <div class="legado-page">
    <el-card shadow="hover" class="filter-card">
      <el-form :inline="true" :model="formInline" @submit.prevent>
        <el-form-item label="关键词">
          <el-input v-model.trim="formInline.search" clearable placeholder="手机号/名称/URL" style="width: 220px" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="formInline.removed" clearable placeholder="全部" style="width: 110px">
            <el-option label="正常" :value="false" />
            <el-option label="已删除" :value="true" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" icon="Search" @click="search">查询</el-button>
          <el-button icon="Refresh" @click="reset">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="hover">
      <el-table v-loading="loading" :data="rows" border stripe>
        <el-table-column prop="user_phone" label="手机号" min-width="140" />
        <el-table-column prop="name" label="书源名称" min-width="160" show-overflow-tooltip />
        <el-table-column prop="source_url" label="书源地址" min-width="240" show-overflow-tooltip />
        <el-table-column label="启用" width="80">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '是' : '否' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.removed ? 'danger' : 'success'">{{ row.removed ? '已删除' : '正常' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="客户端更新时间" min-width="165">
          <template #default="{ row }">{{ formatTime(row.source_updated_at) }}</template>
        </el-table-column>
        <el-table-column prop="update_time" label="服务端更新时间" min-width="165" />
        <el-table-column label="操作" fixed="right" width="150">
          <template #default="{ row }">
            <el-button link type="primary" @click="view(row)">查看</el-button>
            <el-button link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <Pagination :child-msg="pageparm" @callFather="changePage" />
    </el-card>

    <el-dialog v-model="visible" :title="`自定义书源 · ${form.name || form.source_url || ''}`" width="860px">
      <el-form label-width="90px">
        <el-form-item label="手机号">{{ form.user_phone }}</el-form-item>
        <el-form-item label="名称">{{ form.name }}</el-form-item>
        <el-form-item label="地址">{{ form.source_url }}</el-form-item>
        <el-form-item label="启用">{{ form.enabled ? '是' : '否' }}</el-form-item>
        <el-form-item label="状态">{{ form.removed ? '已删除' : '正常' }}</el-form-item>
        <el-form-item label="书源 JSON">
          <el-input :model-value="sourceJson" type="textarea" :rows="20" readonly spellcheck="false" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="visible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup name="userBookSourceManage">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import Pagination from '@/components/Pagination.vue'
import api from './api.js'

const loading = ref(false)
const rows = ref([])
const formInline = reactive({ search: '', removed: '' })
const pageparm = reactive({ page: 1, limit: 20, total: 0 })
const visible = ref(false)
const form = reactive({
  id: null,
  user_phone: '',
  name: '',
  source_url: '',
  enabled: true,
  removed: false,
  raw_json: {}
})

const sourceJson = computed(() => JSON.stringify(form.raw_json || {}, null, 2))

const formatTime = value => {
  if (!value) return ''
  const date = new Date(Number(value))
  return Number.isNaN(date.getTime()) ? '' : date.toLocaleString()
}

const load = async () => {
  loading.value = true
  try {
    const params = { ...formInline, ...pageparm }
    if (params.removed === '') delete params.removed
    if (!params.search) delete params.search
    const res = await api.userBookSourceList(params)
    if (res.code === 2000) {
      rows.value = res.data.data
      pageparm.total = res.data.total
    } else {
      ElMessage.error(res.msg)
    }
  } finally {
    loading.value = false
  }
}

const search = () => {
  pageparm.page = 1
  load()
}

const reset = () => {
  formInline.search = ''
  formInline.removed = ''
  search()
}

const changePage = page => {
  pageparm.page = page.page
  pageparm.limit = page.limit
  load()
}

const view = row => {
  Object.assign(form, row)
  visible.value = true
}

const remove = async row => {
  await ElMessageBox.confirm(`确定删除 ${row.name || row.source_url} 吗？`, '提示', {
    type: 'warning'
  })
  const res = await api.userBookSourceDelete({ id: row.id })
  if (res.code === 2000) {
    ElMessage.success('已删除')
    load()
  } else {
    ElMessage.error(res.msg)
  }
}

onMounted(load)
</script>
