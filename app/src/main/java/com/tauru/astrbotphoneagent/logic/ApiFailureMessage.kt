package com.tauru.astrbotphoneagent.logic

/** Only fixed messages and an HTTP status are shown; response bodies stay private. */
internal object ApiFailureMessage {
    fun forResponse(status: Int?, code: String?, health: Boolean, task: Boolean = false): String {
        val message = when {
            status == 401 || code == "unauthorized" -> "登录凭据已过期，请更新插件 JWT"
            status == 404 -> "接口不存在，请检查插件地址并升级服务器插件"
            task -> when (code) {
                "ui_tasks_disabled", "disabled" -> "服务器未开启界面任务、观察或任务执行，请检查插件配置"
                "app_backend_required" -> "界面任务需要服务器使用 App 控制模式"
                "app_upgrade_required" -> "请先将执行设备上的 Phone Control 升级到 0.4.0 或更高版本"
                "unknown_device", "unknown_requester", "invalid_device_id" -> "设备尚未注册或已变化，请刷新设备列表"
                "device_role_required" -> "请先明确执行设备的用途"
                "invalid_ui_goal_or_package", "package_not_enabled" -> "请填写任务目标，并选择服务器已允许操作的 App"
                "invalid_ui_texts" -> "输入内容或完成条件不符合要求，每类最多 5 项、每项最多 200 字"
                "invalid_ui_step_limit" -> "步骤上限超出服务器允许范围，请刷新后重试"
                "invalid_completion_mode" -> "完成条件协议不兼容，请更新 App 与服务器插件"
                "approval_not_pending", "approval_expired" -> "这项确认已过期或已处理，请刷新任务列表"
                "device_changed", "authorization_changed" -> "执行设备或授权已变化，请重新创建任务"
                "device_busy", "bot_role_in_use" -> "执行设备正在忙，或已有其他 Bot 手机占用该用途"
                "personal_device_pinned", "bot_device_cannot_be_personal" -> "已有个人手机用途固定，Bot 设备不能替换个人手机"
                "invalid_device_label", "invalid_device_role" -> "设备名称或用途不符合服务器要求"
                "model_unavailable" -> "服务器没有可用的任务模型，请检查模型配置"
                "allowed_user_required", "forbidden", "unauthorized" -> "服务器未允许此任务，请检查用户授权、令牌和功能开关"
                "storage_unavailable", "device_registry_invalid", "device_binding_mismatch" -> "服务器暂时无法安全保存或读取任务，请检查服务器日志"
                "not_found" -> "任务已不存在，请刷新列表"
                else -> "服务器未完成任务请求，请刷新后重试"
            }
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
