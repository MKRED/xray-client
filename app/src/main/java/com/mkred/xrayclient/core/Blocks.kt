package com.mkred.xrayclient.core

import com.mkred.xrayclient.data.AppData
import com.mkred.xrayclient.data.BlockType
import com.mkred.xrayclient.data.PriorityBlock
import com.mkred.xrayclient.data.Profile
import com.mkred.xrayclient.data.Stats

/** Какие серверы входят в блок и как его назвать. Общая логика для выбора и интерфейса. */
object Blocks {

    /** Закреплённый последним блок «Любой рабочий». */
    val ANY = PriorityBlock(id = "any", type = BlockType.SUBSCRIPTION)
    const val ANY_TITLE = "Любой рабочий"

    /** Все блоки по порядку, включая закреплённый «Любой рабочий» в конце. */
    fun withAny(d: AppData): List<PriorityBlock> = d.blocks + ANY

    fun isAny(b: PriorityBlock) = b.id == ANY.id

    /** Серверы блока (только включённые, без проверки пинга). */
    fun candidates(b: PriorityBlock, d: AppData, usable: List<Profile>): List<Profile> {
        if (isAny(b)) return usable
        return when (b.type) {
            BlockType.SUB_KEYWORDS -> usable.filter { it.subscriptionId == b.subscriptionId && matches(it, b.keywords) }
            BlockType.SUBSCRIPTION -> usable.filter { it.subscriptionId == b.subscriptionId }
            BlockType.SERVER -> usable.filter { it.id == b.serverId }
            BlockType.ALL_KEYWORDS -> usable.filter { matches(it, b.keywords) }
            BlockType.MANUAL_SERVERS -> usable.filter { it.subscriptionId == null }
            BlockType.MOST_STABLE -> mostStable(usable, b.topN)
        }
    }

    /** Пингу сервера разрешено участвовать в блоке? */
    fun passes(b: PriorityBlock, delay: Long?): Boolean =
        delay != null && delay > 0 && (b.maxPingMs == null || delay < b.maxPingMs)

    /** Почему блок сейчас пропускается целиком (условие сети), или null. */
    fun skipReason(b: PriorityBlock, mobile: Boolean): String? =
        if (b.mobileOnly && !mobile) "не мобильная сеть" else null

    fun title(b: PriorityBlock, d: AppData, all: List<Profile>): String {
        if (isAny(b)) return ANY_TITLE
        val sub = d.subscriptions.firstOrNull { it.id == b.subscriptionId }?.name ?: "подписка не выбрана"
        val words = b.keywords.joinToString(", ").ifEmpty { "слова не заданы" }
        return when (b.type) {
            BlockType.SUB_KEYWORDS -> "$sub · $words"
            BlockType.SUBSCRIPTION -> sub
            BlockType.SERVER -> all.firstOrNull { it.id == b.serverId }?.name ?: "сервер не найден"
            BlockType.ALL_KEYWORDS -> "Все подписки · $words"
            BlockType.MOST_STABLE -> "Топ-${b.topN} по доступности за 24 ч"
            BlockType.MANUAL_SERVERS -> "Добавленные по ссылке"
        }
    }

    private fun matches(p: Profile, words: List<String>) =
        words.any { it.isNotBlank() && p.name.contains(it.trim(), ignoreCase = true) }

    /** Топ-N по доступности за 24 ч (при равенстве — по среднему пингу); нужно хотя бы 3 проверки. */
    private fun mostStable(usable: List<Profile>, n: Int): List<Profile> {
        val since = System.currentTimeMillis() - 24 * 3_600_000L
        val byId = Stats.buckets.value.filter { it.hour >= since }.groupBy { it.id }
        return usable.mapNotNull { p ->
            val list = byId[p.id] ?: return@mapNotNull null
            val ok = list.sumOf { it.ok }
            val total = ok + list.sumOf { it.fail }
            if (total < 3) return@mapNotNull null
            val avg = if (ok > 0) list.sumOf { it.sumDelay } / ok else Long.MAX_VALUE
            Triple(p, ok.toDouble() / total, avg)
        }
            .sortedWith(compareByDescending<Triple<Profile, Double, Long>> { it.second }.thenBy { it.third })
            .take(n)
            .map { it.first }
    }
}
