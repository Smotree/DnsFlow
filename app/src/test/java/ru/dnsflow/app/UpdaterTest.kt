package ru.dnsflow.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.dnsflow.app.update.Updater

class UpdaterTest {
    @Test fun versionsCompareAsNumbers() {
        assertTrue(Updater.isNewer("2.1", "2.0"))
        assertTrue(Updater.isNewer("2.10", "2.9"))
        assertTrue(Updater.isNewer("3", "2.9.9"))
        assertFalse(Updater.isNewer("2.0", "2.0"))
        assertFalse(Updater.isNewer("1.9", "2.0"))
        assertFalse(Updater.isNewer("2.0.0", "2.0"))
    }
}
