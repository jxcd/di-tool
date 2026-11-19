package com.byd.proximitylab.comparison

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.byd.proximitylab.algorithmconfig.AlgorithmConfig
import com.byd.proximitylab.algorithmconfig.AlgorithmConfigStore
import com.byd.proximitylab.algorithmconfig.data.RoomAlgorithmConfigStore
import com.byd.proximitylab.data.ProximityLabDatabase
import com.byd.proximitylab.proximity.ConfirmationRequirement
import com.byd.proximitylab.proximity.ProximityAlgorithm
import com.byd.proximitylab.proximity.ProximityConfig
import com.byd.proximitylab.proximity.ProximityEngine
import com.byd.proximitylab.replay.ReplayConfigSource
import com.byd.proximitylab.replay.ReplayOutcome
import com.byd.proximitylab.replay.SessionReplayRecord
import com.byd.proximitylab.replay.SessionReplayResult
import com.byd.proximitylab.replay.SessionReplayService
import com.byd.proximitylab.replay.data.RoomSessionReplayStore
import com.byd.proximitylab.session.FixedSampleLocation
import com.byd.proximitylab.session.PhonePlacement
import com.byd.proximitylab.session.ReferenceMarkerKind
import com.byd.proximitylab.session.SamplingSession
import com.byd.proximitylab.session.SamplingType
import com.byd.proximitylab.session.SessionConfigSnapshot
import com.byd.proximitylab.session.SessionEvaluationLabel
import com.byd.proximitylab.session.SessionLifecycleStatus
import com.byd.proximitylab.session.SessionReferenceMarker
import com.byd.proximitylab.session.SessionStore
import com.byd.proximitylab.session.SessionTimelineEvent
import com.byd.proximitylab.session.SessionTimelineItem
import com.byd.proximitylab.session.data.RoomSessionStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * 10 的真实存储与 09 回放验收：同一 Session 由两套配置分别回放，评分窗口、版本和汇总都可追溯。
 *
 * 只替代时间输入；[RoomSessionStore]、[RoomAlgorithmConfigStore]、[RoomSessionReplayStore]、
 * [SessionReplayService] 与 [SessionComparisonService] 都是生产实现。
 */
@RunWith(AndroidJUnit4::class)
class SessionComparisonRoomTest {

    private lateinit var context: Context
    private lateinit var databaseName: String
    private var now = 1_800_000_000_000L
    private val harnesses = mutableListOf<Harness>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "session-comparison-${UUID.randomUUID()}.db"
    }

    @After
    fun tearDown() {
        harnesses.forEach { harness -> harness.close() }
        context.deleteDatabase(databaseName)
    }

    @Test
    fun twoConfigsOnTheSameSessionKeepWindowsVersionsAndReplayRecords() {
        val harness = harness()
        val session = seedSession(
            harness = harness,
            id = "comparison-marked",
            timeline = timelineWithEventAt("comparison-marked", 10_000L),
            coverageEndMillis = 20_000L,
            snapshot = null,
        )
        runBlocking {
            harness.sessionStore.insertReferenceMarker(
                marker(sessionId = session.id, offsetMillis = 10_000L),
            )
        }
        val triggering = runBlocking { harness.createConfig("触发配置", enterThresholdDbm = -65) }
        val notTriggering = runBlocking { harness.createConfig("不触发配置", enterThresholdDbm = -50) }
        runBlocking { harness.configStore.selectConfig(triggering.configId) }

        val result = runBlocking {
            harness.comparisonService.compare(
                sessionId = session.id,
                targets = listOf(
                    ComparisonTarget.Config(triggering.configId),
                    ComparisonTarget.Config(notTriggering.configId),
                ),
                window = ScoringWindow(1_000L, 2_000L),
            )
        } as SessionComparisonResult.Completed
        val comparison = result.comparison

        assertEquals(2, comparison.trials.size)
        assertTrue(comparison.failures.isEmpty())

        val onTime = comparison.trials[0]
        assertEquals("触发配置", onTime.config.name)
        assertEquals(TrialOutcome.ON_TIME, onTime.score.outcome)
        assertEquals(1, onTime.replay.events.size)
        assertEquals(10_000L, onTime.replay.events.single().atSessionMillis)
        assertEquals(session.startedAtEpochMillis + 9_000L, onTime.score.windowStartEpochMillis)
        assertEquals(session.startedAtEpochMillis + 12_000L, onTime.score.windowEndEpochMillis)
        assertEquals(9_000L, onTime.score.windowStartSessionMillis)
        assertEquals(12_000L, onTime.score.windowEndSessionMillis)
        assertEquals(10_000L, onTime.score.expectedTriggerSessionMillis)
        assertEquals(0L, onTime.score.firstEvent?.offsetFromMarkerMillis)
        assertEquals(ReplayConfigSource.NAMED_SNAPSHOT, onTime.configSource)
        assertEquals(ReplayOutcome.NEW_CALCULATION, onTime.outcome)
        assertEquals(ProximityEngine.ALGORITHM_VERSION, onTime.versions.executedAlgorithmVersion)
        assertEquals(ProximityEngine.TRIGGER_RULE_VERSION, onTime.versions.executedTriggerRuleVersion)
        assertEquals(triggering.defaultSnapshotId(), onTime.config.snapshotId)
        assertTrue(onTime.isCurrentSelection)
        assertFalse(onTime.isOriginalRunConfig)

        val missed = comparison.trials[1]
        assertEquals("不触发配置", missed.config.name)
        assertEquals(TrialOutcome.MISSED, missed.score.outcome)
        assertEquals(MissedTriggerCause.FAR_CONFIRMED_WITHOUT_TRIGGER, missed.score.missedCause)
        assertTrue(missed.score.coverageCoversWindow == true)
        assertTrue(missed.replay.events.isEmpty())
        assertFalse(missed.isCurrentSelection)

        val summary = comparison.summary
        val triggerSummary = summary.forConfig(triggering.defaultSnapshotId())!!
        assertEquals(1, triggerSummary.includedSessionCount)
        assertEquals(0, triggerSummary.excludedSessionCount)
        assertEquals(1, triggerSummary.outcomeCounts[TrialOutcome.ON_TIME])
        val missedSummary = summary.forConfig(notTriggering.defaultSnapshotId())!!
        assertEquals(1, missedSummary.includedSessionCount)
        assertEquals(1, missedSummary.outcomeCounts[TrialOutcome.MISSED])

        // 09 回放记录已按配置分别持久化，评分结果可追溯到 replayId。
        val replays = runBlocking { harness.replayService.loadReplays(session.id) }
        assertEquals(2, replays.size)
        assertEquals(
            setOf(onTime.replay.replayId, missed.replay.replayId),
            replays.map(SessionReplayRecord::replayId).toSet(),
        )
        assertEquals(
            onTime.replay,
            runBlocking { harness.replayService.loadReplay(onTime.replay.replayId) },
        )

        // 共享图表同时包含原始曲线和两套配置的滤波曲线、事件与评分窗标记。
        assertEquals(3, comparison.chart.series.size)
        assertEquals("原始 RSSI", comparison.chart.series[0].label)
        assertTrue(comparison.chart.series[1].label.contains("触发配置"))
        assertTrue(comparison.chart.series[2].label.contains("不触发配置"))
        assertEquals(2, comparison.chart.markers.count { marker -> marker.id.startsWith("scoring-window") })
        assertTrue(comparison.chart.markers.any { marker -> marker.label.contains("CONFIRMED_NEAR_AFTER_CONFIRMED_FAR") })
    }

    @Test
    fun unmarkedSessionIsExcludedFromTheSummaryAndNegativeAndInterruptedSessionsAreSeparate() {
        val harness = harness()
        val triggering = runBlocking { harness.createConfig("触发配置", enterThresholdDbm = -65) }

        val markedSession = seedSession(
            harness = harness,
            id = "summary-marked",
            timeline = timelineWithEventAt("summary-marked", 10_000L),
            coverageEndMillis = 20_000L,
        )
        runBlocking {
            harness.sessionStore.insertReferenceMarker(
                marker(sessionId = markedSession.id, offsetMillis = 10_000L),
            )
        }
        val marked = (runBlocking {
            harness.comparisonService.compare(
                markedSession.id,
                listOf(ComparisonTarget.Config(triggering.configId)),
                ScoringWindow(),
            )
        } as SessionComparisonResult.Completed).comparison

        val unmarkedSession = seedSession(
            harness = harness,
            id = "summary-unmarked",
            timeline = timelineWithEventAt("summary-unmarked", 10_000L),
            coverageEndMillis = 20_000L,
        )
        val unmarked = (runBlocking {
            harness.comparisonService.compare(
                unmarkedSession.id,
                listOf(ComparisonTarget.Config(triggering.configId)),
                ScoringWindow(),
            )
        } as SessionComparisonResult.Completed).comparison
        assertEquals(TrialOutcome.SKIPPED, unmarked.trials.single().score.outcome)
        assertEquals(0, unmarked.summary.forConfig(triggering.defaultSnapshotId())!!.includedSessionCount)

        val combined = ComparisonSummaryBuilder.build(
            listOf(
                ScoredSessionTrials(marked.session, marked.trials),
                ScoredSessionTrials(unmarked.session, unmarked.trials),
            ),
        ).forConfig(triggering.defaultSnapshotId())!!
        assertEquals(1, combined.includedSessionCount)
        assertEquals(1, combined.excludedSessionCount)
        assertEquals(TrialOutcome.SKIPPED, combined.exclusions.single().outcome)
        assertTrue(combined.exclusions.single().reason.contains("未标记"))

        val negativeSession = seedSession(
            harness = harness,
            id = "summary-negative",
            timeline = timelineWithEventAt("summary-negative", 5_000L),
            coverageEndMillis = 20_000L,
            label = SessionEvaluationLabel.NO_TRIGGER,
        )
        val negative = (runBlocking {
            harness.comparisonService.compare(
                negativeSession.id,
                listOf(ComparisonTarget.Config(triggering.configId)),
                ScoringWindow(),
            )
        } as SessionComparisonResult.Completed).comparison
        val negativeScore = negative.trials.single().score
        assertEquals(TrialOutcome.FALSE_TRIGGER, negativeScore.outcome)
        assertEquals(1, negativeScore.falseTriggerCount)
        val negativeSummary = negative.summary.forConfig(triggering.defaultSnapshotId())!!
        assertEquals(1, negativeSummary.includedSessionCount)
        assertEquals(1, negativeSummary.falseTriggerCount)
        assertEquals(1, negativeSummary.outcomeCounts[TrialOutcome.FALSE_TRIGGER])

        val interruptedSession = seedSession(
            harness = harness,
            id = "summary-interrupted",
            timeline = timelineWithEventAt("summary-interrupted", 10_000L),
            coverageEndMillis = 11_000L,
            status = SessionLifecycleStatus.INTERRUPTED,
        )
        runBlocking {
            harness.sessionStore.insertReferenceMarker(
                marker(sessionId = interruptedSession.id, offsetMillis = 10_000L),
            )
        }
        val interrupted = (runBlocking {
            harness.comparisonService.compare(
                interruptedSession.id,
                listOf(ComparisonTarget.Config(triggering.configId)),
                ScoringWindow(),
            )
        } as SessionComparisonResult.Completed).comparison
        val interruptedScore = interrupted.trials.single().score
        assertEquals(TrialOutcome.INSUFFICIENT_OBSERVATION, interruptedScore.outcome)
        assertFalse(interruptedScore.includedInSummary)
        assertEquals(false, interruptedScore.coverageCoversWindow)
        val interruptedSummary = interrupted.summary.forConfig(triggering.defaultSnapshotId())!!
        assertEquals(0, interruptedSummary.includedSessionCount)
        assertEquals(1, interruptedSummary.excludedSessionCount)
        assertTrue(interruptedSummary.exclusions.single().reason.contains("观测不足"))
    }

    @Test
    fun oldVersionSnapshotIsRecomputedButScoreKeepsConfigAndVersionDifferences() {
        val harness = harness()
        val oldSnapshot = snapshot("旧版本配置").copy(
            algorithmVersion = "proximity-algorithms/0",
            triggerRuleVersion = "confirmed-far-then-near/0",
        )
        val session = seedSession(
            harness = harness,
            id = "old-version-comparison",
            timeline = timelineWithEventAt("old-version-comparison", 10_000L),
            coverageEndMillis = 20_000L,
            snapshot = oldSnapshot,
        )
        runBlocking {
            harness.sessionStore.insertReferenceMarker(
                marker(sessionId = session.id, offsetMillis = 10_000L),
            )
        }

        val comparison = (runBlocking {
            harness.comparisonService.compare(
                session.id,
                listOf(ComparisonTarget.OriginalSnapshot),
                ScoringWindow(1_000L, 2_000L),
            )
        } as SessionComparisonResult.Completed).comparison
        val trial = comparison.trials.single()

        assertEquals(TrialOutcome.ON_TIME, trial.score.outcome)
        assertEquals(ReplayOutcome.VERSION_RECOMPUTED, trial.outcome)
        assertEquals(ReplayConfigSource.ORIGINAL_SNAPSHOT, trial.configSource)
        assertTrue(trial.isOriginalRunConfig)
        assertEquals(oldSnapshot.snapshotId, trial.config.snapshotId)
        assertEquals("proximity-algorithms/0", trial.versions.snapshotAlgorithmVersion)
        assertEquals(ProximityEngine.ALGORITHM_VERSION, trial.versions.executedAlgorithmVersion)
        assertEquals(2, trial.versions.differences.size)
        assertEquals(1, comparison.summary.configs.size)
        assertEquals(1, comparison.summary.configs.single().includedSessionCount)
    }

    @Test
    fun contextExposesMarkersAndOriginalSnapshotBeforeRunning() {
        val harness = harness()
        val snapshot = snapshot("当次配置")
        val session = seedSession(
            harness = harness,
            id = "context-session",
            timeline = timelineWithEventAt("context-session", 10_000L),
            coverageEndMillis = 20_000L,
            snapshot = snapshot,
        )
        runBlocking {
            harness.sessionStore.insertReferenceMarker(
                marker(sessionId = session.id, offsetMillis = 10_000L),
            )
        }

        val context = runBlocking { harness.comparisonService.loadContext(session.id) }
        assertNotNull(context)
        assertEquals(snapshot, context!!.configSnapshot)
        assertEquals(1, context.evaluation.markersInCoverage.size)
        assertEquals(session.startedAtEpochMillis + 10_000L, context.evaluation.expectedTriggerAtEpochMillis)
    }

    private fun harness(): Harness {
        val database = Room.databaseBuilder(
            context,
            ProximityLabDatabase::class.java,
            databaseName,
        ).addMigrations(
            ProximityLabDatabase.MIGRATION_1_2,
            ProximityLabDatabase.MIGRATION_2_3,
            ProximityLabDatabase.MIGRATION_3_4,
            ProximityLabDatabase.MIGRATION_4_5,
            ProximityLabDatabase.MIGRATION_5_6,
        ).build()
        val sessionStore: SessionStore = RoomSessionStore(database.samplingSessionDao())
        val configStore: AlgorithmConfigStore = RoomAlgorithmConfigStore(
            dao = database.algorithmConfigDao(),
            clock = { now },
        )
        val replayService = SessionReplayService(
            sessionStore = sessionStore,
            replayStore = RoomSessionReplayStore(database.sessionReplayDao()),
            configStore = configStore,
            clock = { now },
            idGenerator = { "comparison-replay-${UUID.randomUUID()}" },
        )
        val comparisonService = SessionComparisonService(
            sessionStore = sessionStore,
            replayService = replayService,
            configStore = configStore,
        )
        return Harness(
            database = database,
            sessionStore = sessionStore,
            configStore = configStore,
            replayService = replayService,
            comparisonService = comparisonService,
        ).also { harnesses += it }
    }

    private inner class Harness(
        private val database: ProximityLabDatabase,
        val sessionStore: SessionStore,
        val configStore: AlgorithmConfigStore,
        val replayService: SessionReplayService,
        val comparisonService: SessionComparisonService,
    ) {
        suspend fun createConfig(name: String, enterThresholdDbm: Int): AlgorithmConfig =
            configStore.createConfig(
                name = name,
                config = ProximityConfig(
                    algorithm = ProximityAlgorithm.BASELINE,
                    enterThresholdDbm = enterThresholdDbm,
                    exitThresholdDbm = -80,
                    confirmation = ConfirmationRequirement.ConsecutiveSamples(2),
                    sampleValidityMillis = 5_000L,
                    emaAlpha = 0.5,
                ),
            )

        fun close() {
            database.close()
        }
    }

    private fun seedSession(
        harness: Harness,
        id: String,
        timeline: List<SessionTimelineItem>,
        coverageEndMillis: Long,
        snapshot: SessionConfigSnapshot? = null,
        status: SessionLifecycleStatus = SessionLifecycleStatus.COMPLETED,
        label: SessionEvaluationLabel = SessionEvaluationLabel.UNMARKED,
    ): SamplingSession {
        val session = SamplingSession(
            id = id,
            targetId = "target-1",
            targetDisplayName = "目标车",
            targetAddress = "AA:BB:CC:DD:EE:01",
            samplingType = if (snapshot != null) SamplingType.SIMULATION else SamplingType.WALKING,
            name = id,
            location = FixedSampleLocation.OTHER,
            estimatedDistanceMeters = null,
            phonePlacement = PhonePlacement.HANDHELD,
            status = status,
            startedAtEpochMillis = now,
            endedAtEpochMillis = if (status == SessionLifecycleStatus.COMPLETED) {
                now + coverageEndMillis
            } else {
                null
            },
            lastRecordedAtEpochMillis = now + coverageEndMillis,
            interruptedDetectedAtEpochMillis = if (status == SessionLifecycleStatus.INTERRUPTED) {
                now + coverageEndMillis + 1L
            } else {
                null
            },
            evaluationLabel = label,
        )
        runBlocking {
            if (snapshot != null) {
                harness.sessionStore.createSimulationSession(session, snapshot)
            } else {
                harness.sessionStore.createSession(session)
            }
            timeline.forEach { item -> harness.sessionStore.appendTimeline(session.id, item.event) }
        }
        return session
    }

    private fun timelineWithEventAt(
        sessionId: String,
        eventAtMillis: Long,
    ): List<SessionTimelineItem> {
        val offsets = listOf(
            0L to -85,
            100L to -85,
            eventAtMillis - 800L to -85,
            eventAtMillis - 600L to -85,
            eventAtMillis - 100L to -60,
            eventAtMillis to -60,
        )
        return offsets.mapIndexed { index, (offset, rssi) ->
            observation(
                sessionId = sessionId,
                sequence = index + 1L,
                offsetMillis = offset,
                rssi = rssi,
            )
        }
    }

    private fun observation(
        sessionId: String,
        sequence: Long,
        offsetMillis: Long,
        rssi: Int,
    ): SessionTimelineItem = SessionTimelineItem(
        sequence = sequence,
        sessionId = sessionId,
        event = SessionTimelineEvent.Observation(
            observationId = "$sessionId-observation-$sequence",
            targetId = "target-1",
            rawRssiDbm = rssi,
            observedAtEpochMillis = now + offsetMillis,
            receivedAtEpochMillis = now + offsetMillis,
            scanFrameId = 10_000L + sequence,
            deliverySequence = sequence,
            address = "AA:BB:CC:DD:EE:01",
            payloadFingerprint = "$sessionId-fingerprint-$sequence",
            processedAtEpochMillis = now + offsetMillis,
        ),
    )

    private fun marker(
        sessionId: String,
        offsetMillis: Long,
    ): SessionReferenceMarker = SessionReferenceMarker(
        markerId = "marker-$sessionId",
        sessionId = sessionId,
        kind = ReferenceMarkerKind.EXPECTED_TRIGGER,
        atEpochMillis = now + offsetMillis,
    )

    private fun snapshot(name: String): SessionConfigSnapshot = SessionConfigSnapshot(
        snapshotId = "comparison-snapshot-$name",
        name = name,
        sourceConfigId = null,
        config = ProximityConfig(
            algorithm = ProximityAlgorithm.BASELINE,
            enterThresholdDbm = -65,
            exitThresholdDbm = -80,
            confirmation = ConfirmationRequirement.ConsecutiveSamples(2),
            sampleValidityMillis = 5_000L,
            emaAlpha = 0.5,
        ),
    )
}
