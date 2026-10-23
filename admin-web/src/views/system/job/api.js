import {ajaxGet,ajaxPost,ajaxDelete,ajaxPut} from '@/api/request.js';
const api = {}

// 定时任务管理
api.list = params => ajaxGet({url: `/api/scheduler/jobs/`, params})
// 定时任务管理 -- 新增
api.add = params => ajaxPost({url: `/api/scheduler/jobs/`,params})
// 定时任务管理 -- 编辑
api.edit = params => ajaxPut({url: `/api/scheduler/jobs/`,params})
// 定时任务管理 -- 删除
api.del = params => ajaxDelete({url: `/api/scheduler/jobs/`,params})
// 定时任务管理 -- 立即执行
api.run = params => ajaxPost({url: `/api/scheduler/jobs/${params.id}/run/`, params: {}})
// 定时任务管理 -- 启动
api.start = params => ajaxPost({url: `/api/scheduler/jobs/${params.id}/start/`, params: {}})
// 定时任务管理 -- 停止
api.stop = params => ajaxPost({url: `/api/scheduler/jobs/${params.id}/stop/`, params: {}})

// 执行日志管理
api.logList = params => ajaxGet({url: `/api/scheduler/executions/`, params})
// 执行日志管理 -- 删除
api.logDel = params => ajaxDelete({url: `/api/scheduler/executions/`,params})
// 执行日志管理 -- 清空日志
api.logClear = () => ajaxDelete({url: `/api/scheduler/executions/clear/`, params: {}})

export default api
