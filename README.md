# AstrBot Phone Agent App

AstrBot 的独立 Android 前端：聊天、手机状态、提醒和结构化手机控制。

项目不预设角色姓名、头像、服务器地址或任何密钥。安装后由用户配置自己的连接，并选择本地显示名称与头像。

配套后端：[astrbot-phone-agent](https://github.com/Tauru-t6/astrbot-phone-agent)。插件有两种模式：本 App 对应 `app` 模式；使用 OperitAI 时选择 `operit`。

## 功能

- **聊天**：AstrBot OpenAPI SSE 流式对话，支持中文输入、长回复滚动和本地聊天历史。
- **生活**：设备电量、前台应用和当日应用使用时长，提供截图、状态和定位等快捷操作。
- **提醒**：在 App 新建、查看和取消服务器提醒；到期事件同步到时间线，有通知权限时显示本地通知。
- **任务**：查看真实执行状态；支持可重试任务，以及危险命令的手机端确认。
- **发现**：按消息、任务、提醒、纸笺筛选时间线。
- **连接设置**：编辑服务器、插件、Relay、聊天用户标识；保存后测试插件连接。
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
4. 保存配置后点击「测试已保存连接」。
5. 根据功能需要授予 Shizuku、使用情况访问、通知及定位权限。

| App 字段 | 用途 |
| --- | --- |
| AstrBot 地址 | AstrBot 服务的根地址，如 `http://SERVER:6185` |
| 插件地址 | `http://SERVER:6185/api/plug/astrbot_plugin_phone_agent` |
| 聊天用户标识 | AstrBot OpenAPI 使用的 username；跨平台记忆归并由服务端配置 |
| OpenAPI Key | 用于 `/api/v1/chat` 的 OpenAPI 凭据 |
| 插件令牌 | 已授权的 AstrBot Dashboard JWT，用于插件 `/api/plug/` 路由；与 OpenAPI Key 不同 |
| 手机共享 Token | 与插件的 `app_shared_token` 完全一致 |
| Relay 地址 / Token | 可选的自建中继队列地址与凭据 |

替换凭据时输入新值；凭据输入留空保留当前值，界面不会回显已保存的密钥。公开 APK 的初始值全部为空。

聊天会话标识固定为 `phone_app`，避免每次打开应用产生新会话。修改本地名称/头像只影响显示，不会修改 AstrBot 服务端的人设或记忆。

## 网络路径

```text
App ── SSE / App API ──> AstrBot
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
- 聊天、任务、时间线和通知去重信息保存在手机本地。
- 地址与凭据保存在本机 DataStore，关闭系统自动备份。
- 用户发送的聊天和创建的提醒会提交到自己配置的 AstrBot 服务。
- 危险命令必须经过手机端确认；返回/关闭确认框视为拒绝。
- 截图保存到手机的 `Pictures/PhoneAgent/`，不会自动上传或发到聊天平台。

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

Compose 预览位于 `app/src/debug/`，只使用预览数据；Release 不包含预览入口。通信单测覆盖中文 HTTP 字节长度、分段读取、非法请求、数字/布尔参数和命令过期校验。

## 版本状态

`v0.2.0` 为首个公开预览版本。已完成本地编译与自动检查的具体结果以 Release 说明为准；真机后台存活、Shizuku OEM 兼容性及不同系统键盘仍需实际设备验证。
