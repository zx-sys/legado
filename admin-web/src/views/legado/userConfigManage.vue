<template>
  <div class="legado-page">
    <el-card shadow="hover" class="filter-card">
      <el-form :inline="true" :model="formInline" @submit.prevent>
        <el-form-item label="手机号">
          <el-input v-model.trim="formInline.search" clearable placeholder="手机号" style="width: 180px" />
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="formInline.config_type" clearable placeholder="全部" style="width: 150px">
            <el-option v-for="(label, key) in typeMap" :key="key" :label="label" :value="key" />
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
        <el-table-column label="配置类型" min-width="120">
          <template #default="{ row }">{{ typeMap[row.config_type] || row.config_type }}</template>
        </el-table-column>
        <el-table-column prop="source_file" label="来源文件" min-width="150" />
        <el-table-column label="内容大小" width="100">
          <template #default="{ row }">{{ (row.content || '').length }} B</template>
        </el-table-column>
        <el-table-column prop="update_time" label="更新时间" min-width="165" />
        <el-table-column label="操作" fixed="right" width="160">
          <template #default="{ row }">
            <el-button link type="primary" @click="view(row)">查看/编辑</el-button>
            <el-button link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <Pagination :child-msg="pageparm" @callFather="changePage" />
    </el-card>

    <el-dialog v-model="visible" :title="`用户配置 · ${form.user_phone || ''}`" width="820px">
      <el-form label-width="80px">
        <el-form-item label="类型">
          <el-input :model-value="typeMap[form.config_type] || form.config_type" disabled />
        </el-form-item>
        <el-form-item label="内容">
          <el-input v-model="form.content" type="textarea" :rows="20" spellcheck="false" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="visible = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup name="userConfigManage">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import Pagination from '@/components/Pagination.vue'
import api from './api.js'

const typeMap = {
  read_config: '阅读排版',
  share_read_config: '分享排版',
  theme_config: '主题配置',
  app_config: '应用设置',
  video_config: '视频设置',
  book_source_config: '书源选择'
}
const loading = ref(false)
const rows = ref([])
const formInline = reactive({ search: '', config_type: '' })
const pageparm = reactive({ page: 1, limit: 20, total: 0 })
const visible = ref(false)
const form = reactive({ id: null, user_phone: '', config_type: '', content: '' })

const load = async () => {
  loading.value = true
  try {
    const res = await api.userConfigList({ ...formInline, ...pageparm })
    if (res.code === 2000) { rows.value = res.data.data; pageparm.total = res.data.total }
    else ElMessage.error(res.msg)
  } finally { loading.value = false }
}
const search = () => { pageparm.page = 1; load() }
const reset = () => { Object.assign(formInline, { search: '', config_type: '' }); search() }
const changePage = (p) => { pageparm.page = p.page; pageparm.limit = p.limit; load() }
const view = (row) => { Object.assign(form, row); visible.value = true }
const save = async () => {
  const res = await api.userConfigEdit({ id: form.id, content: form.content })
  if (res.code === 2000) { ElMessage.success('已保存'); visible.value = false; load() }
  else ElMessage.error(res.msg)
}
const remove = async (row) => {
  await ElMessageBox.confirm('确定删除这份用户配置？', '提示', { type: 'warning' })
  const res = await api.userConfigDelete({ id: row.id })
  if (res.code === 2000) { ElMessage.success('已删除'); load() } else ElMessage.error(res.msg)
}
onMounted(load)
</script>

<style scoped>
.legado-page { padding: 16px; }
.filter-card { margin-bottom: 12px; }
</style>
