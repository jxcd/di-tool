package com.byd.proximitylab.verification

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.byd.proximitylab.algorithmconfig.BuiltInAlgorithmConfigs
import com.byd.proximitylab.chart.RssiSeriesKind
import com.byd.proximitylab.comparison.ComparisonTarget
import com.byd.proximitylab.comparison.MissedTriggerCause
import com.byd.proximitylab.comparison.ScoredEventRole
import com.byd.proximitylab.comparison.SessionComparisonResult
import com.byd.proximitylab.comparison.TrialOutcome
import com.byd.proximitylab.proximity.ConfirmationRequirement
import com.byd.proximitylab.proximity.ProximityAlgorithm
import com.byd.proximitylab.proximity.ProximityConfig
import com.byd.proximitylab.proximity.ProximityState
import com.byd.proximitylab.replay.ReplayConfigRequest
import com.byd.proximitylab.replay.ReplayOutcome
import com.byd.proximitylab.replay.SessionReplayResult
import com.byd.proximitylab.session.ReferenceMarkerKind
import com.byd.proximitylab.session.SamplingType
import com.byd.proximitylab.session.SessionLifecycleStatus
import com.byd.proximitylab.session.SessionTimelineEvent
import com.byd.proximitylab.simulation.SimulatedEventFeedbackStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 12 的软件主路径：从生产 ViewModel 入口完成人工绑定、行走采样、模拟测试、Session 详情、
 * 配置编辑/删除、原样回放与多配置评分对比。
 *
 * 只替代 BLE 系统回调和通知/前台服务平台边界；AppContainer、Room、Session、共享判定、
 * 回放、对比与全部 ViewModel 都是生产实现。
 */
@RunWith(AndroidJUnit4::class)
class SoftwareVerificationFlowTest {

    private lateinit var harness: SoftwareVerificationHarness

    @Before
    fun setUp() {
        harness = SoftwareVerificationHarness()
    }

    @After
    fun tearDown() {
        harness.close()
    }

    @Test
    fun mainPathBindsSamplesSimulatesAndComparesThroughProductionEntries() = runBlocking {
        val target = harness.bindThroughDeviceEntry()
        val targetId = target.targetId

        // 1. 行走采样：生产 SamplingViewModel → SessionRecorder → Room，详情可查原始时间线和人工标记。
        val samplingViewModel = harness.samplingViewModel()
        harness.waitUntil("采样页读取到绑定") {
            samplingViewModel.uiState.value.binding != null
        }
        samplingViewModel.selectSamplingType(SamplingType.WALKING)
        samplingViewModel.updateName("软件验收行走")
        samplingViewModel.startSampling()
        harness.waitUntil("行走采样开始") {
            harness.container.sessionRecorder.activeSession.value?.samplingType == SamplingType.WALKING
        }
        val walkingSessionId =
            requireNotNull(harness.container.sessionRecorder.activeSession.value).id
        harness.feed(rssi = -82)
        harness.feed(rssi = -78)
        harness.feed(rssi = -76)
        harness.waitUntil("行走采样原始观测入库") {
            harness.observations(walkingSessionId).size == 3
        }
        samplingViewModel.addReferenceMarker(ReferenceMarkerKind.EXPECTED_TRIGGER)
        harness.waitUntil("行走采样人工标记入库") {
            harness.markers(walkingSessionId).any { marker ->
                marker.kind == ReferenceMarkerKind.EXPECTED_TRIGGER
            }
        }
        samplingViewModel.stopSampling()
        harness.waitUntil("行走 Session 正常结束") {
            harness.session(walkingSessionId)?.status == SessionLifecycleStatus.COMPLETED
        }
        samplingViewModel.openSession(walkingSessionId)
        harness.waitUntil("打开行走采样详情") {
            samplingViewModel.uiState.value.detail?.session?.id == walkingSessionId
        }
        val walkingDetail = requireNotNull(samplingViewModel.uiState.value.detail)
        assertEquals(3, walkingDetail.observationCount)
        assertEquals(1, walkingDetail.markers.size)
        assertNull(walkingDetail.configSnapshot)
        assertTrue(walkingDetail.chart.hasData)
        assertTrue(
            walkingDetail.timeline
                .mapNotNull { item -> item.event as? SessionTimelineEvent.Observation }
                .all { observation ->
                    observation.targetId == targetId &&
                        observation.address == SoftwareVerificationHarness.TARGET_ADDRESS
                },
        )

        // 2. 具名配置：开始模拟测试时固定快照；运行中修改只影响下一次测试。
        val runningConfig = harness.container.algorithmConfigStore.createConfig(
            name = "软件验收配置",
            config = ProximityConfig(
                algorithm = ProximityAlgorithm.BASELINE,
                enterThresholdDbm = -60,
                exitThresholdDbm = -75,
                confirmation = ConfirmationRequirement.ConsecutiveSamples(minimumSamples = 2),
                sampleValidityMillis = 60_000L,
                emaAlpha = 0.5,
            ),
        )
        assertTrue(harness.container.algorithmConfigStore.selectConfig(runningConfig.configId))
        val strictConfig = harness.container.algorithmConfigStore.createConfig(
            name = "严格不触发配置",
            config = ProximityConfig(
                algorithm = ProximityAlgorithm.BASELINE,
                enterThresholdDbm = -30,
                exitThresholdDbm = -45,
                confirmation = ConfirmationRequirement.ConsecutiveSamples(minimumSamples = 2),
                sampleValidityMillis = 60_000L,
                emaAlpha = 0.5,
            ),
        )

        // 3. 模拟测试：生产 MonitorViewModel → LiveMonitorController → 共享判定 → Room。
        val monitorViewModel = harness.monitorViewModel()
        monitorViewModel.startSimulationNow()
        harness.waitUntil("模拟测试开始") {
            val test = harness.container.liveMonitorController.state.value.test
            test != null &&
                test.running &&
                harness.session(test.session.id)?.status == SessionLifecycleStatus.RECORDING
        }
        val simulationSessionId = requireNotNull(
            harness.container.liveMonitorController.state.value.test,
        ).session.id
        val snapshotAtStart = requireNotNull(
            harness.container.sessionStore.loadConfigSnapshot(simulationSessionId),
        )
        assertEquals(runningConfig.configId, snapshotAtStart.sourceConfigId)
        assertEquals("软件验收配置", snapshotAtStart.name)
        assertEquals(-60, snapshotAtStart.config.enterThresholdDbm)

        harness.feed(rssi = -88)
        harness.feed(rssi = -88)
        harness.waitUntil("普通判定确认 FAR") {
            harness.container.liveMonitorController.state.value.test?.decision?.state ==
                ProximityState.FAR
        }
        assertTrue("确认 FAR 不应产生事件", harness.events(simulationSessionId).isEmpty())

        monitorViewModel.markExpectedTrigger()
        harness.waitUntil("记录期望触发位置") {
            harness.markers(simulationSessionId).any { marker ->
                marker.kind == ReferenceMarkerKind.EXPECTED_TRIGGER
            }
        }

        harness.feed(rssi = -55)
        harness.feed(rssi = -55)
        harness.waitUntil("首次 FAR → NEAR 只产生一条事件") {
            harness.events(simulationSessionId).size == 1
        }
        assertEquals(
            ProximityState.NEAR,
            harness.container.liveMonitorController.state.value.test?.decision?.state,
        )
        harness.waitUntil("通知反馈写回事件") {
            harness.events(simulationSessionId).single().feedbackStatus ==
                SimulatedEventFeedbackStatus.DELIVERED
        }
        assertEquals(1, harness.alarm.deliveries.size)

        // 停留在 NEAR 不重复；确认回到 FAR 后折返可再次触发，作为额外事件保留。
        val observationsBeforeStayingNear = harness.observations(simulationSessionId).size
        harness.feed(rssi = -55)
        harness.waitUntil("停留 NEAR 的额外帧入库") {
            harness.observations(simulationSessionId).size == observationsBeforeStayingNear + 1
        }
        harness.waitForTestDecisionAt(
            harness.observations(simulationSessionId).last().processedAtEpochMillis,
        )
        assertEquals(1, harness.events(simulationSessionId).size)
        harness.feed(rssi = -88)
        harness.feed(rssi = -88)
        harness.waitUntil("折返前重新确认 FAR") {
            harness.container.liveMonitorController.state.value.test?.decision?.state ==
                ProximityState.FAR
        }
        harness.feed(rssi = -55)
        harness.feed(rssi = -55)
        harness.waitUntil("折返后再次触发") {
            harness.events(simulationSessionId).size == 2
        }
        harness.waitUntil("两条事件都写回反馈") {
            harness.events(simulationSessionId).all { event ->
                event.feedbackStatus == SimulatedEventFeedbackStatus.DELIVERED
            }
        }

        // 评分窗默认含标记后 2 s 迟到容差；让记录覆盖到评分窗终点，避免把短记录误判为观测不足。
        val markerAt = harness.markers(simulationSessionId)
            .first { marker -> marker.kind == ReferenceMarkerKind.EXPECTED_TRIGGER }
            .atEpochMillis
        val scoringWindowEnd = markerAt + DEFAULT_LATE_TOLERANCE_MILLIS + 200L
        val remainingCoverage = scoringWindowEnd - System.currentTimeMillis()
        if (remainingCoverage > 0L) {
            Thread.sleep(remainingCoverage)
        }

        harness.container.algorithmConfigStore.updateConfig(
            configId = runningConfig.configId,
            name = "软件验收配置-已改",
            config = snapshotAtStart.config.copy(enterThresholdDbm = -20),
        )
        val snapshotDuringRun = requireNotNull(
            harness.container.sessionStore.loadConfigSnapshot(simulationSessionId),
        )
        assertEquals("运行中编辑不能改变当次快照", "软件验收配置", snapshotDuringRun.name)
        assertEquals(-60, snapshotDuringRun.config.enterThresholdDbm)
        assertEquals(
            -60,
            harness.container.liveMonitorController.state.value.test?.config?.config
                ?.enterThresholdDbm,
        )

        monitorViewModel.stopSimulation()
        harness.waitUntil("模拟测试正常结束") {
            harness.session(simulationSessionId)?.status == SessionLifecycleStatus.COMPLETED &&
                !harness.container.liveMonitorController.state.value.simulationRunning
        }

        assertTrue(
            harness.container.algorithmConfigStore.deleteConfig(runningConfig.configId),
        )
        assertNull(harness.container.algorithmConfigStore.loadConfig(runningConfig.configId))
        val snapshotAfterDelete = requireNotNull(
            harness.container.sessionStore.loadConfigSnapshot(simulationSessionId),
        )
        assertEquals("删除原配置不能改变当次快照", "软件验收配置", snapshotAfterDelete.name)
        assertEquals(-60, snapshotAfterDelete.config.enterThresholdDbm)

        // 4. Session 详情：原始时间线、标记、当次快照与模拟事件分别可查。
        samplingViewModel.openSession(simulationSessionId)
        harness.waitUntil("打开模拟测试详情") {
            samplingViewModel.uiState.value.detail?.session?.id == simulationSessionId
        }
        val simulationDetail = requireNotNull(samplingViewModel.uiState.value.detail)
        assertEquals(1, simulationDetail.markers.size)
        assertEquals("软件验收配置", simulationDetail.configSnapshot?.name)
        assertEquals(-60, simulationDetail.configSnapshot?.config?.enterThresholdDbm)
        assertEquals(2, simulationDetail.runEvents.size)
        assertTrue(
            simulationDetail.runEvents.all { event ->
                event.feedbackUpdatedAtEpochMillis != null
            },
        )
        val simulationObservations = simulationDetail.timeline
            .mapNotNull { item -> item.event as? SessionTimelineEvent.Observation }
        assertEquals(9, simulationObservations.size)
        assertTrue(simulationObservations.all { observation ->
            observation.targetId == targetId &&
                observation.address == SoftwareVerificationHarness.TARGET_ADDRESS
        })
        assertTrue(simulationObservations.any { observation -> observation.rawRssiDbm == -88 })
        assertTrue(simulationObservations.any { observation -> observation.rawRssiDbm == -55 })
        assertEquals(
            1,
            simulationDetail.evaluation.expectedTriggerMarkersInCoverage.size,
        )
        assertTrue(simulationDetail.chart.hasData)
        assertTrue(simulationDetail.chart.markers.isNotEmpty())

        // 5. 原样回放：同一版本使用当次快照重现两条事件，不追加实时历史、不触发通知。
        val alarmAttemptsBeforeReplay = harness.alarm.attempts.size
        val liveEventsBeforeReplay = harness.events(simulationSessionId)
        val replayResult = harness.container.sessionReplayService.replay(
            sessionId = simulationSessionId,
            request = ReplayConfigRequest.OriginalSnapshot,
        )
        assertTrue(replayResult is SessionReplayResult.Completed)
        val replayRecord = (replayResult as SessionReplayResult.Completed).record
        assertEquals(ReplayOutcome.REPRODUCED, replayRecord.outcome)
        assertEquals(2, replayRecord.events.size)
        assertEquals(9, replayRecord.coverage.observationCount)
        assertFalse(replayRecord.coverage.interrupted)
        assertEquals(alarmAttemptsBeforeReplay, harness.alarm.attempts.size)
        assertEquals(liveEventsBeforeReplay, harness.events(simulationSessionId))

        samplingViewModel.openSession(simulationSessionId)
        harness.waitUntil("详情显示回放历史") {
            samplingViewModel.uiState.value.detail?.session?.id == simulationSessionId &&
                samplingViewModel.uiState.value.detail?.replayHistory?.isNotEmpty() == true
        }
        assertEquals(
            SimulatedEventFeedbackStatus.DELIVERED,
            samplingViewModel.uiState.value.detail?.runEvents?.first()?.feedbackStatus,
        )

        // 6. 多配置对比与评分：当次快照、内置配置和严格配置同轴回放，评分窗含端点。
        val labViewModel = harness.labViewModel()
        labViewModel.selectComparisonSession(simulationSessionId)
        harness.waitUntil("实验页载入 Session 上下文") {
            val comparison = labViewModel.uiState.value.comparison
            comparison.context?.session?.id == simulationSessionId && !comparison.contextLoading
        }
        val desiredTargets = setOf(
            ComparisonTarget.OriginalSnapshot,
            ComparisonTarget.Config(BuiltInAlgorithmConfigs.BASELINE.configId),
            ComparisonTarget.Config(strictConfig.configId),
        )
        val selected = labViewModel.uiState.value.comparison.selectedTargets
        (selected - desiredTargets).forEach(labViewModel::toggleComparisonTarget)
        (desiredTargets - selected).forEach(labViewModel::toggleComparisonTarget)
        labViewModel.runComparison()
        harness.waitUntil("对比完成") {
            val comparison = labViewModel.uiState.value.comparison
            !comparison.running && comparison.result != null
        }
        val comparison = requireNotNull(labViewModel.uiState.value.comparison.result)
        assertEquals(
            desiredTargets,
            comparison.trials.map { trial -> trial.target }.toSet(),
        )
        assertTrue(comparison.failures.isEmpty())

        val originalTrial = comparison.trials.single { trial ->
            trial.target == ComparisonTarget.OriginalSnapshot
        }
        assertEquals(ReplayOutcome.REPRODUCED, originalTrial.outcome)
        assertEquals(snapshotAtStart.snapshotId, originalTrial.config.snapshotId)
        assertEquals(TrialOutcome.ON_TIME, originalTrial.score.outcome)
        assertEquals(ScoredEventRole.FIRST, originalTrial.score.firstEvent?.role)
        assertEquals(1, originalTrial.score.extraEventCount)
        assertEquals(2, originalTrial.replay.events.size)

        val strictTrial = comparison.trials.single { trial ->
            trial.target == ComparisonTarget.Config(strictConfig.configId)
        }
        assertEquals(TrialOutcome.MISSED, strictTrial.score.outcome)
        assertEquals(
            MissedTriggerCause.FAR_CONFIRMED_WITHOUT_TRIGGER,
            strictTrial.score.missedCause,
        )
        assertTrue(strictTrial.score.coverageCoversWindow == true)
        assertTrue(strictTrial.replay.events.isEmpty())

        assertTrue(comparison.chart.hasData)
        assertNotNull(comparison.chart.rawSeries)
        assertEquals(
            3,
            comparison.chart.series.count { series -> series.kind == RssiSeriesKind.FILTERED },
        )
        assertTrue(
            comparison.chart.markers.any { marker -> marker.id == "scoring-window-start" },
        )
        assertTrue(
            comparison.chart.markers.any { marker -> marker.id == "scoring-window-end" },
        )
        assertNotNull(comparison.summary.forConfig(snapshotAtStart.snapshotId))
        assertNotNull(comparison.summary.forConfig(strictTrial.config.snapshotId))
        assertEquals(alarmAttemptsBeforeReplay, harness.alarm.attempts.size)
        assertEquals(liveEventsBeforeReplay, harness.events(simulationSessionId))
    }

    private companion object {
        /** 与 10 默认评分窗的迟到容差一致。 */
        const val DEFAULT_LATE_TOLERANCE_MILLIS = 2_000L
    }
}
