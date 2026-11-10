package app.invilink.xboard

import app.invilink.xboard.ui.panelPageCount
import org.junit.Assert.assertEquals
import org.junit.Test

class PanelPagingTest {
    @Test
    fun dragPreviewShiftsWithoutChangingSavedPositionsAndCanBeCancelled() {
        val a = app.invilink.xboard.ui.CardSlot(0, 0)
        val b = app.invilink.xboard.ui.CardSlot(0, 1)
        val c = app.invilink.xboard.ui.CardSlot(0, 2)
        val original = linkedMapOf("a" to a, "b" to b, "c" to c)
        val preview = app.invilink.xboard.ui.previewCardSlots(original, "a", c)
        assertEquals(mapOf("b" to b, "c" to app.invilink.xboard.ui.CardSlot(0, 3)), preview)
        assertEquals(mapOf("a" to a, "b" to b, "c" to c), original)
        assertEquals(original, app.invilink.xboard.ui.previewCardSlots(original, null, null))
        assertEquals(mapOf("b" to b, "c" to c), app.invilink.xboard.ui.previewCardSlots(original, "a", null))
        assertEquals(mapOf("a" to c, "b" to b, "c" to app.invilink.xboard.ui.CardSlot(0, 3)), app.invilink.xboard.ui.moveCardSlot(original, "a", c))
    }
    @Test
    fun savedSlotsKeepLeftPagesAndNewCardsFillFromHome() {
        val saved = mapOf("left" to app.invilink.xboard.ui.CardSlot(-1, 2), "home" to app.invilink.xboard.ui.CardSlot(0, 1))
        val actual = app.invilink.xboard.ui.reconcileCardSlots(saved, listOf("left", "home", "new"))
        assertEquals(app.invilink.xboard.ui.CardSlot(-1, 2), actual["left"])
        assertEquals(app.invilink.xboard.ui.CardSlot(0, 0), actual["new"])
        val moved = app.invilink.xboard.ui.moveCardSlot(actual, "new", app.invilink.xboard.ui.CardSlot(2, 0))
        assertEquals(app.invilink.xboard.ui.CardSlot(2, 0), moved["new"])
        val swapped = app.invilink.xboard.ui.moveCardSlot(moved, "new", app.invilink.xboard.ui.CardSlot(0, 1))
        assertEquals(app.invilink.xboard.ui.CardSlot(0, 2), swapped["home"])
    }
    @Test
    fun movingCardsShiftsInterveningPositionsInBothDirections() {
        val initial = mapOf("a" to app.invilink.xboard.ui.CardSlot(0, 0), "b" to app.invilink.xboard.ui.CardSlot(0, 1),
            "c" to app.invilink.xboard.ui.CardSlot(0, 2), "d" to app.invilink.xboard.ui.CardSlot(0, 3),
            "e" to app.invilink.xboard.ui.CardSlot(1, 0))
        val moved = app.invilink.xboard.ui.moveCardSlot(initial, "a", app.invilink.xboard.ui.CardSlot(1, 0))
        assertEquals(app.invilink.xboard.ui.CardSlot(0, 1), moved["b"])
        assertEquals(app.invilink.xboard.ui.CardSlot(0, 2), moved["c"])
        assertEquals(app.invilink.xboard.ui.CardSlot(0, 3), moved["d"])
        assertEquals(app.invilink.xboard.ui.CardSlot(1, 1), moved["e"])
        val back = app.invilink.xboard.ui.moveCardSlot(moved, "a", app.invilink.xboard.ui.CardSlot(0, 1))
        assertEquals(app.invilink.xboard.ui.CardSlot(0, 1), back["a"])
        assertEquals(app.invilink.xboard.ui.CardSlot(1, 1), back["e"])
        assertEquals(app.invilink.xboard.ui.CardSlot(1, 0), back["d"])
    }

    @Test
    fun movingToEmptySlotDoesNotMoveOtherCards() {
        val original = mapOf("a" to app.invilink.xboard.ui.CardSlot(-1, 2), "b" to app.invilink.xboard.ui.CardSlot(0, 1))
        val actual = app.invilink.xboard.ui.moveCardSlot(original, "a", app.invilink.xboard.ui.CardSlot(1, 3))
        assertEquals(original["b"], actual["b"])
        assertEquals(app.invilink.xboard.ui.CardSlot(1, 3), actual["a"])
    }

    @Test
    fun emptyListKeepsOneBlock() {
        assertEquals(1, panelPageCount(0))
    }

    @Test
    fun fourEntriesFitOneBlock() {
        assertEquals(1, panelPageCount(1))
        assertEquals(1, panelPageCount(4))
    }

    @Test
    fun fifthEntryCreatesSecondBlock() {
        assertEquals(2, panelPageCount(5))
        assertEquals(2, panelPageCount(8))
    }

    @Test
    fun blocksContinueByFourWithoutOverflow() {
        assertEquals(3, panelPageCount(9))
        assertEquals(536870912, panelPageCount(Int.MAX_VALUE))
    }

    @Test
    fun counterSelectionSupportsAllAndIndividualToggles() {
        val ids = setOf(1L, 2L, 3L)
        assertEquals(setOf(2L, 3L), app.invilink.xboard.ui.toggleCounterSelection(ids, emptySet(), true, 1L))
        assertEquals(setOf(1L, 3L), app.invilink.xboard.ui.toggleCounterSelection(ids, setOf(1L), false, 3L))
        assertEquals(emptySet<Long>(), app.invilink.xboard.ui.toggleCounterSelection(ids, setOf(1L), false, 1L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeCountsAreRejected() {
        panelPageCount(-1)
    }
}
