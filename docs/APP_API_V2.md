# Phone Control App API

This document describes the app-facing dashboard routes. The command envelope
sent to Android remains schema version 1; 0.4.0 adds structured UI observation
and action commands with explicit target-device binding.

The routes are registered below the plugin prefix
`/astrbot_plugin_phone_agent`. AstrBot's dashboard authentication protects the
routes; write routes additionally reject a different `Origin`. They do not
perform an Android health probe unless explicitly requested by a device route.

## Runtime safety checks

Optional routes recheck their feature switch for each request, including after
runtime configuration changes. Most optional routes disabled at startup are
omitted until plugin reload; the Health Connect upload and clear routes remain
registered and enforce their checks on each request. `POST /device/command` requires `enabled=true` and the App
backend, and rejects disabled screenshot, observation, location, usage and policy
actions. `args` must be a JSON object containing finite numbers. Policy commands
cannot target protected system/control packages. These checks complement the
host's Dashboard authentication and Android's local approval controls.

Notification ingestion returns `503 storage_unavailable` on persistence failure
and restores its previous in-memory state, so the same batch can be retried.
Private chat task approvals do not require the night guard to be enabled.

## Bounded UI tasks

Added in plugin 0.10.0 / App 0.4.0. `enable_ui_tasks`,
`enable_task_execute`, and `enable_observe_tool` must be enabled, with the App
backend and target package permitted by `ui_task_allowed_packages`. A model
must be available. The app never silently changes local approval settings.

`POST /device/register` includes `device_kind: "personal" | "bot"` alongside
the existing registration fields. The personal APK retains its package and
device ID; the independent Bot APK uses `com.tauru.phonecontrol.bot` and a
`bot-` device-ID prefix. They have separate data and can be installed together.
A Bot may register before any personal phone. Registration never lets a Bot
replace the personal phone used for health and care.

Only the personal app registers a notification-listener service. Its
`POST /app/notifications` includes `device_id` with the notification batch
for device-ownership checks. The Bot manifest removes this service and its
client refuses notification uploads, independent of saved forwarding settings.

Use `GET /app/state?device_id=<requesting-phone>` to retrieve device-scoped
tasks, plus the following capability metadata:

```json
{
  "ui_task": {
    "supported": true, "enabled": true, "max_steps": 8,
    "packages": [{"name":"哔哩哔哩", "package":"tv.danmaku.bili"}]
  },
  "devices": [
    {"device_id":"phone-example", "label":"我的手机", "role":"personal", "device_kind":"personal", "online":true, "app_version":"0.4.0"},
    {"device_id":"bot-phone-example", "label":"Bot 手机", "role":"bot", "device_kind":"bot", "online":true, "app_version":"0.4.0"}
  ],
  "default_device_id": "phone-example",
  "task_approvals": [], "task_runs": []
}
```

`role` is `personal`, `bot`, or `unassigned`. The personal role is pinned;
the first Bot receives the Bot role, with additional Bot devices initially
unassigned. `POST /app/device/role` accepts
`{"device_id":"...","role":"bot","label":"执行手机"}` after an explicit
user action. One Bot role is active at a time. Assign `unassigned` to release
it before enabling another. Labels are at most 60 characters. Personal devices
can be renamed while retaining their role, and Bot devices cannot become
personal. `default_device_id` may be empty when only a Bot has registered;
the app selects its own registered ID as its initial target.

`POST /app/ui-task/propose` creates a proposal without executing it:

```json
{
  "goal": "打开哔哩哔哩并搜索哆啦A梦，显示相关视频结果",
  "package": "tv.danmaku.bili",
  "allowed_texts": ["哆啦A梦"],
  "success_texts": ["哆啦A梦", "综合", "视频"],
  "max_steps": 8,
  "completion_mode": "search_results",
  "device_id": "bot-phone-example",
  "requester_device_id": "phone-example"
}
```

The response is `{success, run, approval}`. `goal` is 1–500 characters;
each text list has at most five items of 1–200 characters, without control
characters, and `success_texts` must be nonempty. `max_steps` is bounded by
the advertised server limit. `completion_mode` is `visible_text` or
`search_results`. Every success text must be visible in noneditable content;
search mode also requires a result title containing the query. Text merely
present in a search box does not establish success.

UI approvals include `goal`, `mode: "ui"`, `device_id`,
`requester_device_id`, and a `scope` with target package, allowed texts,
success texts, completion mode, step limit, device ID and device role. The app
shows all of these before approval and disables incomplete scopes. It never
offers the compact overlay's one-tap approval for UI tasks. A notification
only invites the user to open the full task sheet.

`POST /app/task/approve {"approval_id":"..."}` may return HTTP 202 with
`{success:true,run:{...},status:"running"}`. This means started, not complete.
Poll state for the final run. `POST /app/task/reject` refuses a proposal;
`POST /app/task/cancel {"run_id":"..."}` requests a stop. Already completed
device actions cannot be rolled back. The app shows only tasks it requested
or that target its own device; legacy rows without device IDs are accepted
only for non-UI tasks on servers listing at most one phone.

Runs have `summary.mode: "ui"`, `summary.ui_scope`, and
`summary.requester_device_id`. Task records contain step outcomes and stop
reasons. Raw `ui_snapshot` results are transient observations and are not
written into app task history or the timeline; `ui_action` records use fixed
action summaries instead of command output.

On the execution phone, `ui_snapshot` and `ui_action` require local
confirmation by default. Continuous execution requires the user to explicitly
enable their persistent no-confirmation switches in Privacy & Automation.
Those switches trust all corresponding commands from the configured server;
they are not limited to one task. Server-side task approval remains separate.
Without them, per-command prompts can time out or invalidate a page snapshot.

Text input additionally requires the user to manually enable the Phone Control
input method via Android's input-method settings. The executor uses it only
for the approved input, then restores the previous input method. It does not
use a public text-injection broadcast receiver. Page content is sent to the
configured task model; unsupported pages, stale observations, sensitive
controls, missing progress or exhausted step budgets stop execution.

## Health Connect summaries

Added in plugin 0.9.2 with App 0.3.27. Plugin 0.9.3 accepts one validated
origin package, and App 0.3.28 lets the user select it. Both endpoints use the configured
Dashboard JWT at `/api/plug/astrbot_plugin_phone_agent`; the phone shared Token
does not authenticate these routes. Browser cross-origin writes are rejected.

`POST /app/health/snapshot` requires the plugin and `enable_health_tools` to be
enabled, and `device_id` must match the registered personal phone. Bot devices
cannot supply the personal health snapshot. The personal App
uploads only after the user grants Health Connect reads and enables the separate
server-sync switch. Example (generate the capture time, dates and ID for each read):

```json
{
  "schema_version": 1,
  "source": "health_connect",
  "device_id": "registered-device-id",
  "snapshot_id": "unique-snapshot-id",
  "captured_at": 1794312000.0,
  "zone_id": "Asia/Shanghai",
  "origin_packages": ["com.mi.health"],
  "status": "ok",
  "granted_types": ["steps"],
  "days": [{"date": "2026-11-10", "metrics": {"steps": 120}}]
}
```

The upload limit is 64 KiB, with at most seven unique daily rows. `captured_at`
is Unix seconds, no more than 24 hours old or 300 seconds in the future.
Allowed types are `steps`, `sleep`, `heart_rate`, `oxygen_saturation`, and
`calories`. The corresponding numeric metrics are `steps`, `sleep_minutes`,
`heart_rate_avg/min/max`, `spo2_avg/min`, and `active_calories_kcal`. Unknown,
non-finite, out-of-range or ungranted metrics are rejected. Missing metrics are
omitted, never filled with zero. `origin_packages` contains exactly one valid
Android application package identifier; the payload cannot combine sources.
`com.mi.health` remains the default and is the only source verified on a real device.

`status` is `ok`, `permission_denied`, `unavailable`, `read_failed`, or `disabled`.
Non-`ok` snapshots must have empty `days` and invalidate the previous values;
an accepted status report does not mean health data was read successfully.
A successful response has `success=true`, `accepted=true`, `duplicate`,
`received_at` and `source_mode`. Repeating an identical `snapshot_id` is
idempotent and does not refresh the stored timestamp. Reusing its ID for
different content or replacing a newer snapshot is rejected.

`POST /app/health/clear` accepts `{"device_id":"registered-device-id"}` and
returns `{"success":true,"cleared":true}`. It permits deletion after the health
feature or plugin switch is disabled, but still requires authentication, the
current device and allowed origin. Deletion is idempotent for the same device.
The App stops local reads when synchronization is disabled and persists a
pending clear for retries while offline. Permission changes are reported on
the next read; offline revocation cannot immediately remove a server copy.

| HTTP | `error_code` / meaning |
| --- | --- |
| 400 | `invalid_json`, `invalid_schema`, `invalid_source`, `invalid_device`, `invalid_snapshot_id`, `invalid_capture_time`, `invalid_zone`, `invalid_permissions`, `invalid_days`, `invalid_day`, `invalid_date`, `invalid_metrics`, `invalid_metric_value` |
| 401 | Dashboard authentication rejected or expired; response format is owned by AstrBot |
| 403 | `forbidden`: plugin/health feature disabled or write-origin check failed; the host may also reject authentication |
| 404 | Wrong plugin URL or server version without this endpoint |
| 409 | `device_changed`, `snapshot_conflict`, `snapshot_older_than_current` |
| 413 | `payload_too_large` |
| 503 | `storage_unavailable` |

The server stores only the latest snapshot under plugin data, separately from
the xiaomi-sync database. Health queries and the care bridge use the same
`health_data_source`: `auto` chooses a fresh snapshot from the current phone,
otherwise SQLite; `health_connect` chooses only the snapshot; `health_db` chooses
only SQLite. Values from different sources are never added. A snapshot is fresh
for at most three hours after capture, which does not establish the recency of
every measurement. Consumers must inspect both `source` and the daily dates.

## State

`GET /app/state` returns immediately from plugin memory and disk:

```json
{
  "success": true,
  "api_version": 2,
  "server_time": 1760000000.123,
  "backend": "app",
  "features": {"reminders": true, "audit": true},
  "reminders": [{"id":"app-...","text":"喝水","due":1760000060,"source":"app","status":"pending"}],
  "recent_commands": [],
  "timeline": [{"id":"tl-...","type":"reminder","title":"提醒","body":"喝水","related_task_id":null,"timestamp":1760000000.123,"event":"reminder_due","notify":true,"notification_id":"reminder-app-...","reminder_id":"app-..."}]
}
```

`timeline` contains the latest 100 events. `notify` is true only for a
reminder due event. The app polls this list and deduplicates by
`notification_id`; the plugin does not push an app reminder directly.

## App reminders

`POST /app/reminders` accepts exactly the following useful fields:

```json
{"text":"喝水","minutes":15,"request_id":"550e8400-e29b-41d4-a716-446655440000"}
```

`minutes` is an integer from 1 through 10080. `request_id` is a UUID and is
idempotent. Repeating it with the same text and duration returns the original
reminder; changing either field returns HTTP 409. NaN, Infinity, invalid JSON,
booleans, fractional numbers and invalid parameter values are rejected. Unknown fields are ignored.

The response is `{ "success": true, "reminder": { ... } }`, where the reminder
has `id`, `text`, `due`, `source: "app"`, and `status: "pending"`.

`POST /app/reminders/cancel` accepts `{"reminder_id":"app-..."}` and returns
`status: "cancelled"`. A reminder already fired, expired, or cancelled returns
`status: "already_finished"` so cancellation is safe to retry.

App reminders are stored with an empty chat session. On due, the plugin first
atomically persists a timeline event, then removes the pending reminder. Recent
overdue reminders are recovered after a restart; reminders older than 24 hours
produce a non-notifying `reminder_expired` event. Chat-created reminders keep
their old QQ delivery and also write the timeline event.

## Direct transport

`app_direct_urls` is a comma-separated ordered list. Configured URLs are tried
before URLs announced by app registration, so an old Tailscale-only registration
cannot displace the preferred LAN URL. Each candidate gets a three-second
health probe and shares one command timeout budget. A result must match
`type=result`, schema version, command ID and action. The `output` field may be
a JSON encoded string; when it contains an object or array it is decoded before
being returned to tools. A matching `success:false` result is final and is not
replayed through another endpoint or relay. Only transport failures try the
next URL or relay, reusing the same command ID.

### Attempt windows and transport error codes

The per-URL command attempt is capped at 8 seconds for actions that answer in
milliseconds. Inherently slow reads — `screen_text`, `screenshot`,
`usage_stats`, `status`, `quick_status`, `location` — get a 35-second window so
a healthy-but-slow device still answers (issue #1: a uiautomator dump can take
longer than the old flat cap). The window never exceeds the caller's remaining
budget.

Transport failures carry an `error_code`: `timeout` when the phone accepted
the connection but did not answer inside the attempt window,
`connection_failed` when the connect or socket broke. A busy phone and a wrong
address are therefore distinguishable in tool results.

On the app side `screen_text` is bounded too: the uiautomator dump runs under
`timeout 8` with its own shell leash, the temp file is removed before each
dump so a stale one is never read, and the top activity's `dumpsys` view
hierarchy is the fallback. If both sources produce nothing the command fails
with `error_code: ui_dump_unavailable` instead of holding the connection open.
