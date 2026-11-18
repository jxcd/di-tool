package com.byd.proximitylab.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class BleAdvertisementMapperTest {

    @Test
    fun mapsNameRssiAddressUuidManufacturerTimestampsAndFingerprint() {
        val observedAtNanos = 10_000_000_000L
        val receivedAtNanos = 10_250_000_000L
        val receivedAtEpochMillis = 1_800_000_000_000L

        val mapped = BleAdvertisementMapper.map(
            address = " aa:bb:cc:dd:ee:01 ",
            deviceName = "  BYD test device  ",
            rssi = -63,
            serviceUuids = listOf(
                "00003008-0000-1000-8000-00805f9b34fb",
                "42594420-4155-544f-e0a9-e50e24dcca9e",
            ),
            manufacturerData = listOf(
                ManufacturerDataRecord(0x3494, "deadbeef"),
                ManufacturerDataRecord(0x1234, "0102"),
            ),
            rawRecord = byteArrayOf(0x02, 0x01, 0x06),
            observedAtNanos = observedAtNanos,
            receivedAtNanos = receivedAtNanos,
            receivedAtEpochMillis = receivedAtEpochMillis,
            deliverySequence = 42L,
        )

        assertEquals("AA:BB:CC:DD:EE:01", mapped.address)
        assertEquals("BYD test device", mapped.deviceName)
        assertEquals(-63, mapped.rssi)
        assertEquals(
            listOf(
                "00003008-0000-1000-8000-00805F9B34FB",
                "42594420-4155-544F-E0A9-E50E24DCCA9E",
            ),
            mapped.serviceUuids,
        )
        assertEquals(listOf(0x1234, 0x3494), mapped.manufacturerData.map { it.companyId })
        assertEquals("0102", mapped.manufacturerData[0].payloadHex)
        assertEquals("DEADBEEF", mapped.manufacturerData[1].payloadHex)
        assertEquals(observedAtNanos, mapped.observedAtNanos)
        assertEquals(receivedAtNanos, mapped.receivedAtNanos)
        assertEquals(receivedAtEpochMillis - 250L, mapped.observedAtEpochMillis)
        assertEquals(receivedAtEpochMillis, mapped.receivedAtEpochMillis)
        assertEquals(42L, mapped.deliverySequence)
        assertEquals(64, mapped.payloadFingerprint.length)
        assertEquals(mapped.payloadFingerprint.lowercase(Locale.ROOT), mapped.payloadFingerprint)
    }

    @Test
    fun missingRawRecordFallsBackToFieldFingerprint() {
        val mapped = BleAdvertisementMapper.map(
            address = null,
            deviceName = null,
            rssi = -80,
            serviceUuids = emptyList(),
            manufacturerData = emptyList(),
            rawRecord = null,
            observedAtNanos = 1_000L,
            receivedAtNanos = 1_000L,
            receivedAtEpochMillis = 2_000L,
            deliverySequence = 7L,
        )

        assertEquals(null, mapped.address)
        assertEquals(null, mapped.deviceName)
        assertTrue(mapped.payloadFingerprint.isNotBlank())
        assertEquals(64, mapped.payloadFingerprint.length)
        assertEquals(2_000L, mapped.observedAtEpochMillis)
    }

    @Test
    fun differentMissingRecordFieldsProduceDifferentFingerprints() {
        val first = BleAdvertisementMapper.map(
            address = "AA:BB:CC:DD:EE:01",
            deviceName = "First",
            rssi = -50,
            serviceUuids = listOf("0000180F-0000-1000-8000-00805F9B34FB"),
            manufacturerData = emptyList(),
            rawRecord = null,
            observedAtNanos = 1L,
            receivedAtNanos = 1L,
            receivedAtEpochMillis = 1L,
            deliverySequence = 1L,
        )
        val second = BleAdvertisementMapper.map(
            address = "AA:BB:CC:DD:EE:01",
            deviceName = "Second",
            rssi = -50,
            serviceUuids = listOf("0000180F-0000-1000-8000-00805F9B34FB"),
            manufacturerData = emptyList(),
            rawRecord = null,
            observedAtNanos = 1L,
            receivedAtNanos = 1L,
            receivedAtEpochMillis = 1L,
            deliverySequence = 1L,
        )

        assertNotEquals(first.payloadFingerprint, second.payloadFingerprint)
    }
}
