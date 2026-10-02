package com.tauru.astrbotphoneagent.logic

internal fun commandValidationError(command: CommandEnvelope, nowSeconds: Double): String? = when {
    command.type != "command" || command.schemaVersion != COMMAND_SCHEMA_VERSION -> "不支持的命令协议"
    !Regex("[A-Za-z0-9._-]{1,80}").matches(command.commandId) -> "命令 ID 无效"
    command.action !in COMMAND_ACTIONS -> "不支持的命令"
    command.deadline !in 1..300 -> "命令时限无效"
    !command.createdAtEpochSec.isFinite() -> "命令时间无效"
    command.createdAtEpochSec > nowSeconds + 300 -> "设备与服务器时间不一致"
    nowSeconds >= command.createdAtEpochSec + command.deadline -> "命令已过期，请重新发起"
    else -> null
}
