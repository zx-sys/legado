/*
 * @Descripttion: CRUD 操作的 composable
 * @version: 1.0
 */

import { reactive, ref, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'

/**
 * CRUD 管理 composable
 * @param {Object} api - API 对象，包含 getApi, addApi, editApi, deleteApi 方法
 * @param {Object} options - 配置选项
 * @returns {Object} CRUD 方法和状态
 */
export function useCrud(api, options = {}) {
	const {
		immediate = true,
		pageSize = 20,
		labelField = 'name',
	} = options

	// 列表数据
	const tableData = ref([])
	const loading = ref(false)

	// 分页
	const currentPage = ref(1)
	const pageSizeRef = ref(pageSize)
	const total = ref(0)

	// 表单
	const formData = ref({})
	const formDialogVisible = ref(false)
	const isEdit = ref(false)

	// 选择的行
	const selectedRows = ref([])

	/**
	 * 获取列表数据
	 */
	const getList = async (params = {}) => {
		loading.value = true
		try {
			const queryParams = {
				page: currentPage.value,
				page_size: pageSizeRef.value,
				...params
			}
			const res = await api.getList(queryParams)
			if (res.code === 2000) {
				tableData.value = res.data.results || res.data || []
				total.value = res.data.count || res.data.total || 0
			}
		} catch (error) {
			console.error('获取列表失败:', error)
			ElMessage.error('获取数据失败')
		} finally {
			loading.value = false
		}
	}

	/**
	 * 打开新增对话框
	 */
	const openAddDialog = () => {
		formData.value = {}
		isEdit.value = false
		formDialogVisible.value = true
	}

	/**
	 * 打开编辑对话框
	 */
	const openEditDialog = (row) => {
		formData.value = { ...row }
		isEdit.value = true
		formDialogVisible.value = true
	}

	/**
	 * 提交表单
	 */
	const submitForm = async (data = formData.value) => {
		loading.value = true
		try {
			let res
			if (isEdit.value) {
				// 编辑
				res = await api.update(data.id, data)
			} else {
				// 新增
				res = await api.add(data)
			}

			if (res.code === 2000) {
				ElMessage.success(isEdit.value ? '更新成功' : '添加成功')
				formDialogVisible.value = false
				getList()
			} else {
				ElMessage.error(res.msg || '操作失败')
			}
		} catch (error) {
			console.error('提交失败:', error)
			ElMessage.error('操作失败')
		} finally {
			loading.value = false
		}
	}

	/**
	 * 删除
	 */
	const handleDelete = async (row) => {
		try {
			await ElMessageBox.confirm(
				`确定要删除${row[labelField] || '此记录'}吗？`,
				'提示',
				{
					confirmButtonText: '确定',
					cancelButtonText: '取消',
					type: 'warning',
				}
			)

			loading.value = true
			const res = await api.delete(row.id)
			if (res.code === 2000) {
				ElMessage.success('删除成功')
				getList()
			} else {
				ElMessage.error(res.msg || '删除失败')
			}
		} catch (error) {
			if (error !== 'cancel') {
				console.error('删除失败:', error)
				ElMessage.error('删除失败')
			}
		} finally {
			loading.value = false
		}
	}

	/**
	 * 批量删除
	 */
	const handleBatchDelete = async () => {
		if (selectedRows.value.length === 0) {
			ElMessage.warning('请选择要删除的记录')
			return
		}

		try {
			await ElMessageBox.confirm(
				`确定要删除选中的 ${selectedRows.value.length} 条记录吗？`,
				'提示',
				{
					confirmButtonText: '确定',
					cancelButtonText: '取消',
					type: 'warning',
				}
			)

			loading.value = true
			const ids = selectedRows.value.map(row => row.id)
			const res = await api.batchDelete(ids)

			if (res.code === 2000) {
				ElMessage.success('批量删除成功')
				selectedRows.value = []
				getList()
			} else {
				ElMessage.error(res.msg || '批量删除失败')
			}
		} catch (error) {
			if (error !== 'cancel') {
				console.error('批量删除失败:', error)
				ElMessage.error('批量删除失败')
			}
		} finally {
			loading.value = false
		}
	}

	/**
	 * 分页大小改变
	 */
	const handleSizeChange = (val) => {
		pageSizeRef.value = val
		currentPage.value = 1
		getList()
	}

	/**
	 * 当前页改变
	 */
	const handleCurrentChange = (val) => {
		currentPage.value = val
		getList()
	}

	/**
	 * 重置表单
	 */
	const resetForm = () => {
		formData.value = {}
	}

	/**
	 * 刷新列表
	 */
	const refresh = () => {
		currentPage.value = 1
		getList()
	}

	// 如果设置了立即加载，则在创建时获取数据
	if (immediate) {
		getList()
	}

	return {
		// 数据
		tableData,
		loading,
		currentPage,
		pageSize: pageSizeRef,
		total,
		formData,
		formDialogVisible,
		isEdit,
		selectedRows,

		// 方法
		getList,
		openAddDialog,
		openEditDialog,
		submitForm,
		handleDelete,
		handleBatchDelete,
		handleSizeChange,
		handleCurrentChange,
		resetForm,
		refresh,
	}
}

/**
 * 简单的列表数据管理 composable
 * @param {Function} fetchApi - 获取数据的 API 函数
 * @param {Object} options - 配置选项
 * @returns {Object} 列表管理的方法和状态
 */
export function useListData(fetchApi, options = {}) {
	const {
		immediate = true,
		transform = null, // 数据转换函数
	} = options

	const data = ref([])
	const loading = ref(false)
	const error = ref(null)

	const fetch = async (...args) => {
		loading.value = true
		error.value = null
		try {
			const res = await fetchApi(...args)
			if (res.code === 2000) {
				data.value = transform ? transform(res.data) : res.data
			} else {
				error.value = res.msg || '获取数据失败'
				ElMessage.error(error.value)
			}
		} catch (err) {
			error.value = err.message || '获取数据失败'
			console.error('获取数据失败:', err)
		} finally {
			loading.value = false
		}
	}

	// 刷新
	const refresh = (...args) => {
		return fetch(...args)
	}

	// 重置
	const reset = () => {
		data.value = []
		error.value = null
	}

	if (immediate) {
		fetch()
	}

	return {
		data,
		loading,
		error,
		fetch,
		refresh,
		reset,
	}
}
