package com.byd.proximitylab.probe

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BootstrapRoomProbeInstrumentedTest {

    @Test
    fun generatedRoomDatabaseStoresAndReadsProbeRow() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            BootstrapProbeDatabase::class.java,
        ).build()

        try {
            val dao = database.bootstrapProbeDao()
            val expected = BootstrapProbeEntity(id = 1L, note = "bootstrap")

            dao.insert(expected)

            assertEquals(listOf(expected), dao.findAll())
        } finally {
            database.close()
        }
    }
}
