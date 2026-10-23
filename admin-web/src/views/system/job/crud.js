import api from "./api.js"

/**
 * 创建CRUD配置,支持覆盖配置
 * @param {Object} options - 自定义配置选项
 * @returns {Object} CRUD配置对象
 */
export function createCrudConfig(options = {}){
    //需修改：命名规则（文件名+Table）
    let tableName = "jobManageTable"
    //crud请求方法配置
    const request = {
        add:api.add,        // ✅ 改为 api.add
        del:api.del,        // ✅ 改为 api.del
        edit:api.edit,      // ✅ 改为 api.edit
        list:api.list,      // ✅ 改为 api.list
        run:api.run,        // ✅ 改为 api.run
        start:api.start,    // ✅ 改为 api.start
        stop:api.stop,      // ✅ 改为 api.stop
        ...(options.request || {}) // 允许覆盖请求方法
    }
    //table列配置
    const defaultColumns = [
        {
            label: "任务名称",
            prop: "job_name",
            minWidth: "150"
        },
        {
            label: "任务组名",
            prop: "job_group",
            minWidth: "120"
        },
        {
            label: "调用目标",
            prop: "invoke_target",
            minWidth: "200"
        },
        {
            label: "Cron表达式",
            prop: "cron_expression",
            minWidth: "180"
        },
        {
            label: "状态",
            prop: "status",
            width: "100"
        },
        {
            label: "创建时间",
            prop: "create_time",
            minWidth: "180"
        }
    ]

    return {
        crudOptions:{
            //接口配置
            request: request,
            //搜索栏目配置
            searchBar:{
                showSearchBar:true,//显示搜索栏目
                ...(options.searchBar || {})
            },
            //分页
            pagination:{
                hidePagination:false,
                ...(options.pagination || {})
            },
            //crud按钮配置
            rowHandle:{
                width: 280,//操作列宽度,0表示不显示表格操作列
                fixed:"right",//固定操作列在右侧
                ...(options.rowHandle || {})
            },
            //table属性
            table: {
                tableName:tableName,
                pageSize:10,
				rowKey: 'id',
				lazy: false,
                border:true,
                showSelectable:true,//表格显示复选项框
                hideImport:true,
                hideExport:true,
                ...(options.table || {})
			},
            //table列字段
            columns:options.columns || defaultColumns
        }
    }
}
