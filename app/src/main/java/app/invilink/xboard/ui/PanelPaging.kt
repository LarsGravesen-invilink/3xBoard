package app.invilink.xboard.ui

import java.text.NumberFormat
import java.util.Locale

internal const val PANELS_PER_PAGE = 4

/** Pages are signed relative to the original home page (0). */
internal data class CardSlot(val page: Int, val position: Int) {
    init { require(position in 0 until PANELS_PER_PAGE) }
}

internal fun reconcileCardSlots(saved: Map<String, CardSlot>, cards: List<String>): Map<String, CardSlot> {
    require(cards.size == cards.toSet().size)
    val result = linkedMapOf<String, CardSlot>()
    val occupied = mutableSetOf<CardSlot>()
    for (card in cards) {
        val slot = saved[card] ?: continue
        if (occupied.add(slot)) result[card] = slot
    }
    for (card in cards) {
        if (card in result) continue
        var page = 0
        while (true) {
            val empty = (0 until PANELS_PER_PAGE).map { CardSlot(page, it) }.firstOrNull { it !in occupied }
            if (empty != null) {
                occupied.add(empty)
                result[card] = empty
                break
            }
            page++
        }
    }
    return result
}

internal fun moveCardSlot(saved: Map<String, CardSlot>, card: String, target: CardSlot): Map<String, CardSlot> {
    val source = requireNotNull(saved[card])
    if (source == target) return saved
    val result = saved.toMutableMap()
    if (saved.values.any { it == target }) {
        fun number(slot: CardSlot): Long = slot.page.toLong() * PANELS_PER_PAGE + slot.position
        fun slot(number: Long): CardSlot = CardSlot(Math.floorDiv(number, PANELS_PER_PAGE.toLong()).toInt(),
            Math.floorMod(number, PANELS_PER_PAGE.toLong()).toInt())
        val to = number(target)
        val occupied = saved.filterKeys { it != card }.entries.associateBy { number(it.value) }
        var free = to
        while (free in occupied) free++
        for (index in free downTo to + 1) {
            occupied[index - 1]?.let { result[it.key] = slot(index) }
        }
    }
    result[card] = target
    return result
}

internal fun previewCardSlots(saved: Map<String, CardSlot>, card: String?, target: CardSlot?): Map<String, CardSlot> {
    if (card == null || card !in saved) return saved
    return (if (target == null) saved else moveCardSlot(saved, card, target)) - card
}

internal fun panelPageCount(panelCount: Int): Int {
    require(panelCount >= 0)
    return if (panelCount == 0) 1 else (panelCount - 1) / PANELS_PER_PAGE + 1
}

internal fun formatTrafficBytes(bytes: Long?): String {
    if (bytes == null || bytes < 0) return "—"
    val locale = Locale.getDefault()
    val units = if (locale.language == "ru") {
        arrayOf("Б", "КБ", "МБ", "ГБ")
    } else {
        arrayOf("B", "KiB", "MiB", "GiB")
    }
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    val number = NumberFormat.getNumberInstance(locale).apply {
        maximumFractionDigits = if (unit == 0) 0 else 1
        minimumFractionDigits = 0
        isGroupingUsed = true
    }.format(value)
    return "$number ${units[unit]}"
}
