package com.byd.proximitylab.algorithmconfig

import com.byd.proximitylab.proximity.ConfirmationRequirement
import com.byd.proximitylab.proximity.ProximityAlgorithm
import com.byd.proximitylab.proximity.ProximityConfig

/**
 * 参数表中可以显示字段级错误的字段。错误按字段对准表单，而不是只给出整表消息。
 */
enum class AlgorithmConfigField {
    NAME,
    ALGORITHM,
    ENTER_THRESHOLD_DBM,
    EXIT_THRESHOLD_DBM,
    CONFIRMATION_VALUE,
    SAMPLE_VALIDITY_MILLIS,
    EMA_ALPHA,
}

/**
 * 证据确认方式。与 03 的 [ConfirmationRequirement] 一一对应。
 */
enum class ConfirmationKind(val label: String) {
    CONSECUTIVE_SAMPLES("连续有效帧数"),
    DURATION("持续时长"),
}

/**
 * 算法配置表单的原始输入。数字保留为字符串，便于把解析和边界错误定位到具体字段。
 */
data class AlgorithmConfigInput(
    val name: String = "",
    val algorithm: ProximityAlgorithm = ProximityAlgorithm.BASELINE,
    val enterThresholdDbm: String = "-65",
    val exitThresholdDbm: String = "-80",
    val confirmationKind: ConfirmationKind = ConfirmationKind.CONSECUTIVE_SAMPLES,
    val confirmationValue: String = "2",
    val sampleValidityMillis: String = "5000",
    val emaAlpha: String = "0.5",
) {
    companion object {
        fun fromConfig(config: AlgorithmConfig): AlgorithmConfigInput = AlgorithmConfigInput(
            name = config.name,
            algorithm = config.config.algorithm,
            enterThresholdDbm = config.config.enterThresholdDbm.toString(),
            exitThresholdDbm = config.config.exitThresholdDbm.toString(),
            confirmationKind = when (config.config.confirmation) {
                is ConfirmationRequirement.ConsecutiveSamples ->
                    ConfirmationKind.CONSECUTIVE_SAMPLES

                is ConfirmationRequirement.Duration -> ConfirmationKind.DURATION
            },
            confirmationValue = when (val confirmation = config.config.confirmation) {
                is ConfirmationRequirement.ConsecutiveSamples ->
                    confirmation.minimumSamples.toString()

                is ConfirmationRequirement.Duration ->
                    confirmation.minimumDurationMillis.toString()
            },
            sampleValidityMillis = config.config.sampleValidityMillis.toString(),
            emaAlpha = config.config.emaAlpha.toString(),
        )
    }
}

/**
 * 校验结果。[config] 只在全部参数字段合法时存在；[name] 只在配置名非空时存在。
 * 调用方必须同时检查两者，避免用非法名称或非法参数写入存储。
 */
data class AlgorithmConfigValidation(
    val name: String?,
    val config: ProximityConfig?,
    val errors: Map<AlgorithmConfigField, String>,
) {
    val isValid: Boolean
        get() = name != null && config != null && errors.isEmpty()
}

/**
 * 算法配置表单校验。最终参数仍由 03 的 [ProximityConfig] 构造器兜底校验，
 * 这里负责把边界、解析和跨字段错误映射到具体表单字段。
 */
object AlgorithmConfigValidator {

    fun validate(input: AlgorithmConfigInput): AlgorithmConfigValidation {
        val errors = linkedMapOf<AlgorithmConfigField, String>()
        val trimmedName = input.name.trim()
        if (trimmedName.isEmpty()) {
            errors[AlgorithmConfigField.NAME] = "配置名不能为空。"
        }
        if (trimmedName.length > MAX_NAME_LENGTH) {
            errors[AlgorithmConfigField.NAME] = "配置名不能超过 $MAX_NAME_LENGTH 个字符。"
        }

        val enterThreshold = parseInteger(
            raw = input.enterThresholdDbm,
            field = AlgorithmConfigField.ENTER_THRESHOLD_DBM,
            label = "进入阈值",
            errors = errors,
        )
        val exitThreshold = parseInteger(
            raw = input.exitThresholdDbm,
            field = AlgorithmConfigField.EXIT_THRESHOLD_DBM,
            label = "离开阈值",
            errors = errors,
        )
        val enterInRange = enterThreshold != null &&
            enterThreshold in ProximityConfig.RSSI_MIN_DBM..ProximityConfig.RSSI_MAX_DBM
        val exitInRange = exitThreshold != null &&
            exitThreshold in ProximityConfig.RSSI_MIN_DBM..ProximityConfig.RSSI_MAX_DBM
        if (enterThreshold != null && !enterInRange) {
            errors[AlgorithmConfigField.ENTER_THRESHOLD_DBM] =
                "进入阈值 $enterThreshold dBm 超出 ${ProximityConfig.RSSI_MIN_DBM}.." +
                    "${ProximityConfig.RSSI_MAX_DBM} dBm 范围。"
        }
        if (exitThreshold != null && !exitInRange) {
            errors[AlgorithmConfigField.EXIT_THRESHOLD_DBM] =
                "离开阈值 $exitThreshold dBm 超出 ${ProximityConfig.RSSI_MIN_DBM}.." +
                    "${ProximityConfig.RSSI_MAX_DBM} dBm 范围。"
        }
        if (enterInRange && exitInRange && enterThreshold <= exitThreshold) {
            errors[AlgorithmConfigField.ENTER_THRESHOLD_DBM] =
                "进入阈值必须大于离开阈值（dBm 数值更大）。"
            errors[AlgorithmConfigField.EXIT_THRESHOLD_DBM] =
                "离开阈值必须小于进入阈值（dBm 数值更小）。"
        }

        val confirmation = parseConfirmation(input, errors)

        val validityMillis = input.sampleValidityMillis.trim().toLongOrNull()
        if (validityMillis == null) {
            errors[AlgorithmConfigField.SAMPLE_VALIDITY_MILLIS] =
                "样本过期时间必须是整数毫秒。"
        } else if (validityMillis <= 0L) {
            errors[AlgorithmConfigField.SAMPLE_VALIDITY_MILLIS] =
                "样本过期时间必须大于 0 ms。"
        }

        val alpha = input.emaAlpha.trim().toDoubleOrNull()
        if (alpha == null || !alpha.isFinite()) {
            errors[AlgorithmConfigField.EMA_ALPHA] = "EMA 平滑系数必须是数字。"
        } else if (alpha <= 0.0 || alpha > 1.0) {
            errors[AlgorithmConfigField.EMA_ALPHA] = "EMA 平滑系数必须落在 (0, 1] 内。"
        }

        val config = if (errors.keys.none { field ->
                field != AlgorithmConfigField.NAME && field != AlgorithmConfigField.ALGORITHM
            }
        ) {
            try {
                ProximityConfig(
                    algorithm = input.algorithm,
                    enterThresholdDbm = requireNotNull(enterThreshold),
                    exitThresholdDbm = requireNotNull(exitThreshold),
                    confirmation = requireNotNull(confirmation),
                    sampleValidityMillis = requireNotNull(validityMillis),
                    emaAlpha = requireNotNull(alpha),
                )
            } catch (exception: IllegalArgumentException) {
                errors[AlgorithmConfigField.ALGORITHM] =
                    exception.message ?: "算法参数无效。"
                null
            }
        } else {
            null
        }

        return AlgorithmConfigValidation(
            name = trimmedName.takeIf { it.isNotEmpty() && errors[AlgorithmConfigField.NAME] == null },
            config = config,
            errors = errors,
        )
    }

    private fun parseInteger(
        raw: String,
        field: AlgorithmConfigField,
        label: String,
        errors: MutableMap<AlgorithmConfigField, String>,
    ): Int? {
        val value = raw.trim().toIntOrNull()
        if (value == null) {
            errors[field] = "$label 必须是整数（dBm）。"
        }
        return value
    }

    private fun parseConfirmation(
        input: AlgorithmConfigInput,
        errors: MutableMap<AlgorithmConfigField, String>,
    ): ConfirmationRequirement? = when (input.confirmationKind) {
        ConfirmationKind.CONSECUTIVE_SAMPLES -> {
            val samples = input.confirmationValue.trim().toIntOrNull()
            when {
                samples == null -> {
                    errors[AlgorithmConfigField.CONFIRMATION_VALUE] =
                        "连续有效帧数必须是整数。"
                    null
                }

                samples < 1 -> {
                    errors[AlgorithmConfigField.CONFIRMATION_VALUE] =
                        "连续有效帧数必须大于等于 1。"
                    null
                }

                else -> ConfirmationRequirement.ConsecutiveSamples(samples)
            }
        }

        ConfirmationKind.DURATION -> {
            val durationMillis = input.confirmationValue.trim().toLongOrNull()
            when {
                durationMillis == null -> {
                    errors[AlgorithmConfigField.CONFIRMATION_VALUE] =
                        "持续时长必须是整数毫秒。"
                    null
                }

                durationMillis <= 0L -> {
                    errors[AlgorithmConfigField.CONFIRMATION_VALUE] =
                        "持续时长必须大于 0 ms。"
                    null
                }

                else -> ConfirmationRequirement.Duration(durationMillis)
            }
        }
    }

    /** 只做长度上限，避免异常长名称撑坏列表；业务没有其他命名约束。 */
    const val MAX_NAME_LENGTH: Int = 60
}
