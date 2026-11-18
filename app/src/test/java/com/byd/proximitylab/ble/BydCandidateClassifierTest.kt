package com.byd.proximitylab.ble

import com.byd.proximitylab.testing.testAdvertisement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BydCandidateClassifierTest {

    @Test
    fun genericDeviceHasNoBydHintButIsStillARegularObservation() {
        val generic = testAdvertisement(
            address = "AA:BB:CC:DD:EE:01",
            name = "Generic sensor",
            serviceUuids = listOf("0000180F-0000-1000-8000-00805F9B34FB"),
            manufacturerData = listOf(ManufacturerDataRecord(0x1234, "0102")),
        )

        assertNull(BydCandidateClassifier.classify(generic))
    }

    @Test
    fun classicUuidManufacturerIdAndNameAreReportedAsHints() {
        val candidate = testAdvertisement(
            name = "my byd car",
            serviceUuids = listOf(BydCandidateClassifier.CLASSIC_SERVICE_UUID),
            manufacturerData = listOf(
                ManufacturerDataRecord(BydCandidateClassifier.BYD_MANUFACTURER_ID, "DEADBEEF"),
            ),
        )

        val hint = BydCandidateClassifier.classify(candidate)

        assertTrue(hint != null)
        assertEquals(
            setOf(
                BydSignal.CLASSIC_SERVICE_UUID,
                BydSignal.MANUFACTURER_ID,
                BydSignal.NAME,
            ),
            hint!!.matchedSignals,
        )
    }

    @Test
    fun shortBle3UuidIsExpandedAndMatched() {
        val candidate = testAdvertisement(
            name = null,
            serviceUuids = listOf("3008"),
            manufacturerData = emptyList(),
        )

        val hint = BydCandidateClassifier.classify(candidate)

        assertTrue(hint != null)
        assertTrue(BydSignal.BLE3_LEGACY_SERVICE_UUID in hint!!.matchedSignals)
    }
}
