package com.byd.proximitylab.comparison

import com.byd.proximitylab.proximity.ConfirmationRequirement
import com.byd.proximitylab.proximity.ProximityAlgorithm
import com.byd.proximitylab.proximity.ProximityConfig
import com.byd.proximitylab.proximity.ProximityEngine
import com.byd.proximitylab.replay.ReplayConfigSource
import com.byd.proximitylab.replay.ReplayOutcome
import com.byd.proximitylab.replay.ReplayVersionInfo
import com.byd.proximitylab.replay.SessionReplayEngine
import com.byd.proximitylab.replay.SessionReplayRecord
import com.byd.proximitylab.proximity.ProximityState
import com.byd.proximitylab.session.FixedSampleLocation
import com.byd.proximitylab.session.PhonePlacement
import com.byd.proximitylab.session.ReferenceMarkerKind
import com.byd.proximitylab.session.SamplingSession
import com.byd.proximitylab.session.SamplingType
import com.byd.proximitylab.session.SessionConfigSnapshot
import com.byd.proximitylab.session.SessionEvaluationLabel
import com.byd.proximitylab.session.SessionLifecycleStatus
import com.byd.proximitylab.session.SessionReferenceMarker
import com.byd.proximitylab.session.SessionTimelineEvent
import com.byd.proximitylab.session.SessionTimelineItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 10 的固定时间线评分表。
 *
 * 每条用例都把固定 RSSI 时间线交给 09 的 [SessionReplayEngine]（03 共享判定），再按人工标记和
 * 允许窗口评分；评分窗口固定为「标记 `10 s`、窗 `9–12 s`」，仅作为测试输入。
 */
class SessionScoringTest {

    private val window = ScoringWindow(earlyToleranceMillis = 1_000L, lateToleranceMillis = 2_000L)

    @Test
    fun earlyOnTimeLateAndBoundariesFollowTheScoringTable() {
        val early = scoreFor(timelineWithEventAt(8_000L), markerOffsetMillis = 10_000L)
        assertEquals(TrialOutcome.EARLY, early.outcome)
        assertEquals(0, early.extraEventCount)
        assertEquals(startEpochMillis + 8_000L, early.firstEvent?.atEpochMillis)
        assertEquals(-2_000L, early.firstEvent?.offsetFromMarkerMillis)
        assertEquals(startEpochMillis + 9_000L, early.windowStartEpochMillis)
        assertEquals(startEpochMillis + 12_000L, early.windowEndEpochMillis)
        assertEquals(9_000L, early.windowStartSessionMillis)
        assertEquals(12_000L, early.windowEndSessionMillis)
        assertEquals(10_000L, early.expectedTriggerSessionMillis)

        // 窗起点和终点都算按时。
        val atWindowStart = scoreFor(timelineWithEventAt(9_000L), markerOffsetMillis = 10_000L)
        assertEquals(TrialOutcome.ON_TIME, atWindowStart.outcome)
        assertEquals(-1_000L, atWindowStart.firstEvent?.offsetFromMarkerMillis)

        val atMarker = scoreFor(timelineWithEventAt(10_000L), markerOffsetMillis = 10_000L)
        assertEquals(TrialOutcome.ON_TIME, atMarker.outcome)

        val atWindowEnd = scoreFor(timelineWithEventAt(12_000L), markerOffsetMillis = 10_000L)
        assertEquals(TrialOutcome.ON_TIME, atWindowEnd.outcome)
        assertEquals(2_000L, atWindowEnd.firstEvent?.offsetFromMarkerMillis)

        val late = scoreFor(timelineWithEventAt(13_000L), markerOffsetMillis = 10_000L)
        assertEquals(TrialOutcome.LATE, late.outcome)
        assertEquals(3_000L, late.firstEvent?.offsetFromMarkerMillis)
        assertTrue(late.coverageCoversWindow == true)
    }

    @Test
    fun eventsInsideWindowAfterTheFirstAreCountedAsRepeatsWithoutChangingTheOutcome() {
        val onTimeWithExtra = scoreFor(
            timeline = timelineWithEventsAt(10_000L, 11_000L),
            markerOffsetMillis = 10_000L,
        )
        assertEquals(TrialOutcome.ON_TIME, onTimeWithExtra.outcome)
        assertEquals(2, onTimeWithExtra.events.size)
        assertEquals(1, onTimeWithExtra.extraEventCount)
        assertEquals(ScoredEventRole.FIRST, onTimeWithExtra.events[0].role)
        assertEquals(ScoredEventRole.EXTRA, onTimeWithExtra.events[1].role)
        assertEquals(0L, onTimeWithExtra.events[0].offsetFromMarkerMillis)
        assertEquals(1_000L, onTimeWithExtra.events[1].offsetFromMarkerMillis)
        assertEquals(TrialOutcome.ON_TIME, onTimeWithExtra.outcome)

        val earlyWithExtra = scoreFor(
            timeline = timelineWithEventsAt(8_000L, 10_000L),
            markerOffsetMillis = 10_000L,
        )
        assertEquals(TrialOutcome.EARLY, earlyWithExtra.outcome)
        assertEquals(1, earlyWithExtra.extraEventCount)
        assertTrue(earlyWithExtra.events.all { event -> event.role != ScoredEventRole.UNSCORED })
    }

    @Test
    fun zeroEventsAreMissedAndDistinguishMissingFarFromConfirmedFarWithoutTrigger() {
        val withoutFar = scoreFor(
            timeline = nearOnlyTimeline(),
            markerOffsetMillis = 10_000L,
        )
        assertEquals(TrialOutcome.MISSED, withoutFar.outcome)
        assertEquals(MissedTriggerCause.NO_VERIFIED_FAR, withoutFar.missedCause)
        assertNull(withoutFar.farConfirmedAtEpochMillis)
        assertTrue(withoutFar.skipDetail!!.contains("未出现可验证的 FAR"))
        assertEquals(ProximityState.UNKNOWN, withoutFar.finalState)

        val withFar = scoreFor(
            timeline = farOnlyTimeline(),
            markerOffsetMillis = 10_000L,
        )
        assertEquals(TrialOutcome.MISSED, withFar.outcome)
        assertEquals(MissedTriggerCause.FAR_CONFIRMED_WITHOUT_TRIGGER, withFar.missedCause)
        assertEquals(startEpochMillis + 100L, withFar.farConfirmedAtEpochMillis)
        assertTrue(withFar.skipDetail!!.contains("确认 FAR"))
        assertEquals(ProximityState.UNKNOWN, withFar.finalState)
    }

    @Test
    fun negativeSamplesCountFalseTriggersAndInterruptedSilenceIsInsufficientObservation() {
        val completedWithEvent = scoreFor(
            timeline = timelineWithEventAt(5_000L),
            markerOffsetMillis = null,
            label = SessionEvaluationLabel.NO_TRIGGER,
        )
        assertEquals(TrialOutcome.FALSE_TRIGGER, completedWithEvent.outcome)
        assertEquals(1, completedWithEvent.falseTriggerCount)
        assertEquals(0, completedWithEvent.extraEventCount)
        assertEquals(ScoredEventRole.FALSE_TRIGGER, completedWithEvent.events.single().role)
        assertNull(completedWithEvent.windowStartEpochMillis)
        assertNull(completedWithEvent.windowEndEpochMillis)

        val completedSilent = scoreFor(
            timeline = farOnlyTimeline(),
            markerOffsetMillis = null,
            label = SessionEvaluationLabel.NO_TRIGGER,
        )
        assertEquals(TrialOutcome.NO_FALSE_TRIGGER, completedSilent.outcome)
        assertEquals(0, completedSilent.falseTriggerCount)

        val interruptedSilent = scoreFor(
            timeline = farOnlyTimeline(coverageEndMillis = 11_000L),
            markerOffsetMillis = null,
            label = SessionEvaluationLabel.NO_TRIGGER,
            status = SessionLifecycleStatus.INTERRUPTED,
            coverageEndMillis = 11_000L,
        )
        assertEquals(TrialOutcome.INSUFFICIENT_OBSERVATION, interruptedSilent.outcome)
        assertFalse(interruptedSilent.includedInSummary)
        assertTrue(interruptedSilent.exclusionReason!!.contains("提前中断"))

        val interruptedWithEvent = scoreFor(
            timeline = timelineWithEventAt(5_000L, coverageEndMillis = 11_000L),
            markerOffsetMillis = null,
            label = SessionEvaluationLabel.NO_TRIGGER,
            status = SessionLifecycleStatus.INTERRUPTED,
            coverageEndMillis = 11_000L,
        )
        assertEquals(TrialOutcome.FALSE_TRIGGER, interruptedWithEvent.outcome)
        assertTrue(interruptedWithEvent.coverageIncomplete)
        assertTrue(interruptedWithEvent.skipDetail!!.contains("覆盖不全"))
    }

    @Test
    fun interruptedCoverageBeforeWindowEndIsInsufficientAndNeverReportedAsMissed() {
        val score = scoreFor(
            timeline = timelineWithEventAt(10_000L, coverageEndMillis = 11_000L),
            markerOffsetMillis = 10_000L,
            status = SessionLifecycleStatus.INTERRUPTED,
            coverageEndMillis = 11_000L,
        )

        assertEquals(TrialOutcome.INSUFFICIENT_OBSERVATION, score.outcome)
        assertEquals(1, score.events.size)
        assertFalse(score.includedInSummary)
        assertEquals(false, score.coverageCoversWindow)
        assertTrue(score.exclusionReason!!.contains("未到评分窗终点"))
        assertTrue(score.skipDetail!!.contains("不判为漏触发或成功"))
    }

    @Test
    fun unmarkedMissingAndConflictingMarkersAreSkippedWithReasons() {
        val unmarked = scoreFor(
            timeline = timelineWithEventAt(10_000L),
            markerOffsetMillis = null,
            label = SessionEvaluationLabel.UNMARKED,
        )
        assertEquals(TrialOutcome.SKIPPED, unmarked.outcome)
        assertEquals(ScoreSkipReason.UNMARKED, unmarked.skipReason)
        assertFalse(unmarked.includedInSummary)
        assertTrue(unmarked.exclusionReason!!.contains("未标记"))
        // 跳过评分仍然保留已观察事件，只是不推算提前/按时/迟到。
        assertEquals(1, unmarked.events.size)
        assertEquals(ScoredEventRole.UNSCORED, unmarked.events.single().role)

        val missingMarker = scoreFor(
            timeline = timelineWithEventAt(10_000L),
            markerOffsetMillis = 25_000L,
            label = SessionEvaluationLabel.EXPECTED_TRIGGER,
            coverageEndMillis = 20_000L,
        )
        assertEquals(TrialOutcome.SKIPPED, missingMarker.outcome)
        assertEquals(ScoreSkipReason.MISSING_EXPECTED_MARKER, missingMarker.skipReason)

        val conflicting = scoreFor(
            timeline = timelineWithEventAt(10_000L),
            markerOffsets = listOf(10_000L, 11_000L),
            label = SessionEvaluationLabel.EXPECTED_TRIGGER,
        )
        assertEquals(TrialOutcome.SKIPPED, conflicting.outcome)
        assertEquals(ScoreSkipReason.MULTIPLE_EXPECTED_MARKERS, conflicting.skipReason)
        assertEquals(1, conflicting.diagnostics.size)
        assertFalse(conflicting.includedInSummary)
    }

    @Test
    fun summaryCountsOnlyScoredTrialsAndKeepsExclusionReasons() {
        val markedSession = session(id = "marked", label = SessionEvaluationLabel.EXPECTED_TRIGGER)
        val markedScore = recordFor(
            session = markedSession,
            timeline = timelineWithEventAt(10_000L),
            snapshot = snapshot("触发配置", enterThresholdDbm = -65),
        )
        val markedTrial = trialResult(markedSession, markedScore)

        val unmarkedSession = session(id = "unmarked")
        val unmarkedRecord = recordFor(
            session = unmarkedSession,
            timeline = timelineWithEventAt(10_000L),
            snapshot = snapshot("触发配置", enterThresholdDbm = -65),
        )
        val unmarkedTrial = trialResult(unmarkedSession, unmarkedRecord, markers = emptyList())

        val summary = ComparisonSummaryBuilder.build(
            listOf(
                ScoredSessionTrials(markedSession, listOf(markedTrial)),
                ScoredSessionTrials(unmarkedSession, listOf(unmarkedTrial)),
            ),
        )
        val config = summary.forConfig("snapshot-触发配置")!!
        assertEquals(1, config.includedSessionCount)
        assertEquals(1, config.excludedSessionCount)
        assertEquals(1, config.outcomeCounts[TrialOutcome.ON_TIME])
        assertEquals("unmarked", config.exclusions.single().sessionId)
        assertEquals(TrialOutcome.SKIPPED, config.exclusions.single().outcome)
        assertTrue(config.exclusions.single().reason.contains("未标记"))
    }

    @Test
    fun scoreKeepsTheReplayConfigVersionAndReplayOutcome() {
        val session = session(id = "versioned", label = SessionEvaluationLabel.EXPECTED_TRIGGER)
        val snapshot = snapshot("版本配置").copy(
            algorithmVersion = "proximity-algorithms/0",
            triggerRuleVersion = "confirmed-far-then-near/0",
        )
        val record = recordFor(
            session = session,
            timeline = timelineWithEventAt(10_000L),
            snapshot = snapshot,
        )
        val score = SessionScoring.evaluate(
            session = session,
            markers = listOf(marker("marker-1", 10_000L, sessionId = session.id)),
            window = window,
            replay = record,
        )

        assertEquals(TrialOutcome.ON_TIME, score.outcome)
        assertEquals("proximity-algorithms/0", record.config.algorithmVersion)
        assertEquals(ProximityEngine.ALGORITHM_VERSION, record.versions.executedAlgorithmVersion)
        assertEquals(ReplayOutcome.VERSION_RECOMPUTED, record.outcome)
        assertEquals(2, record.versions.differences.size)
        assertEquals("snapshot-版本配置", record.config.snapshotId)
    }

    private fun scoreFor(
        timeline: List<SessionTimelineItem>,
        markerOffsetMillis: Long? = null,
        markerOffsets: List<Long> = emptyList(),
        label: SessionEvaluationLabel = SessionEvaluationLabel.EXPECTED_TRIGGER,
        status: SessionLifecycleStatus = SessionLifecycleStatus.COMPLETED,
        coverageEndMillis: Long = 20_000L,
    ): TrialScore {
        val session = session(
            id = "scoring-session",
            label = label,
            status = status,
            coverageEndMillis = coverageEndMillis,
        )
        val markers = buildList {
            if (markerOffsetMillis != null) {
                add(marker("marker-1", markerOffsetMillis))
            }
            markerOffsets.forEachIndexed { index, offset ->
                add(marker("marker-${index + 2}", offset))
            }
        }
        val record = recordFor(
            session = session,
            timeline = timeline,
            snapshot = snapshot("评分配置"),
        )
        return SessionScoring.evaluate(
            session = session,
            markers = markers,
            window = window,
            replay = record,
        )
    }

    private fun trialResult(
        session: SamplingSession,
        record: SessionReplayRecord,
        markers: List<SessionReferenceMarker> = listOf(
            marker("marker-1", 10_000L, sessionId = session.id),
        ),
    ): ComparisonTrialResult = ComparisonTrialResult(
        replay = record,
        target = ComparisonTarget.Config("config-1"),
        isOriginalRunConfig = false,
        isCurrentSelection = true,
        score = SessionScoring.evaluate(
            session = session,
            markers = markers,
            window = window,
            replay = record,
        ),
    )

    private fun recordFor(
        session: SamplingSession,
        timeline: List<SessionTimelineItem>,
        snapshot: SessionConfigSnapshot,
    ): SessionReplayRecord {
        val computation = SessionReplayEngine.replay(session, timeline, snapshot)
        val versions = ReplayVersionInfo(
            originalSnapshotAvailable = true,
            originalAlgorithmVersion = snapshot.algorithmVersion,
            originalTriggerRuleVersion = snapshot.triggerRuleVersion,
            snapshotAlgorithmVersion = snapshot.algorithmVersion,
            snapshotTriggerRuleVersion = snapshot.triggerRuleVersion,
            executedAlgorithmVersion = ProximityEngine.ALGORITHM_VERSION,
            executedTriggerRuleVersion = ProximityEngine.TRIGGER_RULE_VERSION,
        )
        return SessionReplayRecord(
            replayId = "replay-${session.id}",
            sessionId = session.id,
            config = snapshot,
            configSource = ReplayConfigSource.ORIGINAL_SNAPSHOT,
            outcome = if (versions.versionMismatch) {
                ReplayOutcome.VERSION_RECOMPUTED
            } else {
                ReplayOutcome.REPRODUCED
            },
            versions = versions,
            coverage = computation.coverage,
            samples = computation.samples,
            states = computation.states,
            events = computation.events,
            finalState = computation.finalState,
            createdAtEpochMillis = session.startedAtEpochMillis + 100_000L,
        )
    }

    /**
     * 一条从远端确认 `FAR` 到近端确认 `NEAR` 的固定时间线；事件时刻由两条近端样本中的第二条决定。
     */
    private fun timelineWithEventAt(
        eventAtMillis: Long,
        coverageEndMillis: Long = 20_000L,
    ): List<SessionTimelineItem> = timelineWithEventsAt(eventAtMillis, coverageEndMillis = coverageEndMillis)

    private fun timelineWithEventsAt(
        vararg eventAtMillis: Long,
        coverageEndMillis: Long = 20_000L,
    ): List<SessionTimelineItem> {
        val events = mutableListOf<Pair<Long, Int>>()
        events += 0L to -85
        events += 100L to -85
        eventAtMillis.forEach { eventAt ->
            // 每次触发前先回到 FAR，保证事件来自独立的 FAR → NEAR 转换。
            if (eventAt >= 1_000L) {
                events += eventAt - 800L to -85
                events += eventAt - 600L to -85
            }
            events += eventAt - 100L to -60
            events += eventAt to -60
        }
        val clipped = events.filter { (offset, _) -> offset <= coverageEndMillis }
        return clipped.mapIndexed { index, (offset, rssi) ->
            observation(sequence = index + 1L, offsetMillis = offset, rssi = rssi)
        }
    }

    private fun nearOnlyTimeline(): List<SessionTimelineItem> = listOf(
        observation(sequence = 1L, offsetMillis = 0L, rssi = -60),
        observation(sequence = 2L, offsetMillis = 100L, rssi = -60),
        observation(sequence = 3L, offsetMillis = 1_000L, rssi = -60),
    )

    private fun farOnlyTimeline(coverageEndMillis: Long = 20_000L): List<SessionTimelineItem> = listOf(
        observation(sequence = 1L, offsetMillis = 0L, rssi = -85),
        observation(sequence = 2L, offsetMillis = 100L, rssi = -85),
        observation(sequence = 3L, offsetMillis = 1_000L, rssi = -85),
    ).filter { item -> item.event.processedAtEpochMillis <= startEpochMillis + coverageEndMillis }

    private fun observation(
        sequence: Long,
        offsetMillis: Long,
        rssi: Int,
    ): SessionTimelineItem = SessionTimelineItem(
        sequence = sequence,
        sessionId = CURRENT_SESSION_ID,
        event = SessionTimelineEvent.Observation(
            observationId = "observation-$sequence",
            targetId = "target-1",
            rawRssiDbm = rssi,
            observedAtEpochMillis = startEpochMillis + offsetMillis,
            receivedAtEpochMillis = startEpochMillis + offsetMillis,
            scanFrameId = 1_000L + sequence,
            deliverySequence = sequence,
            address = "AA:BB:CC:DD:EE:01",
            payloadFingerprint = "fingerprint-$sequence",
            processedAtEpochMillis = startEpochMillis + offsetMillis,
        ),
    )

    private fun session(
        id: String,
        label: SessionEvaluationLabel = SessionEvaluationLabel.UNMARKED,
        status: SessionLifecycleStatus = SessionLifecycleStatus.COMPLETED,
        coverageEndMillis: Long = 20_000L,
    ): SamplingSession = SamplingSession(
        id = id,
        targetId = "target-1",
        targetDisplayName = "目标车",
        targetAddress = "AA:BB:CC:DD:EE:01",
        samplingType = SamplingType.WALKING,
        name = "评分固定时间线",
        location = FixedSampleLocation.OTHER,
        estimatedDistanceMeters = null,
        phonePlacement = PhonePlacement.HANDHELD,
        status = status,
        startedAtEpochMillis = startEpochMillis,
        endedAtEpochMillis = if (status == SessionLifecycleStatus.COMPLETED) {
            startEpochMillis + coverageEndMillis
        } else {
            null
        },
        lastRecordedAtEpochMillis = startEpochMillis + coverageEndMillis,
        interruptedDetectedAtEpochMillis = if (status == SessionLifecycleStatus.INTERRUPTED) {
            startEpochMillis + coverageEndMillis + 1L
        } else {
            null
        },
        evaluationLabel = label,
    )

    private fun marker(
        id: String,
        offsetMillis: Long,
        sessionId: String = CURRENT_SESSION_ID,
        kind: ReferenceMarkerKind = ReferenceMarkerKind.EXPECTED_TRIGGER,
    ): SessionReferenceMarker = SessionReferenceMarker(
        markerId = id,
        sessionId = sessionId,
        kind = kind,
        atEpochMillis = startEpochMillis + offsetMillis,
    )

    private fun snapshot(
        name: String,
        enterThresholdDbm: Int = -65,
    ): SessionConfigSnapshot = SessionConfigSnapshot(
        snapshotId = "snapshot-$name",
        name = name,
        sourceConfigId = null,
        config = ProximityConfig(
            algorithm = ProximityAlgorithm.BASELINE,
            enterThresholdDbm = enterThresholdDbm,
            exitThresholdDbm = -80,
            confirmation = ConfirmationRequirement.ConsecutiveSamples(minimumSamples = 2),
            sampleValidityMillis = 5_000L,
            emaAlpha = 0.5,
        ),
    )

    private companion object {
        const val startEpochMillis: Long = 1_800_000_000_000L
        const val CURRENT_SESSION_ID: String = "scoring-session"
    }
}
