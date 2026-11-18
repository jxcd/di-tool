package com.byd.proximitylab.probe

import androidx.room.RoomDatabase
import org.junit.Assert.assertTrue
import org.junit.Test

class BootstrapRoomProbeTest {

    @Test
    fun kspGeneratedRoomImplementationExistsOnClasspath() {
        val generatedClass = Class.forName(
            "com.byd.proximitylab.probe.BootstrapProbeDatabase_Impl",
            false,
            javaClass.classLoader,
        )

        assertTrue(RoomDatabase::class.java.isAssignableFrom(generatedClass))
    }
}
