package io.legado.app.data.entities

/**
 * 旧版内置 Edge TTS 的 loginUi 在 JS 单引号字符串里又写入了单引号，
 * 导致登录表单渲染时报“缺少 ;”。这里用等价的 JSON.stringify 版本替换。
 */
private const val FIXED_EDGE_TTS_LOGIN_UI =
    "@js:\nvar fyrArr = Object.keys(fyrM);\n" +
        "JSON.stringify([{name:\"发言人\",viewName:\"'发言人：'\",action:\"clear()\"," +
        "type:\"select\",chars:fyrArr,style:{layout_flexGrow:1,layout_flexBasisPercent:0.4}}," +
        "{name:\"api_key\",viewName:\"'API Key：'\",type:\"text\"," +
        "style:{layout_flexGrow:1,layout_flexBasisPercent:0.4}}]);"

internal fun repairLegacyHttpTtsLoginUi(loginUi: String?): String? {
    if (loginUi == null) return null
    return if (loginUi.contains("fyrM") && loginUi.contains("发言人")) {
        FIXED_EDGE_TTS_LOGIN_UI
    } else {
        loginUi
    }
}
