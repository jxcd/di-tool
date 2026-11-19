package com.byd.proximitylab.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionStatisticsTest {

    @Test
    fun emptySamplesHaveNoStatistics() {
        assertNull(RssiStatistics.calculate(emptyList()))
    }

    @Test
    fun oddCountUsesMiddleMedianAndPopulationDeviation() {
        val statistics = requireNotNull(RssiStatistics.calculate(listOf(-80, -60, -70)))

        assertEquals(3, statistics.sampleCount)
        assertEquals(-70.0, statistics.meanDbm, 1e-9)
        assertEquals(-70.0, statistics.medianDbm, 1e-9)
        assertEquals(Math.sqrt(200.0 / 3.0), statistics.standardDeviationDbm, 1e-9)
    }

    @Test
    fun evenCountAveragesMiddleValuesAndSingleSampleHasZeroDeviation() {
        val even = requireNotNull(RssiStatistics.calculate(listOf(-84, -80, -70, -66)))
        assertEquals(-75.0, even.meanDbm, 1e-9)
        assertEquals(-75.0, even.medianDbm, 1e-9)

        val single = requireNotNull(RssiStatistics.calculate(listOf(-71)))
        assertEquals(1, single.sampleCount)
        assertEquals(-71.0, single.meanDbm, 1e-9)
        assertEquals(-71.0, single.medianDbm, 1e-9)
        assertEquals(0.0, single.standardDeviationDbm, 1e-9)
    }
}
