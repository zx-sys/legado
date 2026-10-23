/*
 * @Descripttion: 存储工具
 * @version: 1.0
 */

import Cookies from 'js-cookie'
import appConfig from '@/config'

export const autoStorage = {
	set(key, val) {
		//过期时间
		let datetime = 0
		let newVal = val
		//加密
		if(appConfig.LS_ENCRYPTION == "AES"){
			val = crypto.AES.encrypt(JSON.stringify(val), appConfig.LS_ENCRYPTION_KEY)
			newVal = {
				content: val,
				datetime: parseInt(datetime) === 0 ? 0 : new Date().getTime() + parseInt(datetime) * 1000
			}
		}

		appConfig.STORAGE_METHOD === "localStorage" ? window.localStorage.setItem(key,newVal):window.sessionStorage.setItem(key,newVal)
	},
	get(key) {
		try {
			const result =  appConfig.STORAGE_METHOD === "localStorage" ? window.localStorage.getItem(key):window.sessionStorage.getItem(key)
			if (result != null) {
				//解密
				if(appConfig.LS_ENCRYPTION == "AES"){
					let nowTime = new Date().getTime()
					if (nowTime > result.datetime && result.datetime != 0) {
						autoStorage.removeItem(key)
						return null;
					}
					result.content = JSON.parse(crypto.AES.decrypt(result.content, appConfig.LS_ENCRYPTION_KEY))
				}
				if(result == "true"){
					return true
				}else if(result == "false"){
					return false
				}
				return result
			}
			return null
		}catch (err) {
            return null
        }
	},
	remove(key) {
        appConfig.STORAGE_METHOD === "localStorage" ? window.localStorage.removeItem(key):window.sessionStorage.removeItem(key)
	},
	clear() {
        appConfig.STORAGE_METHOD === "localStorage" ? window.localStorage.clear():window.sessionStorage.clear()
		Cookies.remove('logintoken');
	},
	clearAll(){
		window.localStorage.clear()
		window.sessionStorage.clear()
	}
}

export const getToken = function(key = 'logintoken') {
    return autoStorage.get(key)
}

export const setToken = function(key='logintoken',val) {
    return autoStorage.set(key,val)
}

export const getRefreshToken = function(key = 'refreshtoken') {
    return autoStorage.get(key)
}

export const setRefreshToken = function(key='refreshtoken',val) {
    return autoStorage.set(key,val)
}

// 保持向后兼容，保留拼写错误的函数名
export const getRereshToken = getRefreshToken

export const Local = {
    // 设置永久缓存
	set(key, val) {
		window.localStorage.setItem(key, JSON.stringify(val));
	},
	// 获取永久缓存
	get(key) {
		let json = window.localStorage.getItem(key);
		return JSON.parse(json);
	},
	// 移除永久缓存
	remove(key) {
		window.localStorage.removeItem(key);
	},
	// 移除全部永久缓存
	clear() {
		window.localStorage.clear();
	},
}

export const Session = {
    // 设置临时缓存
	set(key, val) {
		window.sessionStorage.setItem(key, JSON.stringify(val));
	},
	// 获取临时缓存
	get(key) {
		let json = window.sessionStorage.getItem(key);
		return JSON.parse(json);
	},
	// 移除临时缓存
	remove(key) {
		window.sessionStorage.removeItem(key);
	},
	// 移除全部临时缓存
	clear() {
		window.sessionStorage.clear();
	},
}
