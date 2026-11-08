package ru.dnsflow.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.dnsflow.app.data.Merge
import ru.dnsflow.app.data.Merge.stamp
import ru.dnsflow.app.data.Place
import ru.dnsflow.app.ui.separatePieces

/** A box plus items the warehouse sent apart from it under the same cargo-place label. */
class PiecesTest {
    private val box = Place(sessionId = 1, position = 1, raw = "mst161020261701220045718238", format = "QR_CODE", scannedAt = 1, updatedAt = 1)

    @Test fun cardTextForThreeKettlesApart() {
        assertNull(separatePieces(box))
        val p = box.copy(pieces = 4, extraEans = "4600000000017\n4600000000017\n4600000000017")
        assertEquals("Отдельно от коробки: 3 шт. — 4600000000017 ×3", separatePieces(p))
        val partly = box.copy(pieces = 4, extraEans = "4600000000017")
        assertEquals("Отдельно от коробки: 3 шт. — 4600000000017 (+2 без EAN)", separatePieces(partly))
        assertEquals("Отдельно от коробки: 2 шт.", separatePieces(box.copy(pieces = 3)))
    }

    @Test fun piecesAddedOnAnotherPhoneArrive() {
        val remote = box.copy(pieces = 4, extraEans = "4600000000017\n4600000000017", updatedAt = 5)
        val merged = Merge.merge(box, remote, now = 10, localName = "A")!!.place
        assertEquals(4, merged.pieces)
        assertEquals("4600000000017\n4600000000017", merged.extraEans)
    }

    @Test fun splitOnAPhoneWithAStaleCopyKeepsPieces() {
        // Phone A adds two separate pieces while out of reach; phone B, not knowing, splits the 1C check later.
        val onA = box.copy(pieces = 3, extraEans = "4600000000017").stamp(Merge.Group.PIECES, 5)
        val onB = box.copy(part = 2).stamp(Merge.Group.PART, 10)
        val atA = Merge.merge(onA, onB, now = 20, localName = "A")!!.place
        val atB = Merge.merge(onB, onA, now = 20, localName = "B")!!.place
        for (p in listOf(atA, atB)) {
            assertEquals(3, p.pieces)
            assertEquals("4600000000017", p.extraEans)
            assertEquals(2, p.part)
        }
    }

    @Test fun eanFixedElsewhereDoesNotDropPieces() {
        val onA = box.copy(pieces = 2).stamp(Merge.Group.PIECES, 5)
        val onB = box.copy(ean = "4680553317372", eanFormat = "EAN_13").stamp(Merge.Group.EAN, 9)
        val atA = Merge.merge(onA, onB, now = 20, localName = "A")!!.place
        assertEquals(2, atA.pieces)
        assertEquals("4680553317372", atA.ean)
    }

    @Test fun phoneBefore22StillMergesByUpdatedAt() {
        // An old phone sends no group times: its copy counts as edited entirely at updatedAt.
        val local = box.copy(pieces = 2).stamp(Merge.Group.PIECES, 5)
        val old = box.copy(problem = true, problemNote = "Коробка вскрыта", updatedAt = 3)
        val merged = Merge.merge(local, old, now = 20, localName = "A")?.place ?: local
        assertEquals(2, merged.pieces)
    }
}
