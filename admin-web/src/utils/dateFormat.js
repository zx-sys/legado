/*
 * @Descripttion: 时间格式化工具
 * @version: 1.0
 */

export const dateFormat = function (date, fmt='yyyy-MM-dd hh:mm:ss') {
	date = new Date(date)
	var o = {
		"M+" : date.getMonth()+1,                 //月份
		"d+" : date.getDate(),                    //日
		"h+" : date.getHours(),                   //小时
		"m+" : date.getMinutes(),                 //分
		"s+" : date.getSeconds(),                 //秒
		"q+" : Math.floor((date.getMonth()+3)/3), //季度
		"S"  : date.getMilliseconds()             //毫秒
	};
	if(/(y+)/.test(fmt)) {
		fmt=fmt.replace(RegExp.$1, (date.getFullYear()+"").substr(4 - RegExp.$1.length));
	}
	for(var k in o) {
		if(new RegExp("("+ k +")").test(fmt)){
			fmt = fmt.replace(RegExp.$1, (RegExp.$1.length==1) ? (o[k]) : (("00"+ o[k]).substr((""+ o[k]).length)));
		}
	}
	return fmt;
}

export function formatTimestampDatetime(timestamp) {
	if (!timestamp) {
	  return '';  // 如果时间为空，返回空字符串
	}
	if (!isNumber(timestamp)){
		return timestamp
	}
	if (timestamp < 1000000000000) {
		timestamp = timestamp * 1000;
	}
	const date = new Date(timestamp);

	const year = date.getFullYear();
	const month = ('0' + (date.getMonth() + 1)).slice(-2); // 月份是从0开始的，所以加1
	const day = ('0' + date.getDate()).slice(-2);
	const hour = ('0' + date.getHours()).slice(-2);
	const minute = ('0' + date.getMinutes()).slice(-2);
	const second = ('0' + date.getSeconds()).slice(-2);

	return `${year}-${month}-${day} ${hour}:${minute}:${second}`;
}

export function formatTimestampDate(timestamp) {
	if (!timestamp) {
	  return '';  // 如果时间为空，返回空字符串
	}
	if (!isNumber(timestamp)){
		return timestamp
	}
	if (timestamp < 1000000000000) {
		timestamp = timestamp * 1000;
	}
	const date = new Date(timestamp);

	const year = date.getFullYear();
	const month = ('0' + (date.getMonth() + 1)).slice(-2); // 月份是从0开始的，所以加1
	const day = ('0' + date.getDate()).slice(-2);
	const hour = ('0' + date.getHours()).slice(-2);
	const minute = ('0' + date.getMinutes()).slice(-2);
	const second = ('0' + date.getSeconds()).slice(-2);

	return `${year}-${month}-${day}`;
}

export function convertToBeijingTime(utcString) {
	if(!utcString){return utcString}
	const utcDate = new Date(utcString);

	// 获取 UTC 时间的各个部分
	const year = utcDate.getUTCFullYear();
	const month = utcDate.getUTCMonth();
	const date = utcDate.getUTCDate();
	const hours = utcDate.getUTCHours();
	const minutes = utcDate.getUTCMinutes();
	const seconds = utcDate.getUTCSeconds();

	// 将 UTC 时间转换为北京时间（加 8 小时）
	const beijingDate = new Date(Date.UTC(year, month, date, hours + 8, minutes, seconds));

	// 格式化为 'YYYY-MM-DD HH:mm:ss' 格式
	const formattedDate = beijingDate.toISOString().slice(0, 19).replace('T', ' ');

	return formattedDate;
}

export const formatSecondsTime = (seconds) =>{
	if (seconds < 60) {
	  return `${seconds}秒`;
	} else if (seconds < 3600) {
	  const mins = Math.floor(seconds / 60);
	  const secs = seconds % 60;
	  return `${mins}分钟${secs}秒`;
	} else {
	  const hours = Math.floor(seconds / 3600);
	  const mins = Math.floor((seconds % 3600) / 60);
	  const secs = seconds % 60;
	  return `${hours}小时${mins}分钟${secs}秒`;
	}
}

export const UnitsTime = [
    { label: "秒", value: 's' },
    { label: "分钟", value: 'm' },
    { label: "小时", value: 'h' },
    { label: "天", value: 'd' },
    { label: "周", value: 'w' },
    { label: "月", value: 'M' },
    { label: "年", value: 'y' },
];

/**
 * 时间日期格式化
 * @param dateObj 如果为字符串 则原样返回，如果为date时间对象则返回格式化后的结果
 * @param format
 * @returns {*}
 */
export const dateFormats = (dateObj, format) => {
	if(typeof(dateObj) =='string'){
		return dateObj
	}
	let date = {
		'M+': dateObj.getMonth() + 1,
		'd+': dateObj.getDate(),
		'h+': dateObj.getHours(),
		'm+': dateObj.getMinutes(),
		's+': dateObj.getSeconds(),
		'q+': Math.floor((dateObj.getMonth() + 3) / 3),
		'S+': dateObj.getMilliseconds()
	};
	if (/(y+)/i.test(format)) {
		format = format.replace(RegExp.$1, (dateObj.getFullYear() + '').substr(4 - RegExp.$1.length))
	}
	for (let k in date) {
		if (new RegExp('(' + k + ')').test(format)) {
			format = format.replace(RegExp.$1, RegExp.$1.length === 1
			? date[k] : ('00' + date[k]).substr(('' + date[k]).length))
		}
	}
	return format
}

function isNumber(value) {
	return typeof value === 'number' && !isNaN(value);
}
