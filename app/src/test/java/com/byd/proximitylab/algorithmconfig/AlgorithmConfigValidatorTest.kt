package com.byd.proximitylab.algorithmconfig

import com.byd.proximitylab.proximity.ConfirmationRequirement
import com.byd.proximitylab.proximity.ProximityAlgorithm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlgorithmConfigValidatorTest {

    @Test
    fun validBaselineInputProducesConfigAndTrimmedName() {
        val validation = AlgorithmConfigValidator.validate(
            validInput().copy(name = "  口袋  ", algorithm = ProximityAlgorithm.BASELINE),
        )

        assertTrue(validation.isValid)
        assertEquals("口袋", validation.name)
        val config = requireNotNull(validation.config)
        assertEquals(ProximityAlgorithm.BASELINE, config.algorithm)
        assertEquals(-60, config.enterThresholdDbm)
        assertEquals(-80, config.exitThresholdDbm)
        assertEquals(
            ConfirmationRequirement.ConsecutiveSamples(3),
            config.confirmation,
        )
        assertEquals(4_000L, config.sampleValidityMillis)
        assertEquals(0.25, config.emaAlpha, 0.0)
    }

    @Test
    fun validEmaDurationInputProducesDurationConfirmation() {
        val validation = AlgorithmConfigValidator.validate(
            validInput().copy(
                algorithm = ProximityAlgorithm.EMA,
                confirmationKind = ConfirmationKind.DURATION,
                confirmationValue = "1500",
                emaAlpha = "0.3",
            ),
        )

        assertTrue(validation.isValid)
        val config = requireNotNull(validation.config)
        assertEquals(ProximityAlgorithm.EMA, config.algorithm)
        assertEquals(ConfirmationRequirement.Duration(1_500L), config.confirmation)
    }

    @Test
    fun blankNameIsRejectedButParametersStillValidate() {
        val validation = AlgorithmConfigValidator.validate(validInput().copy(name = "   "))

        assertFalse(validation.isValid)
        assertNull(validation.name)
        assertNotNull(validation.config)
        assertEquals("配置名不能为空。", validation.errors[AlgorithmConfigField.NAME])
    }

    @Test
    fun swappedThresholdsReportBothThresholdFieldsAndRejectConfig() {
        val validation = AlgorithmConfigValidator.validate(
            validInput().copy(enterThresholdDbm = "-80", exitThresholdDbm = "-60"),
        )

        assertFalse(validation.isValid)
        assertNull(validation.config)
        assertTrue(validation.errors[AlgorithmConfigField.ENTER_THRESHOLD_DBM]!!.contains("大于"))
        assertTrue(validation.errors[AlgorithmConfigField.EXIT_THRESHOLD_DBM]!!.contains("小于"))
    }

    @Test
    fun equalThresholdsAreRejected() {
        val validation = AlgorithmConfigValidator.validate(
            validInput().copy(enterThresholdDbm = "-70", exitThresholdDbm = "-70"),
        )

        assertFalse(validation.isValid)
        assertNull(validation.config)
        assertTrue(validation.errors.containsKey(AlgorithmConfigField.ENTER_THRESHOLD_DBM))
        assertTrue(validation.errors.containsKey(AlgorithmConfigField.EXIT_THRESHOLD_DBM))
    }

    @Test
    fun thresholdsOutsideAndroidRssiRangeAreRejected() {
        val high = AlgorithmConfigValidator.validate(
            validInput().copy(enterThresholdDbm = "127", exitThresholdDbm = "-80"),
        )
        assertFalse(high.isValid)
        assertNull(high.config)
        assertTrue(high.errors[AlgorithmConfigField.ENTER_THRESHOLD_DBM]!!.contains("127"))

        val low = AlgorithmConfigValidator.validate(
            validInput().copy(enterThresholdDbm = "-60", exitThresholdDbm = "-128"),
        )
        assertFalse(low.isValid)
        assertNull(low.config)
        assertTrue(low.errors[AlgorithmConfigField.EXIT_THRESHOLD_DBM]!!.contains("-128"))
    }

    @Test
    fun nonNumericThresholdsReportTheirFields() {
        val validation = AlgorithmConfigValidator.validate(
            validInput().copy(enterThresholdDbm = "近", exitThresholdDbm = "far"),
        )

        assertFalse(validation.isValid)
        assertNull(validation.config)
        assertTrue(validation.errors[AlgorithmConfigField.ENTER_THRESHOLD_DBM]!!.contains("整数"))
        assertTrue(validation.errors[AlgorithmConfigField.EXIT_THRESHOLD_DBM]!!.contains("整数"))
    }

    @Test
    fun invalidConfirmationSamplesAreRejected() {
        for (value in listOf("0", "-1", "abc")) {
            val validation = AlgorithmConfigValidator.validate(
                validInput().copy(confirmationValue = value),
            )

            assertFalse("值 $value 不应通过", validation.isValid)
            assertNull(validation.config)
            assertTrue(validation.errors.containsKey(AlgorithmConfigField.CONFIRMATION_VALUE))
        }
    }

    @Test
    fun invalidConfirmationDurationIsRejected() {
        val validation = AlgorithmConfigValidator.validate(
            validInput().copy(
                confirmationKind = ConfirmationKind.DURATION,
                confirmationValue = "0",
            ),
        )

        assertFalse(validation.isValid)
        assertNull(validation.config)
        assertTrue(validation.errors[AlgorithmConfigField.CONFIRMATION_VALUE]!!.contains("大于 0"))
    }

    @Test
    fun invalidSampleValidityIsRejected() {
        for (value in listOf("0", "-5", "abc")) {
            val validation = AlgorithmConfigValidator.validate(
                validInput().copy(sampleValidityMillis = value),
            )

            assertFalse("值 $value 不应通过", validation.isValid)
            assertNull(validation.config)
            assertTrue(validation.errors.containsKey(AlgorithmConfigField.SAMPLE_VALIDITY_MILLIS))
        }
    }

    @Test
    fun invalidEmaAlphaIsRejected() {
        for (value in listOf("0", "-0.5", "1.1", "NaN", "abc")) {
            val validation = AlgorithmConfigValidator.validate(
                validInput().copy(emaAlpha = value),
            )

            assertFalse("值 $value 不应通过", validation.isValid)
            assertNull(validation.config)
            assertTrue(validation.errors.containsKey(AlgorithmConfigField.EMA_ALPHA))
        }
    }

    @Test
    fun legalBoundaryValuesAreAccepted() {
        val validation = AlgorithmConfigValidator.validate(
            AlgorithmConfigInput(
                name = "边界",
                algorithm = ProximityAlgorithm.EMA,
                enterThresholdDbm = "126",
                exitThresholdDbm = "-127",
                confirmationKind = ConfirmationKind.DURATION,
                confirmationValue = "1",
                sampleValidityMillis = "1",
                emaAlpha = "1.0",
            ),
        )

        assertTrue(validation.isValid)
        val config = requireNotNull(validation.config)
        assertEquals(126, config.enterThresholdDbm)
        assertEquals(-127, config.exitThresholdDbm)
        assertEquals(ConfirmationRequirement.Duration(1L), config.confirmation)
        assertEquals(1L, config.sampleValidityMillis)
        assertEquals(1.0, config.emaAlpha, 0.0)
    }

    @Test
    fun inputRoundTripsFromExistingConfig() {
        val original = BuiltInAlgorithmConfigs.EMA
        val validation = AlgorithmConfigValidator.validate(
            AlgorithmConfigInput.fromConfig(original).copy(name = "复制后的 EMA"),
        )

        assertTrue(validation.isValid)
        assertEquals(original.config, validation.config)
    }

    private fun validInput(): AlgorithmConfigInput = AlgorithmConfigInput(
        name = "配置甲",
        algorithm = ProximityAlgorithm.BASELINE,
        enterThresholdDbm = "-60",
        exitThresholdDbm = "-80",
        confirmationKind = ConfirmationKind.CONSECUTIVE_SAMPLES,
        confirmationValue = "3",
        sampleValidityMillis = "4000",
        emaAlpha = "0.25",
    )
}
