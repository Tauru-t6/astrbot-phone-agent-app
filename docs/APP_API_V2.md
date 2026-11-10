# AstrBot Phone Agent App API v2

This document describes the app-facing dashboard routes. The command envelope
sent to Android remains schema version 1 and is unchanged.

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

## Health Connect summaries

Added in plugin 0.9.2 with App 0.3.27. Plugin 0.9.3 accepts one validated
origin package, and App 0.3.28 lets the user select it. Both endpoints use the configured
Dashboard JWT at `/api/plug/astrbot_plugin_phone_agent`; the phone shared Token
does not authenticate these routes. Browser cross-origin writes are rejected.

`POST /app/health/snapshot` requires the plugin and `enable_health_tools` to be
enabled, and `device_id` must match the currently registered phone. The App
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
