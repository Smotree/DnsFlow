package ru.dnsflow.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.dnsflow.app.data.LookupStatus
import ru.dnsflow.app.data.Place
import ru.dnsflow.app.data.Product
import ru.dnsflow.app.data.Search

class SearchTest {
    private val place = Place(
        sessionId = 1, position = 5, raw = "mst301020261140050045771277", format = "QR_CODE",
        ean = "4680553029077", scannedAt = 1, extraEans = "4600000000017",
    )
    private val product = Product("4680553029077", LookupStatus.FOUND, code = "1277546", name = "Чайник для плиты Aceline TS-305 серебристый")

    @Test fun findsByWhatIsOnTheBoxOrInTheApp() {
        for (q in listOf("045771277", "771277", "1277546", "4680553029077", "4600000000017", "5", "№5", "чайник", "aceline ts")) {
            assertTrue(q, Search.matches(place, product, q))
        }
        for (q in listOf("999999", "88888", "монитор")) assertFalse(q, Search.matches(place, product, q))
        assertTrue(Search.matches(place, product, "  "))
    }
}
