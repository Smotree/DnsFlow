package ru.dnsflow.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.dnsflow.app.data.Merge
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
}
