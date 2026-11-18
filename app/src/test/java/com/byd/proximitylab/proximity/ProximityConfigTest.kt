package com.byd.proximitylab.proximity

import org.junit.Assert.assertThrows
import org.junit.Test

class ProximityConfigTest {

    @Test
    fun config_rejects_enter_threshold_not_stronger_than_exit() {
        assertThrows(IllegalArgumentException::class.java) {
            baselineConfig(enterThresholdDbm = -80, exitThresholdDbm = -80)
        }
        assertThrows(IllegalArgumentException::class.java) {
            baselineConfig(enterThresholdDbm = -85, exitThresholdDbm = -80)
        }
    }

    @Test
    fun config_rejects_thresholds_outside_android_rssi_range() {
        assertThrows(IllegalArgumentException::class.java) {
            baselineConfig(enterThresholdDbm = 127)
        }
        assertThrows(IllegalArgumentException::class.java) {
            baselineConfig(exitThresholdDbm = -128)
        }
    }

    @Test
    fun config_rejects_invalid_evidence_requirements() {
        assertThrows(IllegalArgumentException::class.java) {
            baselineConfig(confirmation = ConfirmationRequirement.ConsecutiveSamples(0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            baselineConfig(confirmation = ConfirmationRequirement.Duration(0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            baselineConfig(confirmation = ConfirmationRequirement.Duration(-1))
        }
    }

    @Test
    fun config_rejects_invalid_validity_and_ema_parameters() {
        assertThrows(IllegalArgumentException::class.java) {
            baselineConfig(sampleValidityMillis = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            baselineConfig(sampleValidityMillis = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            emaConfig(emaAlpha = 0.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            emaConfig(emaAlpha = -0.5)
        }
        assertThrows(IllegalArgumentException::class.java) {
            emaConfig(emaAlpha = 1.1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            emaConfig(emaAlpha = Double.NaN)
        }
    }

    @Test
    fun config_copy_revalidates_parameters() {
        val config = baselineConfig()

        assertThrows(IllegalArgumentException::class.java) {
            config.copy(enterThresholdDbm = config.exitThresholdDbm)
        }
    }

    @Test
    fun config_accepts_legal_boundary_values() {
        baselineConfig(
            enterThresholdDbm = ProximityConfig.RSSI_MAX_DBM,
            exitThresholdDbm = ProximityConfig.RSSI_MIN_DBM,
            confirmation = ConfirmationRequirement.ConsecutiveSamples(1),
            sampleValidityMillis = 1,
            emaAlpha = 1.0,
        )
        baselineConfig(confirmation = ConfirmationRequirement.Duration(1))
    }
}
