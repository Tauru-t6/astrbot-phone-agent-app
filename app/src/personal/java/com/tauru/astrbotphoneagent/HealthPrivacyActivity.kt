package com.tauru.astrbotphoneagent

import android.app.Activity
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView

/** Accessible from Health Connect's own permission and privacy links. */
class HealthPrivacyActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "健康数据使用说明"
        val text = TextView(this).apply {
            textSize = 17f
            setPadding(40, 48, 40, 48)
            this.text = """
                Health Connect 与 Phone Agent

                本功能默认关闭，只读取你选择的单一健康 App 来源及系统页面同意的健康类别。读取范围为最近 7 天的步数、睡眠、心率、血氧和活动热量。

                Phone Agent 不向 Health Connect 写入记录，也不删除其中的数据；不读取运动路线、位置或小米账号凭据。

                开启“同步健康摘要到服务器”后，按日汇总会发送至你配置的 AstrBot 插件服务器，用于你开启的健康查询和关怀功能。AstrBot 在回复或关怀时可能将健康摘要提供给你配置的模型服务。原始逐条心率、血氧记录不上传。

                服务器只保留当前手机最近一次快照，不将 Health Connect 与 xiaomi-sync 的数值相加。摘要带来源、日期和更新时间；没有数据不当成 0。健康数据不写入普通操作日志。

                关闭同步会停止本机读取并请求清除服务器快照；离线时清除请求在连接恢复后重试。你也可以在 Health Connect 撤销单项或全部权限。撤权要在下次同步或快照过期后才能反映到服务器，不能承诺离线时立即清除远端副本。

                自动同步依赖系统允许读取和设备连接服务运行。部分系统要求保持 App 在前台；读取被系统拒绝时会显示错误，不会把旧数据标为刚刚同步。
            """.trimIndent()
        }
        setContentView(ScrollView(this).apply { addView(text) })
    }
}
