package ru.dnsflow.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.dnsflow.app.recover.QrRecovery
import java.time.LocalDate
import kotlin.random.Random

class QrRecoveryTest {
    // Real label 045793862 (photo of 23.10.2026): code mst231020261042440045793862, mask 7.
    private val truth = "mst231020261042440045793862"
    private val label = ("1111111001000100101111111100000100110011100100000110111010010111001010111011011101000110111001011101101" +
        "1101000010110101011101100000101100011000100000111111110101010101011111110000000001011010000000000100101101011000111010" +
        "0000100000011001101101111000111011011000110000001100111011000011101011010111011001010110101100100011110100001000110110" +
        "0000110110110010010001010000100001010110000001001111110100000111101110101011111111011000000000101000001000110011111111" +
        "000000100101010111100000101111010110001000110111010001110111111100111011101011011100010000010101110100111111000111110110" +
        "000010000111001010110001111111010110011110110111").map { it == '1' }.toBooleanArray()

    @Test fun encoderMakesExactlyThePrintersCode() {
        assertEquals(625, label.size)
        assertTrue(QrRecovery.encode(truth, 7).contentEquals(label))
    }

    @Test fun maskIsReadFromTheFormatArea() {
        assertEquals(listOf(7), QrRecovery.likelyMasks(label))
    }

    private fun damage(cells: BooleanArray, block: Boolean, share: Double, seed: Int): BooleanArray {
        val rnd = Random(seed)
        val out = cells.copyOf()
        for (r in 0 until 25) for (c in 0 until 25) {
            val hit = if (block) r in 9..22 && c in 9..22 else rnd.nextDouble() < share
            if (hit) out[r * 25 + c] = rnd.nextBoolean()
        }
        return out
    }

    @Test fun rebuildsFromNineDigitsWhenAThirdIsDestroyed() {
        val broken = damage(label, block = true, share = 0.0, seed = 1)
        assertEquals(listOf(7), QrRecovery.likelyMasks(broken))
        val t = System.currentTimeMillis()
        val res = QrRecovery.solve(broken, "045793862", LocalDate.of(2026, 8, 1), LocalDate.of(2026, 10, 26))
        println("searched ${res.searched} in ${System.currentTimeMillis() - t} ms; best=${res.best}; margin=${res.margin}")
        assertEquals(truth, res.best!!.text)
        assertTrue(res.confident)
    }

    @Test fun rebuildsWithScatteredSmudges() {
        val broken = damage(label, block = false, share = 0.35, seed = 7)
        val res = QrRecovery.solve(broken, "045793862", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 26))
        println("scattered: best=${res.best}; margin=${res.margin}")
        assertEquals(truth, res.best!!.text)
    }

    /** Real photo with the QR smeared and torn so a scanner can't read it: photo -> grid -> rebuild. */
    @Test fun rebuildsFromDamagedPhoto() {
        val grid = ru.dnsflow.app.recover.GridSampler.sample(gray("/damaged_label.gray"))
        assertTrue("grid found", grid != null)
        val res = QrRecovery.solve(grid!!.cells, "045793862", LocalDate.of(2026, 8, 1), LocalDate.of(2026, 10, 26))
        println("photo: grid=${grid.quality} best=${res.best}; margin=${res.margin}; match=${res.match}")
        assertEquals(truth, res.best!!.text)
        assertTrue(res.confident)
    }

    private fun gray(name: String): ru.dnsflow.app.recover.GridSampler.Frame {
        // Grayscale photo: 4-byte width, 4-byte height, then one byte per pixel.
        val raw = javaClass.getResourceAsStream(name)!!.readBytes()
        val buf = java.nio.ByteBuffer.wrap(raw); val w = buf.int; val h = buf.int
        return ru.dnsflow.app.recover.GridSampler.Frame(raw.copyOfRange(8, 8 + w * h), w, h)
    }

    @Test fun gridFromCleanPhotoMatchesTheLabel() {
        val g = ru.dnsflow.app.recover.GridSampler.sample(gray("/clean_label.gray"))!!
        assertTrue((0 until 625).count { g.cells[it] == label[it] } >= 615)
    }

    /** Same physical damage seen in 8 blurry frames (each with its own sampling noise). */
    @Test fun votingOverFramesBeatsOneBlurryFrame() {
        val torn = damage(label, block = true, share = 0.0, seed = 1)
        val rnd = Random(11)
        val frames = List(8) { torn.map { if (rnd.nextDouble() < 0.14) !it else it }.toBooleanArray() }
        val votes = ru.dnsflow.app.recover.GridVotes()
        frames.forEach { votes.add(ru.dnsflow.app.recover.GridSampler.Grid(it, 0.9f)) }
        val v = votes.vote()!!
        val from = LocalDate.of(2026, 8, 1); val to = LocalDate.of(2026, 10, 26)
        val one = QrRecovery.solve(frames[0], "045793862", from, to)
        val voted = QrRecovery.solve(v.cells, "045793862", from, to, use = v.use)
        println("one frame: ${one.best?.text == truth} margin=${one.margin} match=${one.match}; " +
            "voted: ${voted.best?.text == truth} margin=${voted.margin} match=${voted.match} certain=${v.certain}")
        assertEquals(truth, voted.best!!.text)
        assertTrue(voted.confident)
        assertTrue(voted.margin > one.margin)
    }

    @Test fun nineAndTenDigitNumbers() {
        assertEquals("0045793862", QrRecovery.qrNumber("045793862"))
        assertEquals("0045793862", QrRecovery.qrNumber("0045793862"))
        assertEquals(null, QrRecovery.qrNumber("4579386"))
    }
}
