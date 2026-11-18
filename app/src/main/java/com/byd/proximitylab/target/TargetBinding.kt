package com.byd.proximitylab.target

import com.byd.proximitylab.ble.BleAdvertisement
import com.byd.proximitylab.ble.normalizeBleAddress
import java.util.UUID

/**
 * 用户确认的绑定目标。V1 同时只保存一个目标。
 *
 * [targetId] 是 App 内稳定标识；[address] 只是当前观测到的广播地址。
 */
data class BoundTarget(
    val targetId: String,
    val displayName: String?,
    val address: String,
    val boundAtEpochMillis: Long,
    val verifiedIdentityToken: String?,
    val verifiedIdentityDescription: String?,
    val lastAction: TargetAction,
    val lastActionAtEpochMillis: Long,
) {
    init {
        require(targetId.isNotBlank()) { "targetId must not be blank" }
        require(normalizeBleAddress(address) == address) { "address must be normalized" }
    }
}

enum class TargetAction {
    BOUND,
    ADDRESS_CONTINUED,
    RECONFIRMED,
    REBOUND,
}

/**
 * 可复验的稳定身份证据。生产实现目前不提供任何证据；真实车辆的唯一依据留给 14 实测。
 */
data class VerifiedIdentityEvidence(
    val token: String,
    val description: String,
)

fun interface VerifiedIdentityEvidenceProvider {
    fun verifiedIdentity(advertisement: BleAdvertisement): VerifiedIdentityEvidence?
}

/**
 * 保守默认实现：名称、品牌 UUID、厂商 ID、RSSI 或未实测的载荷前缀都不构成可复验证据。
 */
object NoVerifiedIdentityEvidence : VerifiedIdentityEvidenceProvider {
    override fun verifiedIdentity(advertisement: BleAdvertisement): VerifiedIdentityEvidence? = null
}

enum class AssociationBasis {
    PERSISTED_BINDING,
    ADDRESS_MATCH,
    BOUND_ADDRESS_RECENTLY_OBSERVED,
    VERIFIED_IDENTITY_TOKEN,
    USER_BINDING,
    USER_RECONFIRMATION,
    USER_REBIND,
}

enum class IdentityStatus {
    UNBOUND,
    CONFIRMED,
    UNCERTAIN,
}

enum class IdentityUncertaintyReason {
    NO_VERIFIED_IDENTITY_EVIDENCE,
    VERIFIED_IDENTITY_MISMATCH,
    MULTIPLE_VERIFIED_IDENTITY_CANDIDATES,
    CANDIDATE_ADDRESS_UNAVAILABLE,
}

data class IdentityUncertainty(
    val reason: IdentityUncertaintyReason,
    val detail: String,
)

/**
 * 身份判定结果。只有 [IdentityStatus.CONFIRMED] 时 [confirmedAddress] 才可作为接近判断的地址依据；
 * 是否接受某次具体观测由 [AssociationDecision.acceptedObservation] 单独表示。
 */
data class TargetIdentityReport(
    val status: IdentityStatus,
    val targetId: String?,
    val bindingAddress: String?,
    val confirmedAddress: String?,
    val basis: AssociationBasis?,
    val uncertainty: IdentityUncertainty?,
    val pendingCandidateAddresses: List<String>,
    val evidenceDescription: String?,
    val evaluatedAtEpochMillis: Long,
) {
    companion object {

        fun unbound(atEpochMillis: Long): TargetIdentityReport = TargetIdentityReport(
            status = IdentityStatus.UNBOUND,
            targetId = null,
            bindingAddress = null,
            confirmedAddress = null,
            basis = null,
            uncertainty = null,
            pendingCandidateAddresses = emptyList(),
            evidenceDescription = null,
            evaluatedAtEpochMillis = atEpochMillis,
        )

        fun persisted(binding: BoundTarget, atEpochMillis: Long): TargetIdentityReport = TargetIdentityReport(
            status = IdentityStatus.CONFIRMED,
            targetId = binding.targetId,
            bindingAddress = binding.address,
            confirmedAddress = binding.address,
            basis = AssociationBasis.PERSISTED_BINDING,
            uncertainty = null,
            pendingCandidateAddresses = emptyList(),
            evidenceDescription = "读取到已保存的绑定；等待扫描观测确认当前地址。",
            evaluatedAtEpochMillis = atEpochMillis,
        )
    }
}

data class AssociationDecision(
    val identity: TargetIdentityReport,
    val acceptedObservation: Boolean,
    val continuedBinding: BoundTarget?,
)

/**
 * 一次用于接近判断的目标观测。原始字段、观测时刻、App 收到时刻和重复投递信息保持完整。
 */
data class TargetObservation(
    val targetId: String,
    val advertisement: BleAdvertisement,
    val identityBasis: AssociationBasis,
) {
    val rssi: Int
        get() = advertisement.rssi

    val observedAtEpochMillis: Long
        get() = advertisement.observedAtEpochMillis

    val receivedAtEpochMillis: Long
        get() = advertisement.receivedAtEpochMillis
}

enum class AssociationDiagnosticKind {
    SCAN_STATE,
    IDENTITY_CONFIRMED,
    IDENTITY_UNCERTAIN,
    ADDRESS_CONTINUED,
    TARGET_BOUND,
    TARGET_RECONFIRMED,
    TARGET_REBOUND,
    BINDING_CLEARED,
}

data class AssociationDiagnostic(
    val atEpochMillis: Long,
    val kind: AssociationDiagnosticKind,
    val message: String,
)

object TargetBindings {

    fun newBinding(
        advertisement: BleAdvertisement,
        address: String,
        nowEpochMillis: Long,
        evidence: VerifiedIdentityEvidence?,
        targetId: String = UUID.randomUUID().toString(),
    ): BoundTarget = BoundTarget(
        targetId = targetId,
        displayName = advertisement.deviceName,
        address = normalizeBleAddress(address) ?: error("bound address must be available"),
        boundAtEpochMillis = nowEpochMillis,
        verifiedIdentityToken = evidence?.token,
        verifiedIdentityDescription = evidence?.description,
        lastAction = TargetAction.BOUND,
        lastActionAtEpochMillis = nowEpochMillis,
    )

    /**
     * 用户确认当前候选就是原设备：保留原 [BoundTarget.targetId]。
     */
    fun reconfirmAsOriginal(
        binding: BoundTarget,
        advertisement: BleAdvertisement,
        address: String,
        nowEpochMillis: Long,
        evidence: VerifiedIdentityEvidence?,
    ): BoundTarget = binding.copy(
        displayName = advertisement.deviceName ?: binding.displayName,
        address = normalizeBleAddress(address) ?: error("reconfirmed address must be available"),
        verifiedIdentityToken = evidence?.token ?: binding.verifiedIdentityToken,
        verifiedIdentityDescription = evidence?.description ?: binding.verifiedIdentityDescription,
        lastAction = TargetAction.RECONFIRMED,
        lastActionAtEpochMillis = nowEpochMillis,
    )

    /**
     * 用户明确改绑：建立新的目标标识，旧标识不再代表当前目标。
     */
    fun rebindTo(
        advertisement: BleAdvertisement,
        address: String,
        nowEpochMillis: Long,
        evidence: VerifiedIdentityEvidence?,
    ): BoundTarget = newBinding(
        advertisement = advertisement,
        address = address,
        nowEpochMillis = nowEpochMillis,
        evidence = evidence,
    ).copy(
        lastAction = TargetAction.REBOUND,
        lastActionAtEpochMillis = nowEpochMillis,
    )
}
