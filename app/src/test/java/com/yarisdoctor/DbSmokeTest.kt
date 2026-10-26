package com.yarisdoctor

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Proves the Db / SQLite layer can be tested on the JVM: the real schema, real SQL, in memory. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DbSmokeTest {
    private fun db() = Db(ApplicationProvider.getApplicationContext(), null)

    @Test fun inMemoryDatabaseStartsEmptyAndStoresASample() {
        val db = db()
        assertEquals(null, db.lastSample())
        db.sample(1_000, 10, 200, false, 0.0)
        assertEquals(10, db.lastSample()?.soot)
    }
}
