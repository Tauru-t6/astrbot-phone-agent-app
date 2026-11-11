package com.tauru.astrbotphoneagent.logic

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal data class OxygenPoint(val id: String, val time: Instant, val percent: Double)

internal object HealthSummary {
    fun oxygenByDay(points: List<OxygenPoint>, zone: ZoneId): Map<LocalDate, Map<String, Double>> =
        points.filter { it.percent.isFinite() && it.percent in 0.0..100.0 }
            .distinctBy { it.id.ifBlank { "${it.time}:${it.percent}" } }
            .groupBy { it.time.atZone(zone).toLocalDate() }
            .mapValues { (_, rows) -> mapOf("spo2_avg" to rows.map { it.percent }.average(), "spo2_min" to rows.minOf { it.percent }) }
}
