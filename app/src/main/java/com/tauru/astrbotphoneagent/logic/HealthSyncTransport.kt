package com.tauru.astrbotphoneagent.logic

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** JSON stays at the transport boundary; the sync controller handles typed summaries. */
internal class HealthSyncTransport(context: Context, config: LogicConfig) : HealthSyncRemote {
    private val client = AstrBotClient(config)
    private val deviceId = DeviceIdentity.deviceId(context)

    override suspend fun upload(snapshot: HealthUpload): String {
        val days = JSONArray()
        snapshot.days.forEach { day ->
            days.put(JSONObject().put("date", day.date).put("metrics", JSONObject(day.metrics)))
        }
        val payload = JSONObject().put("schema_version", 1).put("source", "health_connect")
            .put("device_id", deviceId).put("snapshot_id", UUID.randomUUID().toString())
            .put("captured_at", snapshot.capturedAt.toEpochMilli() / 1000.0).put("zone_id", snapshot.zone.id)
            .put("origin_packages", JSONArray().put(snapshot.originPackage))
            .put("status", snapshot.status).put("granted_types", JSONArray(snapshot.grantedTypes.sorted()))
            .put("days", days)
        return client.uploadHealthSnapshot(payload).optString("source_mode")
    }

    override suspend fun clear() { client.clearHealthSnapshot(deviceId) }
}
