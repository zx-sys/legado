<template>
  <div class="legado-page">
    <el-card shadow="hover" class="filter-card">
      <el-form :inline="true" :model="formInline" @submit.prevent>
        <el-form-item label="手机号/昵称">
          <el-input v-model.trim="formInline.search" clearable placeholder="手机号或昵称" style="width: 220px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" icon="Search" @click="search">查询</el-button>
          <el-button icon="Refresh" @click="reset">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="hover">
      <el-table v-loading="loading" :data="rows" border stripe>
        <el-table-column prop="phone" label="手机号" min-width="150" />
        <el-table-column prop="nickname" label="昵称" min-width="130" show-overflow-tooltip />
        <el-table-column prop="backup_count" label="备份数" width="90" />
        <el-table-column prop="book_count" label="书架数" width="90" />
        <el-table-column prop="create_time" label="首次使用" min-width="165" />
        <el-table-column prop="update_time" label="最近更新" min-width="165" />
        <el-table-column label="操作" fixed="right" width="120">
          <template #default="{ row }">
            <el-button link type="primary" @click="edit(row)">编辑</el-button>
            <el-button link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <Pagination :child-msg="pageparm" @callFather="changePage" />
    </el-card>

    <el-dialog v-model="visible" title="编辑手机用户" width="460px">
      <el-form :model="form" label-width="80px">
        <el-form-item label="手机号"><el-input v-model="form.phone" disabled /></el-form-item>
        <el-form-item label="昵称"><el-input v-model.trim="form.nickname" maxlength="64" /></el-form-item>
        <el-form-item label="备注"><el-input v-model.trim="form.remark" type="textarea" :rows="3" maxlength="255" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="visible = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup name="legadoUserManage">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import Pagination from '@/components/Pagination.vue'
import api from './api.js'

const loading = ref(false)
const rows = ref([])
const formInline = reactive({ search: '' })
const pageparm = reactive({ page: 1, limit: 20, total: 0 })
const visible = ref(false)
const form = reactive({ id: null, phone: '', nickname: '', remark: '' })

const load = async () => {
  loading.value = true
  try {
    const res = await api.userList({ ...formInline, ...pageparm })
    if (res.code === 2000) { rows.value = res.data.data; pageparm.total = res.data.total }
    else ElMessage.error(res.msg)
  } finally { loading.value = false }
}
const search = () => { pageparm.page = 1; load() }
const reset = () => { formInline.search = ''; search() }
const changePage = (p) => { pageparm.page = p.page; pageparm.limit = p.limit; load() }
const edit = (row) => { Object.assign(form, row); visible.value = true }
const save = async () => {
  const res = await api.userEdit({ ...form })
  if (res.code === 2000) { ElMessage.success('已保存'); visible.value = false; load() }
  else ElMessage.error(res.msg)
}
const remove = async (row) => {
  await ElMessageBox.confirm(`删除用户 ${row.phone} 及其备份/书架数据？`, '提示', { type: 'warning' })
  const res = await api.userDelete({ id: row.id })
  if (res.code === 2000) { ElMessage.success('已删除'); load() } else ElMessage.error(res.msg)
}
onMounted(load)
</script>

<style scoped>
.legado-page { padding: 16px; }
.filter-card { margin-bottom: 12px; }
</style>
