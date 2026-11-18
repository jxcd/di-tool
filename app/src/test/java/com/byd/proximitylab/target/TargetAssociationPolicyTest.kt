package com.byd.proximitylab.target

import com.byd.proximitylab.ble.BydCandidateClassifier
import com.byd.proximitylab.ble.ManufacturerDataRecord
import com.byd.proximitylab.testing.testAdvertisement
import com.byd.proximitylab.testing.testBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetAssociationPolicyTest {

    private val noEvidence = TargetAssociationPolicy()

    private val addressA = "AA:BB:CC:DD:EE:01"
    private val addressB = "AA:BB:CC:DD:EE:02"
    private val addressC = "AA:BB:CC:DD:EE:03"
    private val now = 1_800_000_000_000L

    @Test
    fun boundAddressObserved_keepsBindingEvenWhenAnotherCandidateIsStrongerAndSameBrand() {
        val binding = testBinding(address = addressA)
        val candidateA = bydAdvertisement(addressA, rssi = -80)
        val candidateB = bydAdvertisement(addressB, rssi = -40)

        val decision = noEvidence.evaluate(
            binding = binding,
            advertisement = candidateB,
            recentCandidates = listOf(candidateA, candidateB),
            nowEpochMillis = now,
        )

        assertEquals(IdentityStatus.CONFIRMED, decision.identity.status)
        assertEquals(addressA, decision.identity.confirmedAddress)
        assertEquals(AssociationBasis.BOUND_ADDRESS_RECENTLY_OBSERVED, decision.identity.basis)
        assertFalse(decision.acceptedObservation)
        assertNull(decision.continuedBinding)
        assertEquals(listOf(addressB), decision.identity.pendingCandidateAddresses)
    }

    @Test
    fun newAddressWithoutVerifiedEvidence_keepsBindingAndReportsUncertainty() {
        val binding = testBinding(address = addressA)
        val candidateB = bydAdvertisement(addressB, rssi = -35)

        val decision = noEvidence.evaluate(
            binding = binding,
            advertisement = candidateB,
            recentCandidates = listOf(candidateB),
            nowEpochMillis = now,
        )

        assertEquals(IdentityStatus.UNCERTAIN, decision.identity.status)
        assertEquals(binding.targetId, decision.identity.targetId)
        assertEquals(addressA, decision.identity.bindingAddress)
        assertNull(decision.identity.confirmedAddress)
        assertEquals(
            IdentityUncertaintyReason.NO_VERIFIED_IDENTITY_EVIDENCE,
            decision.identity.uncertainty?.reason,
        )
        assertEquals(listOf(addressB), decision.identity.pendingCandidateAddresses)
        assertFalse(decision.acceptedObservation)
        assertNull(decision.continuedBinding)
    }

    @Test
    fun brandUuidManufacturerNameAndRssi_areNotVerifiedEvidence() {
        val binding = testBinding(address = addressA)
        val candidateB = bydAdvertisement(addressB, rssi = -20, payload = "DEADBEEF")
        val hint = BydCandidateClassifier.classify(candidateB)

        assertTrue(hint != null)
        assertTrue(hint!!.matchedSignals.isNotEmpty())

        val decision = noEvidence.evaluate(
            binding = binding,
            advertisement = candidateB,
            recentCandidates = listOf(candidateB),
            nowEpochMillis = now,
        )

        assertEquals(IdentityStatus.UNCERTAIN, decision.identity.status)
        assertFalse(decision.acceptedObservation)
        assertNull(decision.continuedBinding)
        assertEquals(
            IdentityUncertaintyReason.NO_VERIFIED_IDENTITY_EVIDENCE,
            decision.identity.uncertainty?.reason,
        )
    }

    @Test
    fun verifiedEvidenceMatchingOnlyOneNewAddress_continuesSameTargetId() {
        val binding = testBinding(
            targetId = "stable-target",
            address = addressA,
            verifiedIdentityToken = "verified-token",
        )
        val policy = TargetAssociationPolicy(evidenceProvider = tokenProvider())
        val candidateB = bydAdvertisement(addressB, payload = "verified-token")

        val decision = policy.evaluate(
            binding = binding,
            advertisement = candidateB,
            recentCandidates = listOf(candidateB),
            nowEpochMillis = now,
        )

        assertEquals(IdentityStatus.CONFIRMED, decision.identity.status)
        assertEquals("stable-target", decision.identity.targetId)
        assertEquals(addressB, decision.identity.confirmedAddress)
        assertEquals(AssociationBasis.VERIFIED_IDENTITY_TOKEN, decision.identity.basis)
        assertTrue(decision.acceptedObservation)

        val continued = decision.continuedBinding
        assertTrue(continued != null)
        assertEquals("stable-target", continued!!.targetId)
        assertEquals(addressB, continued.address)
        assertEquals(TargetAction.ADDRESS_CONTINUED, continued.lastAction)
    }

    @Test
    fun twoCandidatesMatchingSameVerifiedEvidence_reportsConflictAndKeepsBinding() {
        val binding = testBinding(address = addressA, verifiedIdentityToken = "verified-token")
        val policy = TargetAssociationPolicy(evidenceProvider = tokenProvider())
        val candidateB = bydAdvertisement(addressB, payload = "verified-token")
        val candidateC = bydAdvertisement(addressC, payload = "verified-token")

        val decision = policy.evaluate(
            binding = binding,
            advertisement = candidateB,
            recentCandidates = listOf(candidateB, candidateC),
            nowEpochMillis = now,
        )

        assertEquals(IdentityStatus.UNCERTAIN, decision.identity.status)
        assertEquals(
            IdentityUncertaintyReason.MULTIPLE_VERIFIED_IDENTITY_CANDIDATES,
            decision.identity.uncertainty?.reason,
        )
        assertFalse(decision.acceptedObservation)
        assertNull(decision.continuedBinding)
        assertEquals(listOf(addressB, addressC), decision.identity.pendingCandidateAddresses)
    }

    @Test
    fun verifiedEvidenceDisappears_doesNotContinueAndKeepsBinding() {
        val binding = testBinding(address = addressA, verifiedIdentityToken = "verified-token")
        val candidateB = bydAdvertisement(addressB, payload = "no-evidence")

        val decision = noEvidence.evaluate(
            binding = binding,
            advertisement = candidateB,
            recentCandidates = listOf(candidateB),
            nowEpochMillis = now,
        )

        assertEquals(IdentityStatus.UNCERTAIN, decision.identity.status)
        assertEquals(
            IdentityUncertaintyReason.NO_VERIFIED_IDENTITY_EVIDENCE,
            decision.identity.uncertainty?.reason,
        )
        assertFalse(decision.acceptedObservation)
        assertNull(decision.continuedBinding)
    }

    @Test
    fun verifiedEvidenceMismatch_reportsMismatch() {
        val binding = testBinding(address = addressA, verifiedIdentityToken = "verified-token")
        val policy = TargetAssociationPolicy(evidenceProvider = tokenProvider())
        val candidateB = bydAdvertisement(addressB, payload = "other-token")

        val decision = policy.evaluate(
            binding = binding,
            advertisement = candidateB,
            recentCandidates = listOf(candidateB),
            nowEpochMillis = now,
        )

        assertEquals(IdentityStatus.UNCERTAIN, decision.identity.status)
        assertEquals(
            IdentityUncertaintyReason.VERIFIED_IDENTITY_MISMATCH,
            decision.identity.uncertainty?.reason,
        )
        assertFalse(decision.acceptedObservation)
    }

    @Test
    fun matchingVerifiedEvidenceWithoutAddress_reportsAddressUnavailable() {
        val binding = testBinding(address = addressA, verifiedIdentityToken = "verified-token")
        val policy = TargetAssociationPolicy(evidenceProvider = tokenProvider())
        val candidate = bydAdvertisement(address = null, payload = "verified-token")

        val decision = policy.evaluate(
            binding = binding,
            advertisement = candidate,
            recentCandidates = listOf(candidate),
            nowEpochMillis = now,
        )

        assertEquals(IdentityStatus.UNCERTAIN, decision.identity.status)
        assertEquals(
            IdentityUncertaintyReason.CANDIDATE_ADDRESS_UNAVAILABLE,
            decision.identity.uncertainty?.reason,
        )
        assertFalse(decision.acceptedObservation)
        assertNull(decision.continuedBinding)
    }

    @Test
    fun manualReconfirmationKeepsTargetIdAndManualRebindCreatesNewTargetId() {
        val binding = testBinding(targetId = "original-id", address = addressA)
        val candidateB = bydAdvertisement(addressB)
        val candidateC = bydAdvertisement(addressC)

        val reconfirmed = TargetBindings.reconfirmAsOriginal(
            binding = binding,
            advertisement = candidateB,
            address = addressB,
            nowEpochMillis = now,
            evidence = null,
        )
        assertEquals("original-id", reconfirmed.targetId)
        assertEquals(addressB, reconfirmed.address)
        assertEquals(TargetAction.RECONFIRMED, reconfirmed.lastAction)

        val rebound = TargetBindings.rebindTo(
            advertisement = candidateC,
            address = addressC,
            nowEpochMillis = now,
            evidence = null,
        )
        assertNotEquals("original-id", rebound.targetId)
        assertEquals(addressC, rebound.address)
        assertEquals(TargetAction.REBOUND, rebound.lastAction)
    }

    private fun bydAdvertisement(
        address: String?,
        rssi: Int = -50,
        payload: String = "DEADBEEF",
    ) = testAdvertisement(
        address = address,
        name = "BYD BLE3 candidate",
        rssi = rssi,
        serviceUuids = listOf(BydCandidateClassifier.CLASSIC_SERVICE_UUID),
        manufacturerData = listOf(
            ManufacturerDataRecord(
                companyId = BydCandidateClassifier.BYD_MANUFACTURER_ID,
                payloadHex = payload,
            ),
        ),
    )

    private fun tokenProvider(): VerifiedIdentityEvidenceProvider =
        VerifiedIdentityEvidenceProvider { advertisement ->
            advertisement.manufacturerData
                .firstOrNull { record ->
                    record.companyId == BydCandidateClassifier.BYD_MANUFACTURER_ID &&
                        record.payloadHex.isNotBlank()
                }
                ?.let {
                    VerifiedIdentityEvidence(
                        token = it.payloadHex,
                        description = "测试用可复验身份证据",
                    )
                }
        }
}
