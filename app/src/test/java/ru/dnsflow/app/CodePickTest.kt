package ru.dnsflow.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import ru.dnsflow.app.data.Codes
import ru.dnsflow.app.scan.ReadConfirm

/** What the camera reads at once on a real ASUS monitor box (serial, part number, maker QR, EAN, UPC). */
class CodePickTest {
    private val box = listOf(
        "W2LMTF026595,13HA" to "QR_CODE",
        "W2LMTF026595" to "CODE_128",
        "0197105914827" to "EAN_13", // UPC read as EAN-13
        "4711387914823" to "EAN_13", // the EAN
        "90LM09Q2-B01171" to "CODE_128",
    )

    @Test fun eanStepTakesTheRealEan() {
        assertEquals("4711387914823", Codes.pick(box, waitingForEan = true)?.first)
    }

    @Test fun serialNumbersAreNeverACargoPlace() {
        // Place step with only the product box in view: the EAN comes back (the screen says "this is a product"),
        // the serial / part number / maker QR never become a cargo place.
        assertEquals("4711387914823", Codes.pick(box, waitingForEan = false)?.first)
        assertNull(Codes.pick(box.filter { !Codes.isProduct(it.second) }, waitingForEan = false))
    }

    @Test fun cargoLabelWinsOutsideTheEanStep() {
        val both = box + ("mst161020261701220045718238" to "QR_CODE")
        assertEquals("mst161020261701220045718238", Codes.pick(both, waitingForEan = false)?.first)
        assertEquals("4711387914823", Codes.pick(both, waitingForEan = true)?.first)
        // Old labels: numeric barcode of the place.
        assertEquals("229096409", Codes.pick(listOf("229096409" to "CODE_128"), waitingForEan = false)?.first)
    }

    @Test fun upcOnlyBoxStillGivesItsCode() {
        assertEquals("0197105914827", Codes.pick(listOf("0197105914827" to "EAN_13", "W2LMTF026595" to "CODE_128"), true)?.first)
    }

    @Test fun upcEInsideASerialBarcodeIsIgnored() {
        // LG washer label: ML Kit "found" UPC-E 02128544 inside the S/N barcode while the EAN was out of focus.
        assertNull(Codes.pick(listOf("02128544" to "UPC_E", "608RWYU3K826" to "CODE_128"), waitingForEan = true))
        assertEquals("8806098662012", Codes.pick(listOf("02128544" to "UPC_E", "8806098662012" to "EAN_13"), true)?.first)
    }

    @Test fun singleFrameMisreadIsNotAccepted() {
        // Gorenje box 3838783150226: one frame read the right half shifted (3838783615022, check digit still valid).
        val c = ReadConfirm()
        assertFalse(c.feed(0, "3838783615022", "EAN_13"))
        assertFalse(c.feed(80, "3838783150226", "EAN_13"))
        assertFalse(c.feed(160, "3838783150226", "EAN_13"))
        assertFalse(c.feed(240, "3838783150226", "EAN_13")) // 3 real vs 1 misread: one more frame
        assertTrue(c.feed(320, "3838783150226", "EAN_13"))
    }

    @Test fun misreadNeverWinsAgainstTheRealCode() {
        val c = ReadConfirm()
        val frames = listOf("3838783615022", "3838783150226", "3838783615022", "3838783150226", "3838783615022") +
            List(6) { "3838783150226" }
        val accepted = frames.withIndex().filter { (i, raw) -> c.feed(i * 80L, raw, "EAN_13") }.map { it.value }
        assertEquals(listOf("3838783150226"), accepted)
    }

    @Test fun misreadsFromTheTruckOf28October() {
        // DEXP hood: UPC-E 17640505 "found" in the PI/SN barcodes, the EAN is 4680553324448.
        assertEquals("4680553324448", Codes.pick(listOf("17640505" to "UPC_E", "4680553324448" to "EAN_13"), true)?.first)
        // Indesit fridge 8690842648526 read once as UPC-A 636239389526; vacuum 4044996270333 once as 7044696270333.
        for ((real, wrong, fmt) in listOf(
            Triple("8690842648526", "636239389526", "UPC_A"),
            Triple("4044996270333", "7044696270333", "EAN_13"),
        )) {
            val c = ReadConfirm()
            assertFalse(c.feed(0, wrong, fmt))
            val accepted = (1..6).map { c.feed(it * 80L, real, "EAN_13") }
            assertTrue(accepted.any { it })
        }
    }

    @Test fun cargoLabelIsAcceptedAtOnce() {
        assertTrue(ReadConfirm().feed(0, "mst161020261701220045718238", "QR_CODE"))
    }
}
