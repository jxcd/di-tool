package com.byd.proximitylab.target

import com.byd.proximitylab.ble.BleAdvertisement
import com.byd.proximitylab.ble.normalizeBleAddress

/**
 * 目标关联判定。输入为原绑定、当前观测和当前扫描会话内最近出现的候选。
 *
 * 直接地址匹配优先。地址不同且原地址不在最近候选中时，只有可复验身份证据唯一匹配一个新地址才延续；
 * 其他情况保留原绑定并报告身份不确定。品牌、名称、厂商 ID、RSSI 强弱和未实测载荷前缀不参与证据计算。
 */
class TargetAssociationPolicy(
    private val evidenceProvider: VerifiedIdentityEvidenceProvider = NoVerifiedIdentityEvidence,
) {

    fun verifiedIdentity(advertisement: BleAdvertisement): VerifiedIdentityEvidence? =
        evidenceProvider.verifiedIdentity(advertisement)

    fun evaluate(
        binding: BoundTarget?,
        advertisement: BleAdvertisement,
        recentCandidates: List<BleAdvertisement>,
        nowEpochMillis: Long,
    ): AssociationDecision {
        if (binding == null) {
            return AssociationDecision(
                identity = TargetIdentityReport.unbound(nowEpochMillis),
                acceptedObservation = false,
                continuedBinding = null,
            )
        }

        val recentAddresses = recentCandidates
            .mapNotNull { normalizeBleAddress(it.address) }
            .distinct()
        val currentAddress = normalizeBleAddress(advertisement.address)
        val allOtherAddresses = (recentAddresses + listOfNotNull(currentAddress))
            .filter { it != binding.address }
            .distinct()
            .sorted()

        if (currentAddress != null && currentAddress == binding.address) {
            return AssociationDecision(
                identity = confirmedReport(
                    binding = binding,
                    basis = AssociationBasis.ADDRESS_MATCH,
                    confirmedAddress = binding.address,
                    pendingCandidateAddresses = allOtherAddresses,
                    evidenceDescription = "当前广播地址与已绑定目标一致。",
                    nowEpochMillis = nowEpochMillis,
                ),
                acceptedObservation = true,
                continuedBinding = null,
            )
        }

        if (binding.address in recentAddresses) {
            return AssociationDecision(
                identity = confirmedReport(
                    binding = binding,
                    basis = AssociationBasis.BOUND_ADDRESS_RECENTLY_OBSERVED,
                    confirmedAddress = binding.address,
                    pendingCandidateAddresses = allOtherAddresses,
                    evidenceDescription = "原绑定地址仍在最近候选中，其他候选只作为待确认列表。",
                    nowEpochMillis = nowEpochMillis,
                ),
                acceptedObservation = false,
                continuedBinding = null,
            )
        }

        val candidateEvidence = verifiedIdentity(advertisement)
        val bindingToken = binding.verifiedIdentityToken

        if (bindingToken != null && candidateEvidence?.token == bindingToken) {
            if (currentAddress == null) {
                return uncertain(
                    binding = binding,
                    reason = IdentityUncertaintyReason.CANDIDATE_ADDRESS_UNAVAILABLE,
                    detail = "候选匹配可复验身份证据，但没有可取得的广播地址，无法延续地址关联。",
                    pendingCandidateAddresses = allOtherAddresses,
                    nowEpochMillis = nowEpochMillis,
                )
            }

            val matchingAddresses = recentCandidates
                .filter { candidate ->
                    val address = normalizeBleAddress(candidate.address)
                    address != null &&
                        address != binding.address &&
                        verifiedIdentity(candidate)?.token == bindingToken
                }
                .mapNotNull { normalizeBleAddress(it.address) }
                .plus(currentAddress)
                .distinct()

            if (matchingAddresses.size == 1) {
                val continued = binding.copy(
                    address = currentAddress,
                    lastAction = TargetAction.ADDRESS_CONTINUED,
                    lastActionAtEpochMillis = nowEpochMillis,
                    verifiedIdentityDescription = candidateEvidence.description,
                )
                return AssociationDecision(
                    identity = confirmedReport(
                        binding = continued,
                        basis = AssociationBasis.VERIFIED_IDENTITY_TOKEN,
                        confirmedAddress = currentAddress,
                        pendingCandidateAddresses = allOtherAddresses.filter { it != currentAddress },
                        evidenceDescription = candidateEvidence.description,
                        nowEpochMillis = nowEpochMillis,
                    ),
                    acceptedObservation = true,
                    continuedBinding = continued,
                )
            }

            return uncertain(
                binding = binding,
                reason = IdentityUncertaintyReason.MULTIPLE_VERIFIED_IDENTITY_CANDIDATES,
                detail = "多个候选同时匹配同一可复验身份证据（${matchingAddresses.joinToString()}），无法唯一确认。",
                pendingCandidateAddresses = allOtherAddresses,
                nowEpochMillis = nowEpochMillis,
            )
        }

        val reason = when {
            bindingToken == null -> IdentityUncertaintyReason.NO_VERIFIED_IDENTITY_EVIDENCE
            candidateEvidence == null -> IdentityUncertaintyReason.NO_VERIFIED_IDENTITY_EVIDENCE
            else -> IdentityUncertaintyReason.VERIFIED_IDENTITY_MISMATCH
        }
        val detail = when (reason) {
            IdentityUncertaintyReason.NO_VERIFIED_IDENTITY_EVIDENCE ->
                "原绑定地址未出现，新地址缺少可复验且唯一的关联证据；保留原绑定。"
            IdentityUncertaintyReason.VERIFIED_IDENTITY_MISMATCH ->
                "原绑定地址未出现，候选的可复验身份证据与绑定不符；保留原绑定。"
            IdentityUncertaintyReason.MULTIPLE_VERIFIED_IDENTITY_CANDIDATES ->
                "多个候选同时匹配可复验身份证据，无法唯一确认；保留原绑定。"
            IdentityUncertaintyReason.CANDIDATE_ADDRESS_UNAVAILABLE ->
                "候选缺少可取得的广播地址，无法建立地址关联；保留原绑定。"
        }

        return uncertain(
            binding = binding,
            reason = reason,
            detail = detail,
            pendingCandidateAddresses = allOtherAddresses,
            nowEpochMillis = nowEpochMillis,
        )
    }

    private fun confirmedReport(
        binding: BoundTarget,
        basis: AssociationBasis,
        confirmedAddress: String,
        pendingCandidateAddresses: List<String>,
        evidenceDescription: String?,
        nowEpochMillis: Long,
    ): TargetIdentityReport = TargetIdentityReport(
        status = IdentityStatus.CONFIRMED,
        targetId = binding.targetId,
        bindingAddress = binding.address,
        confirmedAddress = confirmedAddress,
        basis = basis,
        uncertainty = null,
        pendingCandidateAddresses = pendingCandidateAddresses,
        evidenceDescription = evidenceDescription,
        evaluatedAtEpochMillis = nowEpochMillis,
    )

    private fun uncertain(
        binding: BoundTarget,
        reason: IdentityUncertaintyReason,
        detail: String,
        pendingCandidateAddresses: List<String>,
        nowEpochMillis: Long,
    ): AssociationDecision = AssociationDecision(
        identity = TargetIdentityReport(
            status = IdentityStatus.UNCERTAIN,
            targetId = binding.targetId,
            bindingAddress = binding.address,
            confirmedAddress = null,
            basis = null,
            uncertainty = IdentityUncertainty(reason = reason, detail = detail),
            pendingCandidateAddresses = pendingCandidateAddresses,
            evidenceDescription = null,
            evaluatedAtEpochMillis = nowEpochMillis,
        ),
        acceptedObservation = false,
        continuedBinding = null,
    )
}
