package com.tauru.astrbotphoneagent.logic

/** Only fixed messages and an HTTP status are shown; response bodies stay private. */
internal object ApiFailureMessage {
    fun forResponse(status: Int?, code: String?, health: Boolean): String {
        val message = when {
            status == 401 || code == "unauthorized" -> "登录凭据已过期，请更新插件 JWT"
            status == 404 -> "接口不存在，请检查插件地址并升级服务器插件"
            health -> when (code) {
                "device_changed", "no_device" -> "手机尚未注册或设备绑定已变化，请等待连接恢复后重试"
                "forbidden", "disabled" -> "服务器未允许健康同步，请检查插件和健康功能开关"
                "invalid_capture_time" -> "手机与服务器时间不一致，请校准时间后重试"
                "invalid_metric_value", "invalid_metrics", "invalid_days", "invalid_date" -> "健康摘要未通过服务器校验，请更新 App 与插件后重试"
                "invalid_schema", "invalid_source", "invalid_permissions", "invalid_zone", "invalid_device", "invalid_snapshot_id", "invalid_json" -> "健康同步协议不兼容，请更新 App 与服务器插件"
                "snapshot_conflict", "snapshot_older_than_current" -> "服务器已有更新的快照，请重新读取后同步"
                "storage_unavailable" -> "服务器无法保存健康快照，请检查磁盘与写入权限"
                "payload_too_large" -> "健康摘要超过服务器大小限制"
                else -> if (status == 403) "服务器拒绝健康同步，请检查登录凭据和健康功能开关" else "服务器未完成健康同步，请稍后重试"
            }
            status == 403 || code == "forbidden" -> "认证失败，请检查插件令牌或 API Key"
            else -> when (code) {
                "not_found" -> "这条提醒已不存在，请刷新列表"
                "invalid_minutes", "invalid_text", "validation_error", "invalid_request" -> "提醒内容或时间不符合要求"
                "too_many_reminders", "limit_reached", "reminder_limit" -> "待办提醒数量已达上限"
                else -> "服务器未完成请求，请稍后重试"
            }
        }
        return if (status != null && status !in 200..299) "$message（HTTP $status）" else message
    }
}
