package com.byd.proximitylab.replay

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.byd.proximitylab.algorithmconfig.AlgorithmConfigStore
import com.byd.proximitylab.algorithmconfig.data.RoomAlgorithmConfigStore
import com.byd.proximitylab.ble.ScanAvailability
import com.byd.proximitylab.data.ProximityLabDatabase
import com.byd.proximitylab.live.LiveMonitorController
import com.byd.proximitylab.live.SimulationStartResult
import com.byd.proximitylab.live.SimulationStopResult
import com.byd.proximitylab.proximity.ConfirmationRequirement
import com.byd.proximitylab.proximity.MotionTrend
import com.byd.proximitylab.proximity.ProximityAlgorithm
import com.byd.proximitylab.proximity.ProximityConfig
import com.byd.proximitylab.proximity.ProximityEventReason
import com.byd.proximitylab.proximity.ProximityEventType
import com.byd.proximitylab.proximity.ProximityState
import com.byd.proximitylab.proximity.ProximityStateReason
import com.byd.proximitylab.replay.data.RoomSessionReplayStore
import com.byd.proximitylab.session.FixedSampleLocation
import com.byd.proximitylab.session.PhonePlacement
import com.byd.proximitylab.session.SamplingSession
import com.byd.proximitylab.session.SamplingType
import com.byd.proximitylab.session.SessionConfigSnapshot
import com.byd.proximitylab.session.SessionLifecycleStatus
import com.byd.proximitylab.session.SessionRecorder
import com.byd.proximitylab.session.SessionStore
import com.byd.proximitylab.session.SessionTimelineEvent
import com.byd.proximitylab.session.SessionTimelineItem
import com.byd.proximitylab.session.data.RoomSessionStore
import com.byd.proximitylab.simulation.SessionRunEvent
import com.byd.proximitylab.simulation.SessionRunState
import com.byd.proximitylab.simulation.SimulatedEventFeedbackStatus
import com.byd.proximitylab.simulation.data.RoomSimulationRunStore
import com.byd.proximitylab.target.TargetMonitor
import com.byd.proximitylab.target.data.RoomTargetBindingStore
import com.byd.proximitylab.testing.ControllableBleScanSource
import com.byd.proximitylab.testing.testAdvertisement
import com.byd.proximitylab.testing.testBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * 09 的真实存储与共享判定验收：通过 05 的实时入口记录 Session，再用 09 回放入口重现；
 * 同时覆盖 P4/P6 语义、中断覆盖终点、不同配置、配置删除、版本差异、重开 App 和回放隔离。
 *
 * 只替代 BLE 系统回调和时间输入；SessionRecorder、LiveMonitorController、ProximityEngine、
 * SessionStore、SessionReplayStore 都是生产实现。
 */
@RunWith(AndroidJUnit4::class)
class SessionReplayRoomTest {

    private lateinit var context: Context
    private lateinit var databaseName: String
    private val harnesses = mutableListOf<Harness>()
    private var now = 1_800_000_000_000L
    private var sequence = 0L
    private var replaySequence = 0L
    private var sessionSequence = 0L

    private val addressA = "AA:BB:CC:DD:EE:01"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "session-replay-${UUID.randomUUID()}.db"
    }

    @After
    fun tearDown() {
        harnesses.forEach { it.close() }
        context.deleteDatabase(databaseName)
    }

    @Test
    fun p4AndP6FixturesReplayFromRealSessionStorage() {
        val harness = harness()
        val p4Session = seedSession(
            harness = harness,
            id = "p4-storage-session",
            timeline = listOf(
                observation(sessionId = "p4-storage-session", sequence = 1, processedAt = 0L, rssi = -85, frameId = 601L),
                observation(sessionId = "p4-storage-session", sequence = 2, processedAt = 1_000L, rssi = -85, frameId = 602L),
                observation(sessionId = "p4-storage-session", sequence = 3, processedAt = 8_000L, rssi = -60, frameId = 603L),
                observation(sessionId = "p4-storage-session", sequence = 4, processedAt = 9_000L, rssi = -60, frameId = 604L),
            ),
            configSnapshot = configSnapshot("P4 存储配置"),
            endedAtEpochMillis = now + 10_000L,
            lastRecordedAtEpochMillis = now + 10_000L,
        )
        val p4 = (runBlocking { harness.replayService.replay(p4Session.id) } as SessionReplayResult.Completed).record

        assertTrue(p4.events.isEmpty())
        assertTrue(
            p4.states.any { state ->
                state.atSessionMillis == 6_000L &&
                    state.state == ProximityState.UNKNOWN &&
                    state.reason == ProximityStateReason.SAMPLES_EXPIRED
            },
        )
        assertEquals(ProximityState.NEAR, p4.finalState.state)
        assertEquals(10_000L, p4.finalState.atSessionMillis)

        val p6Session = seedSession(
            harness = harness,
            id = "p6-storage-session",
            timeline = listOf(
                observation(sessionId = "p6-storage-session", sequence = 1, processedAt = 1_000L, rssi = -85, frameId = 611L),
                // 同一帧重复投递。
                observation(sessionId = "p6-storage-session", sequence = 2, processedAt = 1_000L, rssi = -85, frameId = 611L),
                observation(sessionId = "p6-storage-session", sequence = 3, processedAt = 2_000L, rssi = -85, frameId = 612L),
                // 5.2 s 批量到达的强帧，观测时刻分别是 0 s / 0.1 s，已经过期。
                observation(sessionId = "p6-storage-session", sequence = 4, processedAt = 5_200L, observedAt = 0L, rssi = -60, frameId = 613L),
                observation(sessionId = "p6-storage-session", sequence = 5, processedAt = 5_200L, observedAt = 100L, rssi = -60, frameId = 614L),
            ),
            configSnapshot = configSnapshot("P6 存储配置"),
            endedAtEpochMillis = now + 5_200L,
            lastRecordedAtEpochMillis = now + 5_200L,
        )
        val p6 = (runBlocking { harness.replayService.replay(p6Session.id) } as SessionReplayResult.Completed).record

        assertTrue(p6.events.isEmpty())
        assertEquals(ProximityState.FAR, p6.finalState.state)
        assertEquals(5_200L, p6.finalState.atSessionMillis)
        assertEquals(listOf(611L, 612L), p6.samples.map { sample -> sample.frameId })
    }

    @Test
    fun liveSimulationReplayMatchesStatesEventsAndLeavesLiveHistoryUnchanged() {
        val harness = harness()
        val session = recordLiveSimulationWithGapAndScanRecovery(harness)
        val liveEvents = runBlocking { harness.runStore.loadEvents(session.id) }
        assertEquals(1, liveEvents.size)
        val liveStates = runBlocking { harness.runStore.loadStates(session.id) }
        assertTrue(liveStates.any { state -> state.reason == ProximityStateReason.SAMPLES_EXPIRED })
        assertTrue(liveStates.any { state -> state.reason == ProximityStateReason.SCAN_UNAVAILABLE })

        val delivered = runBlocking {
            harness.runStore.updateEventFeedback(
                eventId = liveEvents.single().eventId,
                status = SimulatedEventFeedbackStatus.DELIVERED,
                detail = "实时反馈已提交",
                atEpochMillis = session.startedAtEpochMillis + 15_000L,
            )
        }
        requireNotNull(delivered)
        val eventsBeforeReplay = runBlocking { harness.runStore.loadEvents(session.id) }
        val statesBeforeReplay = runBlocking { harness.runStore.loadStates(session.id) }

        val result = runBlocking { harness.replayService.replay(session.id) }
        val record = (result as SessionReplayResult.Completed).record

        assertEquals(ReplayOutcome.REPRODUCED, record.outcome)
        assertEquals(ReplayConfigSource.ORIGINAL_SNAPSHOT, record.configSource)
        assertFalse(record.versions.versionMismatch)
        assertEquals(liveEvents.size, record.events.size)

        assertEquals(
            liveEvents.map { event -> event.comparable() },
            record.events.map { event -> event.comparable() },
        )
        assertEquals(
            liveStates.map { state -> state.comparable() },
            record.states.map { state -> state.comparable() },
        )

        // 回放不写实时事件历史，也不更新实时反馈结果。
        assertEquals(eventsBeforeReplay, runBlocking { harness.runStore.loadEvents(session.id) })
        assertEquals(statesBeforeReplay, runBlocking { harness.runStore.loadStates(session.id) })
        assertEquals(
            SimulatedEventFeedbackStatus.DELIVERED,
            runBlocking { harness.runStore.loadEvents(session.id) }.single().feedbackStatus,
        )
        assertEquals(
            "实时反馈已提交",
            runBlocking { harness.runStore.loadEvents(session.id) }.single().feedbackDetail,
        )
        assertTrue(
            runBlocking { harness.runStore.loadEvents(session.id) }
                .none { event -> event.eventId.startsWith("replay") },
        )
    }

    @Test
    fun sameSessionTwoConfigsProduceIndependentDeterministicResults() {
        val harness = harness()
        val session = seedSession(
            harness = harness,
            id = "two-config-session",
            timeline = listOf(
                observation(sessionId = "two-config-session", sequence = 1, processedAt = 1_000L, rssi = -85, frameId = 101L),
                observation(sessionId = "two-config-session", sequence = 2, processedAt = 2_000L, rssi = -85, frameId = 102L),
                observation(sessionId = "two-config-session", sequence = 3, processedAt = 3_000L, rssi = -60, frameId = 103L),
                observation(sessionId = "two-config-session", sequence = 4, processedAt = 4_000L, rssi = -60, frameId = 104L),
            ),
            configSnapshot = null,
        )
        val triggering = configSnapshot("触发配置", enterThresholdDbm = -65)
        val notTriggering = configSnapshot("不触发配置", enterThresholdDbm = -50)

        val first = runBlocking {
            harness.replayService.replay(
                session.id,
                ReplayConfigRequest.NamedSnapshot(triggering),
            )
        } as SessionReplayResult.Completed
        val repeated = runBlocking {
            harness.replayService.replay(
                session.id,
                ReplayConfigRequest.NamedSnapshot(triggering),
            )
        } as SessionReplayResult.Completed
        val other = runBlocking {
            harness.replayService.replay(
                session.id,
                ReplayConfigRequest.NamedSnapshot(notTriggering),
            )
        } as SessionReplayResult.Completed

        assertEquals(1, first.record.events.size)
        assertTrue(other.record.events.isEmpty())
        assertEquals(ProximityState.NEAR, first.record.finalState.state)
        assertEquals(ProximityState.FAR, other.record.finalState.state)
        assertEquals(first.record.withoutIdentity(), repeated.record.withoutIdentity())
        assertNotEquals(first.record.config.snapshotId, other.record.config.snapshotId)
        assertEquals(ReplayConfigSource.NAMED_SNAPSHOT, first.record.configSource)
        assertEquals(ReplayOutcome.NEW_CALCULATION, first.record.outcome)

        val history = runBlocking { harness.replayService.loadReplays(session.id) }
        assertEquals(3, history.size)
        assertEquals(
            setOf(triggering.snapshotId, notTriggering.snapshotId),
            history.map { record -> record.config.snapshotId }.toSet(),
        )

        // 无当次快照的采样 Session 用当前选中配置分析，并标为新算结果。
        val selected = runBlocking {
            harness.configStore.createConfig(
                name = "当前选中配置",
                config = ProximityConfig(
                    algorithm = ProximityAlgorithm.BASELINE,
                    enterThresholdDbm = -50,
                    exitThresholdDbm = -80,
                    confirmation = ConfirmationRequirement.ConsecutiveSamples(2),
                    sampleValidityMillis = 5_000L,
                    emaAlpha = 0.5,
                ),
            )
        }
        assertTrue(runBlocking { harness.configStore.selectConfig(selected.configId) })
        val currentSelection = runBlocking {
            harness.replayService.replay(session.id)
        } as SessionReplayResult.Completed
        assertEquals(ReplayConfigSource.CURRENT_SELECTION, currentSelection.record.configSource)
        assertEquals(ReplayOutcome.NEW_CALCULATION, currentSelection.record.outcome)
        assertEquals(selected.configId, currentSelection.record.config.sourceConfigId)
        assertEquals("当前选中配置", currentSelection.record.config.name)
        assertTrue(currentSelection.record.events.isEmpty())

        assertTrue(runBlocking { harness.runStore.loadEvents(session.id) }.isEmpty())
        assertTrue(runBlocking { harness.runStore.loadStates(session.id) }.isEmpty())
    }

    @Test
    fun interruptedSessionReplaysOnlyRecordedCoverageAndFlagsInputGaps() {
        val harness = harness()
        val sessionId = "interrupted-replay"
        val session = seedSession(
            harness = harness,
            id = sessionId,
            status = SessionLifecycleStatus.INTERRUPTED,
            endedAtEpochMillis = null,
            lastRecordedAtEpochMillis = now + 4_000L,
            timeline = listOf(
                observation(sessionId = sessionId, sequence = 1, processedAt = 1_000L, rssi = -85, frameId = 201L),
                observation(sessionId = sessionId, sequence = 2, processedAt = 2_000L, rssi = -85, frameId = 202L),
                SessionTimelineItem(
                    sequence = 3,
                    sessionId = sessionId,
                    event = SessionTimelineEvent.EventGap(
                        droppedEventCount = 7,
                        processedAtEpochMillis = now + 3_000L,
                    ),
                ),
            ),
            configSnapshot = configSnapshot("中断配置"),
        )

        val record = (runBlocking { harness.replayService.replay(session.id) } as SessionReplayResult.Completed).record

        assertEquals(ReplayOutcome.REPRODUCED, record.outcome)
        assertTrue(record.coverage.interrupted)
        assertEquals(SessionLifecycleStatus.INTERRUPTED, record.coverage.sessionStatus)
        assertEquals(session.startedAtEpochMillis + 4_000L, record.coverage.coverageEndEpochMillis)
        assertTrue(record.coverage.hasEventGaps)
        assertEquals(7, record.coverage.droppedEventCount)
        assertEquals(ProximityState.FAR, record.finalState.state)
        assertEquals(4_000L, record.finalState.atSessionMillis)
        assertTrue(record.states.none { state -> state.atSessionMillis >= 6_000L })
        assertTrue(record.states.none { state -> state.reason == ProximityStateReason.SAMPLES_EXPIRED })

        assertEquals(1, runBlocking { harness.replayService.loadReplays(sessionId) }.size)
        assertTrue(runBlocking { harness.runStore.loadEvents(sessionId) }.isEmpty())
    }

    @Test
    fun deletedConfigurationSnapshotStillReplaysAndRecordsSurviveReopen() {
        val first = harness()
        val created = runBlocking {
            first.configStore.createConfig(
                name = "将被删除的配置",
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
        assertTrue(runBlocking { first.configStore.selectConfig(created.configId) })
        val snapshot = runBlocking { first.configStore.snapshotForNextSimulation() }
        val sessionId = "deleted-config-session"
        seedSession(
            harness = first,
            id = sessionId,
            timeline = listOf(
                observation(sessionId = sessionId, sequence = 1, processedAt = 1_000L, rssi = -85, frameId = 301L),
                observation(sessionId = sessionId, sequence = 2, processedAt = 2_000L, rssi = -85, frameId = 302L),
                observation(sessionId = sessionId, sequence = 3, processedAt = 3_000L, rssi = -60, frameId = 303L),
                observation(sessionId = sessionId, sequence = 4, processedAt = 4_000L, rssi = -60, frameId = 304L),
            ),
            configSnapshot = snapshot,
        )
        assertTrue(runBlocking { first.configStore.deleteConfig(created.configId) })
        assertNull(runBlocking { first.configStore.loadConfig(created.configId) })
        assertEquals(
            snapshot,
            runBlocking { first.sessionStore.loadConfigSnapshot(sessionId) },
        )

        val record = (runBlocking { first.replayService.replay(sessionId) } as SessionReplayResult.Completed).record
        assertEquals(ReplayOutcome.REPRODUCED, record.outcome)
        assertEquals(created.configId, record.config.sourceConfigId)
        assertEquals("将被删除的配置", record.config.name)
        assertEquals(1, record.events.size)

        first.close()
        harnesses.remove(first)

        // 重开 App 级存储：回放记录和当次快照仍按版本独立查询。
        val reopened = harness(persistBinding = false)
        val records = runBlocking { reopened.replayService.loadReplays(sessionId) }
        assertEquals(1, records.size)
        assertEquals(record, records.single())
        assertEquals(
            snapshot,
            runBlocking { reopened.sessionStore.loadConfigSnapshot(sessionId) },
        )
    }

    @Test
    fun versionDifferenceIsRecordedAndOriginalSnapshotRemainsReadable() {
        val harness = harness()
        val sessionId = "old-version-session"
        val oldSnapshot = configSnapshot("旧版本配置").copy(
            algorithmVersion = "proximity-algorithms/0",
            triggerRuleVersion = "confirmed-far-then-near/0",
        )
        seedSession(
            harness = harness,
            id = sessionId,
            timeline = listOf(
                observation(sessionId = sessionId, sequence = 1, processedAt = 1_000L, rssi = -85, frameId = 401L),
                observation(sessionId = sessionId, sequence = 2, processedAt = 2_000L, rssi = -85, frameId = 402L),
                observation(sessionId = sessionId, sequence = 3, processedAt = 3_000L, rssi = -60, frameId = 403L),
                observation(sessionId = sessionId, sequence = 4, processedAt = 4_000L, rssi = -60, frameId = 404L),
            ),
            configSnapshot = oldSnapshot,
        )

        val record = (runBlocking { harness.replayService.replay(sessionId) } as SessionReplayResult.Completed).record

        assertEquals(ReplayOutcome.VERSION_RECOMPUTED, record.outcome)
        assertTrue(record.versions.versionMismatch)
        assertEquals(2, record.versions.differences.size)
        assertEquals("proximity-algorithms/0", record.versions.snapshotAlgorithmVersion)
        assertEquals("proximity-algorithms/1", record.versions.executedAlgorithmVersion)
        assertEquals("confirmed-far-then-near/0", record.versions.snapshotTriggerRuleVersion)
        assertEquals("confirmed-far-then-near/1", record.versions.executedTriggerRuleVersion)
        assertEquals(1, record.events.size)

        // 原记录保留：当次快照仍是旧版本，供比较。
        assertEquals(
            oldSnapshot,
            runBlocking { harness.sessionStore.loadConfigSnapshot(sessionId) },
        )
        val persisted = runBlocking { harness.replayService.loadReplay(record.replayId) }
        assertEquals(record, persisted)
        assertEquals(
            record.versions,
            runBlocking { harness.replayService.loadReplays(sessionId) }.single().versions,
        )
    }

    private fun recordLiveSimulationWithGapAndScanRecovery(harness: Harness): SamplingSession {
        awaitUntil { harness.monitor.state.value.binding != null }
        harness.monitor.startScan()
        awaitUntil { harness.monitor.state.value.scanState is ScanAvailability.Scanning }

        val snapshot = configSnapshot("实时等价配置")
        val started = runBlocking { harness.controller.startSimulation(snapshot) }
        assertTrue(started is SimulationStartResult.Started)
        val session = (started as SimulationStartResult.Started).session
        awaitUntil { harness.controller.state.value.simulationRunning }
        val start = session.startedAtEpochMillis

        harness.feed(session.id, start = start, offsetMillis = 1_000L, rssi = -85)
        harness.feed(session.id, start = start, offsetMillis = 2_000L, rssi = -85)
        awaitUntil { harness.controller.state.value.test?.decision?.state == ProximityState.FAR }

        now = start + 7_000L
        runBlocking { harness.controller.refreshNow(now) }
        awaitUntil {
            harness.controller.state.value.test?.decision?.reason ==
                ProximityStateReason.SAMPLES_EXPIRED
        }

        now = start + 8_000L
        harness.source.setScanState(ScanAvailability.Stopped)
        awaitUntil {
            harness.controller.state.value.test?.decision?.reason ==
                ProximityStateReason.SCAN_UNAVAILABLE
        }

        now = start + 9_000L
        harness.source.setScanState(ScanAvailability.Scanning)
        awaitUntil {
            harness.controller.state.value.test?.decision?.reason ==
                ProximityStateReason.AWAITING_OBSERVATION
        }

        harness.feed(session.id, start = start, offsetMillis = 10_000L, rssi = -85)
        harness.feed(session.id, start = start, offsetMillis = 11_000L, rssi = -85)
        awaitUntil { harness.controller.state.value.test?.decision?.state == ProximityState.FAR }

        harness.feed(session.id, start = start, offsetMillis = 12_000L, rssi = -60)
        harness.feed(session.id, start = start, offsetMillis = 13_000L, rssi = -60)
        awaitUntil {
            harness.controller.state.value.test?.decision?.state == ProximityState.NEAR &&
                harness.controller.state.value.test?.events?.size == 1
        }

        now = start + 14_000L
        val stopped = runBlocking { harness.controller.stopSimulation() }
        assertTrue(stopped is SimulationStopResult.Stopped)
        awaitUntil {
            harness.recorder.activeSession.value == null &&
                runBlocking { harness.sessionStore.loadSession(session.id)?.status } ==
                SessionLifecycleStatus.COMPLETED
        }
        awaitUntil {
            runBlocking { harness.runStore.loadStates(session.id) }.lastOrNull()?.reason ==
                ProximityStateReason.SCAN_UNAVAILABLE
        }
        return runBlocking { requireNotNull(harness.sessionStore.loadSession(session.id)) }
    }

    private fun harness(persistBinding: Boolean = true): Harness {
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
        val bindingStore = RoomTargetBindingStore(database.targetBindingDao())
        if (persistBinding) {
            runBlocking {
                bindingStore.saveBinding(
                    testBinding(
                        targetId = "target-1",
                        address = addressA,
                        displayName = "目标车",
                    ),
                )
            }
        }
        val source = ControllableBleScanSource()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val monitor = TargetMonitor(
            scanSource = source,
            bindingStore = bindingStore,
            scope = scope,
            clock = { now },
        )
        val sessionStore = RoomSessionStore(database.samplingSessionDao())
        val runStore = RoomSimulationRunStore(database.simulationRunDao())
        val replayStore = RoomSessionReplayStore(database.sessionReplayDao())
        val configStore = RoomAlgorithmConfigStore(database.algorithmConfigDao())
        val recorder = SessionRecorder(
            store = sessionStore,
            observationSource = monitor,
            scope = scope,
            clock = { now },
            idGenerator = { "replay-room-session-${++sessionSequence}" },
        )
        val controller = LiveMonitorController(
            observationSource = monitor,
            recorder = recorder,
            sessionStore = sessionStore,
            simulationStore = runStore,
            scope = scope,
            clock = { now },
            tickerIntervalMillis = 0L,
        )
        val replayService = SessionReplayService(
            sessionStore = sessionStore,
            replayStore = replayStore,
            configStore = configStore,
            clock = { now },
            idGenerator = { "replay-${++replaySequence}" },
        )
        return Harness(
            database = database,
            source = source,
            scope = scope,
            monitor = monitor,
            sessionStore = sessionStore,
            runStore = runStore,
            replayStore = replayStore,
            configStore = configStore,
            recorder = recorder,
            controller = controller,
            replayService = replayService,
        ).also { harnesses += it }
    }

    private fun seedSession(
        harness: Harness,
        id: String,
        timeline: List<SessionTimelineItem>,
        configSnapshot: SessionConfigSnapshot?,
        status: SessionLifecycleStatus = SessionLifecycleStatus.COMPLETED,
        endedAtEpochMillis: Long? = now + 4_000L,
        lastRecordedAtEpochMillis: Long = now + 4_000L,
    ): SamplingSession {
        val session = SamplingSession(
            id = id,
            targetId = "target-1",
            targetDisplayName = "目标车",
            targetAddress = addressA,
            samplingType = if (configSnapshot != null) SamplingType.SIMULATION else SamplingType.FIXED_POINT,
            name = id,
            location = FixedSampleLocation.OTHER,
            estimatedDistanceMeters = null,
            phonePlacement = PhonePlacement.HANDHELD,
            status = status,
            startedAtEpochMillis = now,
            endedAtEpochMillis = endedAtEpochMillis,
            lastRecordedAtEpochMillis = lastRecordedAtEpochMillis,
            interruptedDetectedAtEpochMillis = null,
        )
        runBlocking {
            if (configSnapshot != null) {
                harness.sessionStore.createSimulationSession(session, configSnapshot)
            } else {
                harness.sessionStore.createSession(session)
            }
            timeline.forEach { item ->
                harness.sessionStore.appendTimeline(session.id, item.event)
            }
        }
        return session
    }

    private fun observation(
        sessionId: String,
        sequence: Long,
        processedAt: Long,
        rssi: Int,
        frameId: Long,
        observedAt: Long = processedAt,
    ): SessionTimelineItem = SessionTimelineItem(
        sequence = sequence,
        sessionId = sessionId,
        event = SessionTimelineEvent.Observation(
            observationId = "observation-$frameId",
            targetId = "target-1",
            rawRssiDbm = rssi,
            observedAtEpochMillis = now + observedAt,
            receivedAtEpochMillis = now + observedAt,
            scanFrameId = frameId,
            deliverySequence = sequence,
            address = addressA,
            payloadFingerprint = "fingerprint-$frameId",
            processedAtEpochMillis = now + processedAt,
        ),
    )

    private fun configSnapshot(
        name: String,
        enterThresholdDbm: Int = -65,
    ): SessionConfigSnapshot = SessionConfigSnapshot(
        snapshotId = "replay-room-$name",
        name = name,
        sourceConfigId = null,
        config = ProximityConfig(
            algorithm = ProximityAlgorithm.BASELINE,
            enterThresholdDbm = enterThresholdDbm,
            exitThresholdDbm = -80,
            confirmation = ConfirmationRequirement.ConsecutiveSamples(2),
            sampleValidityMillis = 5_000L,
            emaAlpha = 0.5,
        ),
    )

    private fun awaitUntil(timeoutMillis: Long = TIMEOUT_MILLIS, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("等待条件超时：${timeoutMillis}ms")
            }
            Thread.sleep(10L)
        }
    }

    private inner class Harness(
        private val database: ProximityLabDatabase,
        val source: ControllableBleScanSource,
        private val scope: CoroutineScope,
        val monitor: TargetMonitor,
        val sessionStore: SessionStore,
        val runStore: RoomSimulationRunStore,
        val replayStore: RoomSessionReplayStore,
        val configStore: AlgorithmConfigStore,
        val recorder: SessionRecorder,
        val controller: LiveMonitorController,
        val replayService: SessionReplayService,
    ) {
        fun feed(sessionId: String, start: Long, offsetMillis: Long, rssi: Int) {
            now = start + offsetMillis
            sequence += 1
            val emitted = source.emit(
                testAdvertisement(
                    address = addressA,
                    rssi = rssi,
                    observedAtEpochMillis = now,
                    receivedAtEpochMillis = now,
                    observedAtNanos = sequence * 1_000_000_000L,
                    receivedAtNanos = sequence * 1_000_000_000L + 1_000_000L,
                    deliverySequence = sequence,
                    payloadFingerprint = "replay-frame-$sequence",
                ),
            )
            assertTrue("扫描事件缓冲拒绝投递", emitted)
            awaitUntil {
                runBlocking { sessionStore.loadTimeline(sessionId) }.any { item ->
                    val recorded = item.event
                    recorded is SessionTimelineEvent.Observation &&
                        recorded.processedAtEpochMillis >= now
                }
            }
            awaitUntil {
                (controller.state.value.test?.decision?.lastDecisionAtSessionMillis ?: -1L) >=
                    offsetMillis
            }
        }

        fun close() {
            runBlocking { scope.coroutineContext[Job]?.cancelAndJoin() }
            database.close()
        }
    }

    private fun SessionRunEvent.comparable(): EventComparison = EventComparison(
        atSessionMillis = atSessionMillis,
        type = type,
        reason = reason,
        targetId = targetId,
        frameId = frameId,
        observedAtEpochMillis = observedAtEpochMillis,
        rawRssiDbm = rawRssiDbm,
        filteredRssiDbm = filteredRssiDbm,
        algorithmVersion = algorithmVersion,
        triggerRuleVersion = triggerRuleVersion,
    )

    private fun ReplayEvent.comparable(): EventComparison = EventComparison(
        atSessionMillis = atSessionMillis,
        type = type,
        reason = reason,
        targetId = targetId,
        frameId = frameId,
        observedAtEpochMillis = observedAtEpochMillis,
        rawRssiDbm = rawRssiDbm,
        filteredRssiDbm = filteredRssiDbm,
        algorithmVersion = algorithmVersion,
        triggerRuleVersion = triggerRuleVersion,
    )

    private fun SessionRunState.comparable(): StateComparison = StateComparison(
        atSessionMillis = atSessionMillis,
        state = state,
        reason = reason,
        trend = trend,
        lastReadingFrameId = lastReadingFrameId,
        lastReadingObservedAtEpochMillis = lastReadingObservedAtEpochMillis,
        rawRssiDbm = rawRssiDbm,
        filteredRssiDbm = filteredRssiDbm,
    )

    private fun ReplayStatePoint.comparable(): StateComparison = StateComparison(
        atSessionMillis = atSessionMillis,
        state = state,
        reason = reason,
        trend = trend,
        lastReadingFrameId = lastReadingFrameId,
        lastReadingObservedAtEpochMillis = lastReadingObservedAtEpochMillis,
        rawRssiDbm = rawRssiDbm,
        filteredRssiDbm = filteredRssiDbm,
    )

    private fun SessionReplayRecord.withoutIdentity(): SessionReplayRecord = copy(
        replayId = "normalized-replay-id",
        createdAtEpochMillis = 0L,
    )

    private data class EventComparison(
        val atSessionMillis: Long,
        val type: ProximityEventType,
        val reason: ProximityEventReason,
        val targetId: String,
        val frameId: Long,
        val observedAtEpochMillis: Long,
        val rawRssiDbm: Int,
        val filteredRssiDbm: Double,
        val algorithmVersion: String,
        val triggerRuleVersion: String,
    )

    private data class StateComparison(
        val atSessionMillis: Long,
        val state: ProximityState,
        val reason: ProximityStateReason,
        val trend: MotionTrend,
        val lastReadingFrameId: Long?,
        val lastReadingObservedAtEpochMillis: Long?,
        val rawRssiDbm: Int?,
        val filteredRssiDbm: Double?,
    )

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
    }
}
