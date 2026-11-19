package com.byd.proximitylab.algorithmconfig

import com.byd.proximitylab.proximity.ProximityAlgorithm
import com.byd.proximitylab.session.BuiltInSessionConfigs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlgorithmConfigModelsTest {

    @Test
    fun builtInBaselineAndEmaAreAlwaysAvailable() {
        assertEquals(2, BuiltInAlgorithmConfigs.ALL.size)
        assertEquals(
            listOf("builtin-baseline-v1", "builtin-ema-v1"),
            BuiltInAlgorithmConfigs.ALL.map { it.configId },
        )
        assertEquals(ProximityAlgorithm.BASELINE, BuiltInAlgorithmConfigs.BASELINE.config.algorithm)
        assertEquals(ProximityAlgorithm.EMA, BuiltInAlgorithmConfigs.EMA.config.algorithm)
        assertTrue(BuiltInAlgorithmConfigs.ALL.all { it.builtIn })
        assertEquals(BuiltInAlgorithmConfigs.BASELINE, BuiltInAlgorithmConfigs.DEFAULT)
        assertEquals(BuiltInSessionConfigs.DEFAULT.config, BuiltInAlgorithmConfigs.DEFAULT.config)
    }

    @Test
    fun builtInSnapshotMatchesFrozen05SnapshotAndHasNoSourceConfig() {
        val snapshot = BuiltInAlgorithmConfigs.EMA.toSessionSnapshot()

        assertEquals(BuiltInSessionConfigs.EMA, snapshot)
        assertNull(snapshot.sourceConfigId)
        assertEquals("builtin-ema-v1", snapshot.snapshotId)
    }

    @Test
    fun userConfigSnapshotCarriesSourceIdAndParameters() {
        val config = AlgorithmConfig(
            configId = "user-config-1",
            name = "口袋",
            config = BuiltInAlgorithmConfigs.EMA.config.copy(emaAlpha = 0.2),
            builtIn = false,
            updatedAtEpochMillis = 123L,
        )

        val snapshot = config.toSessionSnapshot(snapshotId = "user-config-1@123")

        assertEquals("user-config-1@123", snapshot.snapshotId)
        assertEquals("user-config-1@123", config.defaultSnapshotId())
        assertEquals("口袋", snapshot.name)
        assertEquals("user-config-1", snapshot.sourceConfigId)
        assertEquals(0.2, snapshot.config.emaAlpha, 0.0)
    }

    @Test
    fun snapshotDoesNotChangeWhenConfigIsEditedAfterward() {
        val original = AlgorithmConfig(
            configId = "user-config-1",
            name = "口袋",
            config = BuiltInAlgorithmConfigs.EMA.config,
            builtIn = false,
            updatedAtEpochMillis = 100L,
        )
        val snapshot = original.toSessionSnapshot()
        val edited = original.copy(
            name = "口袋改",
            config = original.config.copy(emaAlpha = 0.15, enterThresholdDbm = -55),
            updatedAtEpochMillis = 200L,
        )

        assertEquals("口袋", snapshot.name)
        assertEquals(0.5, snapshot.config.emaAlpha, 0.0)
        assertEquals(-65, snapshot.config.enterThresholdDbm)
        assertEquals("口袋改", edited.name)
        assertEquals(0.15, edited.config.emaAlpha, 0.0)
    }

    @Test
    fun builtInLookupFindsOnlyBuiltIns() {
        assertEquals(BuiltInAlgorithmConfigs.BASELINE, BuiltInAlgorithmConfigs.find("builtin-baseline-v1"))
        assertNull(BuiltInAlgorithmConfigs.find("user-config-1"))
    }
}
