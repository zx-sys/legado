/*
 * @Descripttion: 表单验证工具
 * @version: 1.0
 */

//检查是否为IP
export const checkIsIp = function(rule,value,callback){
    if (value === '' || typeof value === 'undefined' || value == null) {
		let required = rule.required
		if(!required){
			callback();
		}
        callback(new Error("请输入IP"));
    } else {
        const reg = /^(\d{1,2}|1\d\d|2[0-4]\d|25[0-5])\.(\d{1,2}|1\d\d|2[0-4]\d|25[0-5])\.(\d{1,2}|1\d\d|2[0-4]\d|25[0-5])\.(\d{1,2}|1\d\d|2[0-4]\d|25[0-5])$/;
        if (!reg.test(value) && value !== '') {
            callback(new Error("请输入正确IP"));
        } else {
            callback();
        }
    }
}

//检查是否为IPv4 子网
export const checkIsIpv4Subnet = function(rule,value,callback){
    if (value === '' || typeof value === 'undefined' || value == null) {
		let required = rule.required
		if(!required){
			callback();
		}
        callback(new Error("请输入子网"));
    } else {
        const reg = /^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)(\/(3[0-2]|[12]?[0-9]))$/;
        if (!reg.test(value) && value !== '') {
            callback(new Error("请输入正确ipv4网段"));
        } else {
            callback();
        }
    }
}

//检查是否为有效容器 镜像名:标签 形式（镜像名必须）
export const checkIsDockerImageAndTag = function(rule,value,callback){
    if (value === '' || typeof value === 'undefined' || value == null) {
		let required = rule.required
		if(!required){
			callback();
		}
        callback(new Error("请输入必填项，如：mysql:latest"));
    } else {
        const reg = /^[a-zA-Z0-9@:/\.-_,]{1,200}(?::[a-zA-Z0-9@:/\.-_,]{1,200})?$/;
        if (!reg.test(value) && value !== '') {
            callback(new Error("格式错误：支持字母、数字、@:/.-_ 且长度1-200"));
        } else {
            callback();
        }
    }
}
