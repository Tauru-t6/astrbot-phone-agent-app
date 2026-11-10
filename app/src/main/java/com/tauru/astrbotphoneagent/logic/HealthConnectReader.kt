package com.tauru.astrbotphoneagent.logic

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal data class HealthDay(val date: String, val metrics: Map<String, Double>)
internal class HealthReadException(val operation: String, cause: Exception) : Exception(cause)

/** Only reads the selected source; never writes to or deletes Health Connect. */
internal class HealthConnectReader(private val context: Context) : HealthSummaryReader {
    companion object {
        val permissionsByType = mapOf(
            "steps" to HealthPermission.getReadPermission(StepsRecord::class),
            "sleep" to HealthPermission.getReadPermission(SleepSessionRecord::class),
            "heart_rate" to HealthPermission.getReadPermission(HeartRateRecord::class),
            "oxygen_saturation" to HealthPermission.getReadPermission(OxygenSaturationRecord::class),
            "calories" to HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        )
        val permissions = permissionsByType.values.toSet()
    }

    override fun available(): Boolean = HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
    private fun client() = HealthConnectClient.getOrCreate(context)
    override suspend fun grantedTypes(): Set<String> {
        val granted = client().permissionController.getGrantedPermissions()
        return permissionsByType.filterValues { it in granted }.keys
    }

    private suspend fun <T> query(operation: String, block: suspend () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (denied: SecurityException) { throw denied }
    catch (error: Exception) { throw HealthReadException(operation, error) }

    override suspend fun read(types: Set<String>, zone: ZoneId, originPackage: String): List<HealthDay> {
        val now = Instant.now()
        require(HealthSource.isValid(originPackage))
        val client = client()
        val today = now.atZone(zone).toLocalDate()
        val origins = setOf(DataOrigin(originPackage))
        val rows = linkedMapOf<LocalDate, MutableMap<String, Double>>()
        for (index in 0L..6L) {
            val day = today.minusDays(index)
            val start = day.atStartOfDay(zone).toInstant()
            val end = minOf(day.plusDays(1).atStartOfDay(zone).toInstant(), now)
            if (start >= end) continue
            val range = TimeRangeFilter.between(start, end)
            val values = mutableMapOf<String, Double>()
            if ("steps" in types) {
                val result = query("步数") { client.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), range, origins)) }
                if (result.dataOrigins.any { it.packageName == originPackage }) result[StepsRecord.COUNT_TOTAL]?.let { values["steps"] = it.toDouble() }
            }
            if ("calories" in types) {
                val result = query("活动热量") { client.aggregate(AggregateRequest(setOf(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL), range, origins)) }
                if (result.dataOrigins.any { it.packageName == originPackage }) result[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.let { values["active_calories_kcal"] = it.inKilocalories }
            }
            if ("sleep" in types) {
                val result = query("睡眠") { client.aggregate(AggregateRequest(setOf(SleepSessionRecord.SLEEP_DURATION_TOTAL), range, origins)) }
                if (result.dataOrigins.any { it.packageName == originPackage }) result[SleepSessionRecord.SLEEP_DURATION_TOTAL]?.let { values["sleep_minutes"] = it.toMillis() / 60000.0 }
            }
            if ("heart_rate" in types) {
                val result = query("心率") { client.aggregate(AggregateRequest(setOf(HeartRateRecord.BPM_AVG, HeartRateRecord.BPM_MIN, HeartRateRecord.BPM_MAX), range, origins)) }
                result[HeartRateRecord.BPM_AVG]?.let { values["heart_rate_avg"] = it.toDouble() }
                result[HeartRateRecord.BPM_MIN]?.let { values["heart_rate_min"] = it.toDouble() }
                result[HeartRateRecord.BPM_MAX]?.let { values["heart_rate_max"] = it.toDouble() }
            }
            rows[day] = values
        }
        if ("oxygen_saturation" in types) {
            val range = TimeRangeFilter.between(today.minusDays(6).atStartOfDay(zone).toInstant(), now)
            val points = query("血氧") {
                readHealthPages { token ->
                    val response = client.readRecords(ReadRecordsRequest(OxygenSaturationRecord::class, range,
                        dataOriginFilter = origins, pageSize = 1000, pageToken = token))
                    response.records.map { OxygenPoint(it.metadata.id, it.time, it.percentage.value) } to response.pageToken
                }
            }
            HealthSummary.oxygenByDay(points, zone).forEach { (day, metrics) -> rows[day]?.putAll(metrics) }
        }
        return rows.filterValues { it.isNotEmpty() }.map { (day, metrics) -> HealthDay(day.toString(), metrics) }
    }
}
