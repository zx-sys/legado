<!--密码强度检测组件-->
<template>
	<div class="ly-password-strength">
		<div class="ly-password-strength-bar" :class="`ly-password-strength-level-${level}`"></div>
	</div>
</template>

<script setup>
import { ref, watch, onMounted } from 'vue'

const props = defineProps({
	modelValue: { type: String, default: "" }
})

const level = ref(0)

/**
 * 计算密码强度
 * @param {string} password - 待检测的密码
 * @returns {number} 强度等级 (0-5)
 */
function strength(password) {
	let _level = 0

	// 空密码
	if (password.length <= 0) {
		level.value = 0
		return
	}

	// 检查长度是否达到要求（至少6位）
	if (password.length < 6) {
		level.value = 1
		return
	}

	// 检查是否包含数字
	if (/\d/.test(password)) {
		_level += 1
	}

	// 检查是否包含小写字母
	if (/[a-z]/.test(password)) {
		_level += 1
	}

	// 检查是否包含大写字母
	if (/[A-Z]/.test(password)) {
		_level += 1
	}

	// 检查是否有连续3位相同字符
	if (!/(\w)\1{2}/.test(password)) {
		_level += 1
	}

	// 检查是否包含特殊字符
	if (/[`~!@#$%^&*()_+<>?:"{},./;'[\]]/.test(password)) {
		_level += 1
	}

	level.value = _level
}

// 监听 modelValue 变化
watch(() => props.modelValue, (newVal) => {
	strength(newVal)
})

// 组件挂载时计算初始值
onMounted(() => {
	strength(props.modelValue)
})
</script>

<style scoped>
.ly-password-strength {
	height: 5px;
	width: 100%;
	background: var(--el-color-info-light-5);
	border-radius: 5px;
	position: relative;
	margin: 10px 0;
}

.ly-password-strength:before {
	left: 20%;
}

.ly-password-strength:after {
	right: 20%;
}

.ly-password-strength:before,
.ly-password-strength:after {
	position: absolute;
	content: "";
	display: block;
	width: 20%;
	height: inherit;
	border: 5px solid var(--el-bg-color-overlay);
	border-top: 0;
	border-bottom: 0;
	z-index: 1;
	background-color: transparent;
	box-sizing: border-box;
}

.ly-password-strength-bar {
	position: absolute;
	height: inherit;
	width: 0%;
	border-radius: inherit;
	transition: width .5s ease-in-out, background .25s;
	background: transparent;
}

.ly-password-strength-level-1 {
	width: 20%;
	background-color: var(--el-color-error);
}

.ly-password-strength-level-2 {
	width: 40%;
	background-color: var(--el-color-error);
}

.ly-password-strength-level-3 {
	width: 60%;
	background-color: var(--el-color-warning);
}

.ly-password-strength-level-4 {
	width: 80%;
	background-color: var(--el-color-success);
}

.ly-password-strength-level-5 {
	width: 100%;
	background-color: var(--el-color-success);
}
</style>
