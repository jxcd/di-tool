package com.byd.proximitylab.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class LabDestinationTest {

    @Test
    fun exposesExactlyFourDistinctPages() {
        val destinations = LabDestination.entries

        assertEquals(4, destinations.size)
        assertEquals(4, destinations.map(LabDestination::route).toSet().size)
        assertEquals(4, destinations.map(LabDestination::label).toSet().size)
    }

    @Test
    fun startsOnDevicePage() {
        assertEquals(LabDestination.Device, LabDestination.start)
        assertEquals("device", LabDestination.start.route)
    }
}
