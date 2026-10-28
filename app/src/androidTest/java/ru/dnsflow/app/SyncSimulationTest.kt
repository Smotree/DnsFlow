package ru.dnsflow.app

import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.dnsflow.app.data.AppDb
import ru.dnsflow.app.data.Place
import ru.dnsflow.app.data.PlaceStore
import ru.dnsflow.app.data.Session
import ru.dnsflow.app.sync.Protocol

/**
 * Simulates several phones without radios: each "phone" has its own database and they swap
 * the same JSON messages the app sends over Nearby, through the same merge code.
 */
@RunWith(AndroidJUnit4::class)
class SyncSimulationTest {
    private var clock = 1_000_000L
    private fun tick() = ++clock
    private val phones = mutableListOf<Phone>()

    inner class Phone(val name: String) {
        val db: AppDb = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, AppDb::class.java,
        ).allowMainThreadQueries().build()
        val dao = db.dao()
        val store = PlaceStore(db) { name }
        val sid: Long = runBlocking { dao.insertSession(Session(title = "t", createdAt = 0, uid = "u")) }

        init { phones += this }

        // Same rules as Repo for local edits.
        fun scan(raw: String, at: Long = tick()) = runBlocking {
            val ex = dao.findPlace(sid, raw)
            if (ex != null && !ex.deleted) return@runBlocking false
            val p = Place(id = ex?.id ?: 0, sessionId = sid, position = dao.maxPosition(sid) + 1, raw = raw,
                format = "QR_CODE", scannedAt = at, updatedAt = at)
            if (ex != null) dao.updatePlace(p) else dao.insertPlace(p)
            true
        }

        fun edit(raw: String, block: (Place) -> Place) = runBlocking {
            val p = dao.findPlace(sid, raw)!!
            dao.updatePlace(block(p).copy(updatedAt = tick()))
        }

        fun verify(raw: String) = runBlocking {
            val p = dao.findPlace(sid, raw)!!
            dao.updatePlace(p.copy(verified = true))
        }

        fun all(): List<Place> = runBlocking { dao.placesWithDeleted(sid) }
        fun visible(): List<Place> = runBlocking { dao.placesOnce(sid) }
        fun get(raw: String) = runBlocking { dao.findPlace(sid, raw)!! }

        /** Receives a message exactly as it would arrive over the radio; returns what to send back / relay. */
        fun receive(bytes: ByteArray): PlaceStore.Applied = runBlocking {
            val msg = JSONObject(String(bytes, Charsets.UTF_8))
            store.applyRemote(sid, Protocol.readPlaces(msg), now = tick(), renumber = false)
                .also { if (it.reorder) needsRenumber = true }
        }

        var needsRenumber = false

        /** What Sync does once a burst of messages is over. */
        fun settle() = runBlocking { if (needsRenumber) store.renumber(sid); needsRenumber = false }

        fun encode(places: List<Place>): List<ByteArray> =
            places.chunked(Protocol.CHUNK).map { Protocol.places("u", it, name).toString().toByteArray(Charsets.UTF_8) }
    }

    /** Full exchange on (re)connect, including sending back places marked as conflicts, until nothing changes. */
    private fun connect(a: Phone, b: Phone) {
        var pending = listOf(a to b, b to a).map { (from, to) -> Triple(from, to, from.all()) }
        var rounds = 0
        while (pending.isNotEmpty()) {
            check(++rounds < 20) { "sync does not converge" }
            pending = pending.flatMap { (from, to, places) ->
                from.encode(places).flatMap { bytes ->
                    val res = to.receive(bytes)
                    if (res.conflicts.isEmpty()) emptyList() else listOf(Triple(to, from, res.conflicts))
                }
            }
        }
        a.settle(); b.settle()
    }

    /** Live message: one phone's change sent to another. */
    private fun live(from: Phone, to: Phone, raw: String): PlaceStore.Applied =
        from.encode(listOf(from.get(raw))).map { to.receive(it) }.last().also { to.settle() }

    private fun assertSame(a: Phone, b: Phone) {
        val key = { p: Place -> listOf(p.raw, p.position, p.ean, p.problem, p.problemResolved, p.deleted, p.verified, p.scannedAt, p.part) }
        assertEquals(a.all().map(key).sortedBy { it.toString() }, b.all().map(key).sortedBy { it.toString() })
    }

    @After fun close() = phones.forEach { it.db.close() }

    @Test fun samePlaceScannedOnBothPhones_isOneRecordWithEarliestScan() {
        val a = Phone("A"); val b = Phone("B")
        a.scan("mst-1", at = 100)
        b.scan("mst-1", at = 200)
        connect(a, b)
        assertEquals(1, a.visible().size)
        assertEquals(1, b.visible().size)
        assertEquals(100, b.get("mst-1").scannedAt)
        assertEquals("A", b.get("mst-1").scannedBy)
        assertSame(a, b)
    }

    @Test fun duplicateAfterLiveSync_isRejectedOnSecondPhone() {
        val a = Phone("A"); val b = Phone("B")
        a.scan("mst-1")
        live(a, b, "mst-1")
        assertFalse("second phone must reject the duplicate", b.scan("mst-1"))
    }

    @Test fun differentEanForOnePlace_isMarkedProblemOnBothPhones() {
        val a = Phone("A"); val b = Phone("B")
        a.scan("mst-1"); b.scan("mst-1")
        a.edit("mst-1") { it.copy(ean = "4600000000017") }
        b.edit("mst-1") { it.copy(ean = "4680553267899") }
        connect(a, b)
        for (p in listOf(a, b)) {
            val x = p.get("mst-1")
            assertTrue("${p.name}: problem", x.problem)
            assertTrue("${p.name}: note has both EANs", x.problemNote.contains("4600000000017") && x.problemNote.contains("4680553267899"))
            assertEquals("4680553267899", x.ean) // the later scan is kept
        }
        assertSame(a, b)
    }

    @Test fun differentEanArrivingLive_isMarkedProblemAndSentBack() {
        val a = Phone("A"); val b = Phone("B")
        a.scan("mst-1"); live(a, b, "mst-1")
        a.edit("mst-1") { it.copy(ean = "1111111111116") }
        b.edit("mst-1") { it.copy(ean = "2222222222222") }
        val res = live(b, a, "mst-1") // A gets B's newer EAN
        assertEquals(1, res.conflicts.size)
        res.conflicts.forEach { c -> a.encode(listOf(c)).forEach { b.receive(it) } } // A sends the flagged place back
        b.settle()
        assertTrue(a.get("mst-1").problem)
        assertTrue(b.get("mst-1").problem)
        assertSame(a, b)
    }

    @Test fun sameEanOnBothPhones_isNotAProblem() {
        val a = Phone("A"); val b = Phone("B")
        a.scan("mst-1"); b.scan("mst-1")
        a.edit("mst-1") { it.copy(ean = "4680553267899") }
        b.edit("mst-1") { it.copy(ean = "4680553267899") }
        connect(a, b)
        assertFalse(a.get("mst-1").problem)
        assertFalse(b.get("mst-1").problem)
    }

    @Test fun separateScans_mergeIntoSameListAndNumbers() {
        val a = Phone("A"); val b = Phone("B")
        a.scan("p1", 10); a.scan("p3", 30); a.scan("p5", 50)
        b.scan("p2", 20); b.scan("p4", 40); b.scan("p5", 55)
        connect(a, b)
        val order = listOf("p1", "p2", "p3", "p4", "p5")
        assertEquals(order, a.visible().sortedBy { it.position }.map { it.raw })
        assertEquals(order, b.visible().sortedBy { it.position }.map { it.raw })
        assertSame(a, b)
    }

    @Test fun changesMadeWhileApart_arriveOnReconnect() {
        val a = Phone("A"); val b = Phone("B")
        a.scan("p1"); a.scan("p2"); a.scan("p3")
        connect(a, b)
        // Out of range: A finds a problem while checking in 1C, B shows another place in 1C and deletes a third.
        a.edit("p1") { it.copy(problem = true, problemNote = "мятая коробка") }
        b.verify("p2")
        b.edit("p3") { it.copy(deleted = true) }
        connect(a, b)
        assertTrue(b.get("p1").problem)
        assertEquals("мятая коробка", b.get("p1").problemNote)
        assertTrue(a.get("p2").verified)
        assertTrue(a.get("p3").deleted)
        assertEquals(2, a.visible().size)
        assertSame(a, b)
    }

    @Test fun problemResolvedOnOnePhone_winsOverOlderMarking() {
        val a = Phone("A"); val b = Phone("B")
        a.scan("p1"); connect(a, b)
        a.edit("p1") { it.copy(problem = true) }
        connect(a, b)
        b.edit("p1") { it.copy(problemResolved = true) }
        connect(a, b)
        assertTrue(a.get("p1").problemResolved)
        assertSame(a, b)
    }

    @Test fun deletedThenScannedAgain_comesBackEverywhere() {
        val a = Phone("A"); val b = Phone("B")
        a.scan("p1"); connect(a, b)
        a.edit("p1") { it.copy(deleted = true) }
        connect(a, b)
        assertTrue(b.scan("p1"))
        connect(a, b)
        assertFalse(a.get("p1").deleted)
        assertSame(a, b)
    }

    @Test fun threePhonesThroughHost_allSeeEverything() {
        val host = Phone("H"); val g1 = Phone("G1"); val g2 = Phone("G2")
        g1.scan("p1"); g2.scan("p2"); host.scan("p3")
        connect(host, g1); connect(host, g2)
        // Live scan on G1 goes to the host, the host relays what changed for it to G2.
        g1.scan("p4")
        val res = live(g1, host, "p4")
        res.changed.forEach { p -> host.encode(listOf(p)).forEach { g2.receive(it) } }
        g2.settle()
        connect(host, g1)
        for (p in listOf(host, g1, g2)) assertEquals(p.name, setOf("p1", "p2", "p3", "p4"), p.visible().map { it.raw }.toSet())
        assertSame(host, g2)
    }

    @Test fun performance_1000Places() {
        val a = Phone("A"); val b = Phone("B")
        var t = System.nanoTime()
        for (i in 0 until 1000) a.scan("mst%06d".format(i), at = 1_000L + i * 2)
        val scan1000 = (System.nanoTime() - t) / 1_000_000
        for (i in 500 until 1500) b.scan("mst%06d".format(i), at = 1_001L + i * 2) // 500 overlap

        t = System.nanoTime()
        connect(a, b)
        val fullSync = (System.nanoTime() - t) / 1_000_000
        assertEquals(1500, a.visible().size)
        assertSame(a, b)

        t = System.nanoTime()
        a.scan("mst-new")
        live(a, b, "mst-new")
        val liveOne = (System.nanoTime() - t) / 1_000_000

        t = System.nanoTime()
        val list = a.visible()
        val query = (System.nanoTime() - t) / 1_000_000

        val bytes = a.encode(a.all()).sumOf { it.size }
        val msg = "PERF: scan 1000 local=${scan1000}ms (${scan1000 / 1000.0}ms each); full sync 1000+1000 (500 same)=${fullSync}ms; " +
            "live scan with 1500 in list=${liveOne}ms; read list of ${list.size}=${query}ms; full list over radio=${bytes / 1024}KB in ${(list.size + 39) / 40} messages"
        Log.i("PERF", msg)
        println(msg)
        assertTrue("live scan must stay fast: $liveOne ms", liveOne < 500)
    }
}
