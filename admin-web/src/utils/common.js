/*
 * @Descripttion: 通用工具函数
 * @version: 1.0
 */

import { MsgError, MsgOk } from '@/utils/message';
import useClipboard from 'vue-clipboard3';
const { toClipboard } = useClipboard();

export function safeDelete(obj, key) {
	try {
		if (obj && typeof obj === 'object') {
			delete obj[key]
		}
	} catch (e) {
		console.warn('删除属性失败:', e)
	}
}

//拷贝文字
export const copyText = async function (content) {
    try {
        await toClipboard(content);
        MsgOk('复制成功');
    } catch (e) {
        MsgError('复制失败');
    }
}

//数据拷贝
export const deepClone = function(data){
    if(data){
       return JSON.parse(JSON.stringify(data))
    }
    return data
}

export const fullScreen = function (element) {
	var isFull = !!(document.webkitIsFullScreen || document.mozFullScreen || document.msFullscreenElement || document.fullscreenElement);
	if(isFull){
		if(document.exitFullscreen) {
			document.exitFullscreen();
		}else if (document.msExitFullscreen) {
			document.msExitFullscreen();
		}else if (document.mozCancelFullScreen) {
			document.mozCancelFullScreen();
		}else if (document.webkitExitFullscreen) {
			document.webkitExitFullscreen();
		}
	}else{
		if(element.requestFullscreen) {
			element.requestFullscreen();
		}else if(element.msRequestFullscreen) {
			element.msRequestFullscreen();
		}else if(element.mozRequestFullScreen) {
			element.mozRequestFullScreen();
		}else if(element.webkitRequestFullscreen) {
			element.webkitRequestFullscreen();
		}
	}
}

//触发全屏
//element this.$refs.xxx  需要全屏的元素
export const triggerFullScreen =function(element) {
	if (element.requestFullscreen) {
		element.requestFullscreen();
	} else if (element.mozRequestFullScreen) {
		element.mozRequestFullScreen();
	} else if (element.webkitRequestFullscreen) {
		element.webkitRequestFullscreen();
	} else if (element.msRequestFullscreen) {
		element.msRequestFullscreen();
	}
}

// 退出全屏
export const exitFullScreen=function(element) {
	if (element == undefined) {
		element = document.documentElement;
	}
	var exitMethod = document.exitFullscreen || document.mozCancelFullScreen || document.webkitExitFullscreen;
	if (exitMethod) {
		exitMethod.call(document);
	} else if (typeof window.ActiveXObject !== "undefined") { //IE
		var wscript = new ActiveXObject("WScript.Shell");
		!!wscript && wscript.SendKeys("{F11}");
	}
}

//字节转换，到指定单位结束 is_unit：是否显示单位  fixed：小数点位置 end_unit：结束单位
export const formatUnitSize = function (bytes, is_unit = true, fixed = 2, end_unit = '') {
    if (bytes === undefined) return 0;

    bytes = typeof bytes === 'string' ? parseInt(bytes) : bytes;

    const unit = [' B', ' KB', ' MB', ' GB', 'TB'];
    const divisor = 1024;

    for (let i = 0; i < unit.length; i++) {
        if ((end_unit && unit[i].trim() === end_unit.trim()) || (!end_unit && bytes < divisor)) {
            const val = i === 0 ? bytes : (fixed === 0 ? bytes : bytes.toFixed(fixed));
            return is_unit ? val + unit[i] : parseFloat(val);
        }
        bytes /= divisor;
    }
};

// 生成指定长度随机字符串
export const generateRandomString = function (length) {
	const characters = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
	let result = '';
	for (let i = 0; i < length; i++) {
	  const randomIndex = Math.floor(Math.random() * characters.length);
	  result += characters.charAt(randomIndex);
	}
	return result;
}

//数据拷贝
export const isEmpty = function(value){
    // 如果值是 null 或 undefined，直接返回 true
	if (value === null || value === undefined) {
		return true;
	}

	// 如果值是字符串，判断是否为空字符串
	if (typeof value === 'string' && value.trim() === '') {
		return true;
	}

	// 如果值是数组，判断是否为空数组
	if (Array.isArray(value) && value.length === 0) {
		return true;
	}

	// 如果值是对象，判断是否为空对象
	if (typeof value === 'object' && Object.keys(value).length === 0) {
		return true;
	}

	// 对于其他类型，返回 false，表示不为空
	return false;
}

export function isNumber(value) {
	return typeof value === 'number' && !isNaN(value);
}

export function isNull(value) {
	return (value === null) || (value === undefined);
}

export function getTableHeight(tableSelectHeight,allowPage=true){
    var pagination_height = allowPage?176:0;
    let height = (window.innerHeight || document.documentElement.clientHeight || document.body.clientHeight) - tableSelectHeight;
    var ua = navigator.userAgent;
    //获取当前设备类型（安卓或苹果）
    if (ua && /Android/.test(ua)) {
        return 700
    }
    else if (ua && /iPhone|ipad|ipod|ios/.test(ua)){
        return 700
    }
    else {
        return height - pagination_height
    }
}
