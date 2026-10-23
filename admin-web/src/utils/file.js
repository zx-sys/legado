/*
 * @Descripttion: 文件操作工具
 * @version: 1.0
 */

import { MsgWarn } from '@/utils/message';
import Api from '@/api/api';

export const downloadFileContent = function (content, fileName) {
    const downloadUrl = window.URL.createObjectURL(new Blob([content]));
    const a = document.createElement('a');
    a.style.display = 'none';
    a.href = downloadUrl;
    a.download = fileName;
    const event = new MouseEvent('click');
    a.dispatchEvent(event);
}

export const getDownloadFile = function (fileName) {
	Api.sysFileGetToken({filename:fileName}).then(res => {
		if (res.code == 2000) {
			let params = res.data
			Api.downloadFile(params)
		} else {
			MsgWarn(res.msg)
		}
	})
}

export const addCopySuffix = function (name) {
	const parts = name.split('.');
	if (parts.length > 1) {
	  	// 如果有扩展名，则在扩展名之前加上'-副本'
	  	return parts.slice(0, -1).join('.') + '-副本.' + parts[parts.length - 1];
	} else {
	  	// 如果没有扩展名，则在最后加上'-副本'
	  	return name + '-副本';
	}
}

export const getFileExt = function (fileName) {
	// 从文件名中获取扩展名
	const lastDotIndex = fileName.lastIndexOf('.');
	if (lastDotIndex === -1) {
		// 如果没有找到点，返回空字符串或其他默认值
		return '';
	} else {
		// 使用 substring 方法获取最后一个点之后的部分作为扩展名
		return fileName.substring(lastDotIndex + 1).toLowerCase();
	}
}

// 获取路径中的文件名，如果为目录则获取目录名
export const getFileNameFromPath = function(path) {
	const isWindowsPath = /^[A-Za-z]:\//.test(path);
	if(isWindowsPath){
		if(path.length>3){
			path = path.replace(/\/$/, '');
		}else{
			return ""
		}
	}else{
		if(path.length>1){
			path = path.replace(/\/$/, '');
		}
		if(path === "/"){
			return ""
		}
	}
    // 使用 split('/') 将路径按照斜杠分割成数组，并取最后一个元素作为文件名
    const parts = path.split('/');
    return parts[parts.length - 1];
}

// 获取assets静态资源
export const getAssetsFile = (fileName="",forder=null) => {
	const path = forder?`/src/assets/lybbn/imgs/${forder}/${fileName}`:`/src/assets/lybbn/imgs/${fileName}`
	const modules = import.meta.glob(`@/assets/lybbn/imgs/**/*.{png,svg,jpg,jpeg}`, { eager: true });
	if (modules[path]) return modules[path].default;
	else {
	  // 地址错误
	  //console.error("Error url is wrong path");
	}
};

/**
 * 从响应头解析文件名（兼容 RFC 5987 和传统格式）
 */
export function extractFilenameFromHeaders(headers) {
	const disposition = headers['content-disposition'];
	if (!disposition) return null;

	// 1. 匹配 RFC 5987 格式（现代浏览器）：filename*=UTF-8''中文名.xlsx
	const utf8Filename = disposition.match(/filename\*=UTF-8''([^;]+)/i);
	if (utf8Filename) {
		return decodeURIComponent(utf8Filename[1]);
	}

	// 2. 匹配传统格式：filename="%E4%B8%AD%E6%96%87.xlsx"
	const legacyFilename = disposition.match(/filename="([^"]+)"/i);
	if (legacyFilename) {
		return decodeURIComponent(legacyFilename[1]);
	}

	// 3. 无引号格式：filename=%E4%B8%AD%E6%96%87.xlsx
	const rawFilename = disposition.match(/filename=([^;]+)/i);
	if (rawFilename) {
		return decodeURIComponent(rawFilename[1].trim());
	}

	return null;
}
