# AstrBot Phone Agent App

Phone Agent 的 Android 前端：手机状态、提醒、结构化手机控制和自主关怀诊断。

项目不预设角色姓名、头像、服务器地址或任何密钥。安装后由用户配置自己的连接，并选择本地显示名称与头像。

配套后端：[astrbot-phone-agent](https://github.com/Tauru-t6/astrbot-phone-agent)。插件有两种模式：本 App 对应 `app` 模式；使用 OperitAI 时选择 `operit`。

聊天在 QQ 里进行，不由本 App 承载：`v0.3.5` 起移除了应用内聊天，App 只做设备侧的事。

## 功能

- **生活**：设备电量、前台应用（显示应用名）和当日应用使用时长，提供截图、状态和定位等快捷操作。
- **提醒**：在 App 新建、查看和取消服务器提醒；到期事件同步到时间线，有通知权限时显示本地通知。
- **任务**：查看真实执行状态；支持可重试任务，以及危险命令的手机端确认。
- **动态**：按消息、任务、提醒、纸笺筛选时间线。
- **自主功能**：只读展示服务端夜间守护状态与主动关怀状态（模式、安静时段、配额、最近决策）；未交付的能力明确标注，不编造。
- **连接设置**：编辑插件地址、Relay 与令牌；保存后测试插件连接。
- **用户资料**：本地设置或清除显示名称和头像；不提供预置角色头像。
- **权限入口**：Shizuku 授权、使用情况访问、通知和系统应用权限。

市集尚未开放。专注/批量应用限制没有完整的本地目标应用配置，不能把展示文案当作已完成的定时锁定功能。

## 下载

前往 [Releases](https://github.com/Tauru-t6/astrbot-phone-agent-app/releases) 下载 APK。Android 8.0（API 26）或更高版本；执行特权操作需要安装、启动并授权 [Shizuku](https://github.com/RikkaApps/Shizuku)。

本仓库首个公开版本使用新的应用包名，和早期私人调试版本属于不同应用，数据不会自动迁移。

## 首次连接

1. 在 AstrBot 安装配套插件，选择 `control_backend=app`。
2. 设置 `allowed_user_ids` 和随机 `app_shared_token`。需要提醒时开启 `enable_reminder_tools`。
3. 打开 App 的「纸笺 → 服务器连接」，填写下表对应值。
4. 点击「保存并测试连接」保存当前修改并检查连接；配置未修改时按钮显示「测试已保存的连接」。也可以只点「保存配置」。
5. 根据功能需要授予 Shizuku、使用情况访问、通知及定位权限。

| App 字段 | 用途 |
| --- | --- |
| 插件地址 | `http://SERVER:6185/api/plug/astrbot_plugin_phone_agent` |
| 插件令牌 | 已授权的 AstrBot Dashboard JWT，用于插件 `/api/plug/` 路由 |
| 手机共享 Token | 与插件的 `app_shared_token` 完全一致 |
| Relay 地址 / Token | 可选的自建中继队列地址与凭据 |

替换凭据时输入新值；凭据输入留空保留当前值，界面不会回显已保存的密钥。公开 APK 的初始值全部为空。

修改本地名称/头像只影响显示，不会修改 AstrBot 服务端的人设或记忆。

## 网络路径

```text
App ── App API ──> AstrBot
                    │
                    ├─ 局域网直连 :8260 ──> App / Shizuku
                    ├─ Tailscale 直连 ─────> App / Shizuku
                    └─ Relay 队列 <─────── App 轮询
```

插件的 `app_direct_urls` 是按顺序排列的直连地址，例如：

```text
http://192.0.2.10:8260,http://100.64.0.10:8260
```

示例地址仅作占位，必须替换为自己的手机地址。可把局域网地址放第一位、Tailscale 地址放第二位。App 也会注册本机检测到的局域网和 Tailscale 地址；发布包不硬编码作者的设备地址。

前台服务帮助设备保持连接，但厂商电池策略、强制停止、系统重启及网络断开仍会影响送达。提醒由服务器计时，手机离线时需要等重新连接才能同步；这不是闹钟或紧急通知工具。

## 本地数据与隐私

- 名称及头像保存在 App 私有目录，头像不会上传到 AstrBot。
- 选择图片后会生成受限尺寸的私有副本；清除头像只删除此副本，不删除相册原图。
- 任务、时间线和通知去重信息保存在手机本地。
- 地址与凭据保存在本机 DataStore；系统自动备份和 Android 12+ 设备迁移均显式排除应用数据，换机需要重新配置连接。
- 用户创建的提醒会提交到自己配置的 AstrBot 服务。
- 危险命令必须经过手机端确认；返回/关闭确认框视为拒绝。
- 截图命令通过当前直连或 Relay 返回压缩图像；聊天截图请求可由插件发送到发起会话。临时原图读取后删除，不会自动存入系统相册。

不要将手机命令端口直接暴露到不受信任的网络。公网 Relay 应使用 HTTPS 和随机 Token。

## 构建

依赖 JDK 17、Android SDK Platform 34。Gradle Wrapper 随仓库提供。

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Windows：

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-17'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

在本机 `local.properties` 指定 `sdk.dir`，或设置 `ANDROID_HOME`。该文件不应提交。

正式签名通过环境变量传入：`PHONE_AGENT_KEYSTORE`、`PHONE_AGENT_STORE_PASSWORD`、`PHONE_AGENT_KEY_ALIAS`、`PHONE_AGENT_KEY_PASSWORD`，然后运行 `:app:assembleRelease`。签名私钥不属于公开仓库，也不应上传为 Release 附件。

Compose 预览位于 `app/src/debug/`，只使用预览数据；Release 不包含预览入口。通信单测覆盖中文 HTTP 字节长度、分段读取、非法请求、数字/布尔参数、命令过期校验，以及屏幕文本解析（uiautomator XML 与 dumpsys 兜底共用同一解析器）。

持久 shell 和端口限流已拆为可独立测试的 JVM 组件：会话断开或超时后返回明确失败，不重放可能已执行的命令；取消会终止执行进程，输出队列和每次命令的输出都有上限。停止命令服务时同时关闭已连接的客户端。限流使用单调时钟并回收过期地址，避免长期运行时持续增长。回归测试覆盖断连、超时、取消、无尾换行输出及限流窗口边界。

连接设置检测未保存的修改并提供「保存并测试连接」；编辑任何字段后清除旧测试反馈，保存失败时不会继续测试旧配置。审批悬浮窗的显示与关闭统一在主线程完成，显示失败时继续使用通知提醒。

## 版本状态

`v0.3.26` 整理生活页、动态页和设置页的信息层级：首页聚合插件连接、同步时间和电量；使用统计未授权时显示明确的空状态并提供权限入口。设置页改为独立分组，连接表单按 AstrBot、手机控制和可选 Relay 组织，支持折叠诊断、未保存修改提示及键盘上方的保存/测试按钮。测试按钮只验证插件连接，不代表 Relay 或 Shizuku 已通过测试。详细说明见 `docs/UI_UPDATE_2026-11-09.md`。

`v0.3.25` 修复 `screen_text` 在部分设备上必然超时的问题（issue #1，Galaxy S10 / Android 12 上 uiautomator dump 超过 15 秒不返回，超过插件的传输窗口导致连接被断开）：dump 加 `timeout 8` 硬限制和独立 shell 短超时，临时文件先删后写避免读到旧数据，失败时回退到 `dumpsys activity top` 视图层级，两者都拿不到文本时返回 `error_code: ui_dump_unavailable` 而不是挂住连接；服务器 socket 读超时从 15 秒放宽到 60 秒，不再短于应用自身的 25 秒 shell 超时。`v0.3.6` 在自主功能页加入最近任务列表（试跑与真实执行、每步核验结论）。`v0.3.5` 移除了应用内聊天，接入服务端真实关怀状态，并修复了 Shizuku 远程进程等待导致所有 shell 命令失败的问题。真机后台存活、Shizuku OEM 兼容性及不同系统键盘仍需实际设备验证。

## 许可证

本仓库自有代码采用 [MIT License](LICENSE)。可选联动插件及第三方依赖遵循各自的许可证，本仓库的 MIT 授权不覆盖 Private Companion 的上游代码或服务器本地修改。
