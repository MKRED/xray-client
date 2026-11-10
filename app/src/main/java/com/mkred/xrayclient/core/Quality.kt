package com.mkred.xrayclient.core

import com.mkred.xrayclient.data.AppStore
import com.mkred.xrayclient.data.PingBucket
import com.mkred.xrayclient.data.Profile
import com.mkred.xrayclient.data.SpeedPoint
import com.mkred.xrayclient.data.Stats

/**
 * Качество сервера 0–100:
 *  - скорость     50 баллов: 50 Мбит/с и выше — максимум (только замер младше 24 ч);
 *  - пинг         30 баллов: 50 мс и ниже — максимум, 300 мс и выше — ноль;
 *  - стабильность 20 баллов: доступность за 24 ч.
 * Без свежего замера скорости оценка неполная (максимум 50) и помечается «≈».
 * Недоступный сейчас сервер — 0.
 */
data class QualityInfo(
    val score: Int,
    /** Есть свежий замер скорости. */
    val complete: Boolean,
    val speedMbps: Float?,
    val speedAt: Long?,
    val speedSizeMb: Int?,
    val delay: Long?,
    /** Доступность за 24 ч, 0..1; null — проверок не было. */
    val uptime: Float?,
    val speedPts: Int,
    val pingPts: Int,
    val stabilityPts: Int,
) {
    val label: String get() = if (complete) "$score" else "≈$score"
}

object Quality {
    const val SPEED_MAX_MBPS = 50f
    const val SPEED_FRESH_MS = 24 * 3_600_000L

    fun of(
        p: Profile,
        delays: Map<String, Long> = AppStore.delays.value,
        speeds: Map<String, List<SpeedPoint>> = Stats.speeds.value,
        buckets: List<PingBucket> = Stats.buckets.value,
        now: Long = System.currentTimeMillis(),
    ): QualityInfo {
        val delay = delays[p.id]
        val last = speeds[p.id]?.lastOrNull()
        val fresh = last?.takeIf { now - it.ts < SPEED_FRESH_MS && it.mbps > 0f }

        val mine = buckets.filter { it.id == p.id && it.hour >= now - 24 * 3_600_000L }
        val ok = mine.sumOf { it.ok }
        val total = ok + mine.sumOf { it.fail }
        val uptime = if (total > 0) ok.toFloat() / total else null

        val pingPts = if (delay == null || delay <= 0) 0 else
            (((300f - delay) / 250f).coerceIn(0f, 1f) * 30).toInt()
        // Без истории: считаем стабильным, если сейчас отвечает
        val stabilityPts = ((uptime ?: if (delay != null && delay > 0) 1f else 0f) * 20).toInt()
        val speedPts = fresh?.let { ((it.mbps / SPEED_MAX_MBPS).coerceIn(0f, 1f) * 50).toInt() } ?: 0

        val unavailable = delay != null && delay <= 0
        val score = if (unavailable) 0 else speedPts + pingPts + stabilityPts
        return QualityInfo(
            score = score.coerceIn(0, 100),
            complete = fresh != null,
            speedMbps = last?.mbps?.takeIf { it > 0f },
            speedAt = last?.ts,
            speedSizeMb = last?.sizeMb,
            delay = delay,
            uptime = uptime,
            speedPts = speedPts,
            pingPts = pingPts,
            stabilityPts = stabilityPts,
        )
    }

    fun word(score: Int): String = when {
        score >= 80 -> "Отличное качество"
        score >= 50 -> "Среднее качество"
        score > 0 -> "Низкое качество"
        else -> "Недоступен"
    }
}
