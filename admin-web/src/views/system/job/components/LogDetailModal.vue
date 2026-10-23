<template>
    <el-dialog
        title="执行日志详情"
        v-model="showDialog"
        width="800px"
        :close-on-click-modal="false"
    >
        <div class="log-detail">
            <el-descriptions :column="1" border>
                <el-descriptions-item label="任务名称">{{ logData.job_name }}</el-descriptions-item>
                <el-descriptions-item label="任务组名">{{ logData.job_group }}</el-descriptions-item>
                <el-descriptions-item label="调用目标">{{ logData.invoke_target }}</el-descriptions-item>
                <el-descriptions-item label="执行状态">
                    <el-tag v-if="logData.status === '0'" type="success">成功</el-tag>
                    <el-tag v-else type="danger">失败</el-tag>
                </el-descriptions-item>
                <el-descriptions-item label="创建时间">{{ logData.create_time }}</el-descriptions-item>
                <el-descriptions-item label="日志信息" :span="2">
                    <div class="message-box">{{ logData.job_message }}</div>
                </el-descriptions-item>
                <el-descriptions-item v-if="logData.exception_info" label="异常信息" :span="2">
                    <div class="exception-box">{{ logData.exception_info }}</div>
                </el-descriptions-item>
            </el-descriptions>
        </div>
        <template #footer>
            <el-button type="primary" @click="showDialog = false">关闭</el-button>
        </template>
    </el-dialog>
</template>

<script setup>
import { ref, watch } from 'vue'

const props = defineProps({
    modelValue: {
        type: Boolean,
        default: false
    }
})

const emit = defineEmits(['update:modelValue'])

const showDialog = ref(false)
const logData = ref({
    job_name: '',
    job_group: '',
    invoke_target: '',
    status: '',
    create_time: '',
    job_message: '',
    exception_info: ''
})

// 监听props变化
watch(() => props.modelValue, (newVal) => {
    showDialog.value = newVal
})

watch(showDialog, (newVal) => {
    emit('update:modelValue', newVal)
})

const handleOpen = (data) => {
    logData.value = {
        job_name: data.job_name || '-',
        job_group: data.job_group || '-',
        invoke_target: data.invoke_target || '-',
        status: data.status || '-',
        create_time: data.create_time || '-',
        job_message: data.job_message || '-',
        exception_info: data.exception_info || ''
    }
    showDialog.value = true
}

// 暴露方法给父组件
defineExpose({
    handleOpen
})
</script>

<style lang="scss" scoped>
.log-detail {
    max-height: 500px;
    overflow-y: auto;
}

.exception-box,
.message-box {
    max-height: 300px;
    overflow-y: auto;
    background-color: #f5f5f5;
    padding: 10px;
    border-radius: 4px;
    white-space: pre-wrap;
    word-break: break-all;
    font-family: 'Courier New', monospace;
    font-size: 12px;
}

.exception-box {
    color: #f56c6c;
    background-color: #fef0f0;
}
</style>
