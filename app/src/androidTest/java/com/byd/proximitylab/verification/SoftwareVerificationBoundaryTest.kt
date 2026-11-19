package com.byd.proximitylab.verification

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.byd.proximitylab.algorithmconfig.BuiltInAlgorithmConfigs
import com.byd.proximitylab.comparison.ComparisonTarget
import com.byd.proximitylab.comparison.ScoringWindow
import com.byd.proximitylab.comparison.SessionComparisonResult
import com.byd.proximitylab.comparison.TrialOutcome
import com.byd.proximitylab.feedback.SimulationAlertCapabilities
import com.byd.proximitylab.proximity.ConfirmationRequirement
import com.byd.proximitylab.proximity.ProximityAlgorithm
import com.byd.proximitylab.proximity.ProximityConfig
import com.byd.proximitylab.proximity.ProximityEventReason
import com.byd.proximitylab.proximity.ProximityState
import com.byd.proximitylab.proximity.ProximityStateReason
import com.byd.proximitylab.replay.ReplayConfigRequest
import com.byd.proximitylab.replay.SessionReplayResult
import com.byd.proximitylab.session.FixedSampleLocation
import com.byd.proximitylab.session.PhonePlacement
import com.byd.proximitylab.session.ReferenceMarkerKind
import com.byd.proximitylab.session.SamplingType
import com.byd.proximitylab.session.SessionLifecycleStatus
import com.byd.proximitylab.session.SessionTimelineEvent
import com.byd.proximitylab.simulation.SimulatedEventFeedbackStatus
import com.byd.proximitylab.target.IdentityStatus
import com.byd.proximitylab.ui.monitor.MonitorViewModel
import com.byd.proximitylab.ui.sampling.SamplingViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking

/**
 * 12 的软件反例与恢复路径：冷启动近端、重复帧、样本过期、停扫恢复、身份不确定、
 * 中断覆盖不足、通知不可用与回放无实时反馈。
 *
 * 全部走生产 AppContainer、Room、共享判定、回放与 ViewModel；只替代 BLE 回调及通知平台边界。
 */
@RunWith(AndroidJUnit4::class)
class SoftwareVerificationBoundaryTest {

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
    fun duplicateFramesDoNotAccumulateAndNearOnlyStartDoesNotTrigger() = runBlocking {
        harness.bindThroughDeviceEntry()
        selectStableConfig("重复帧稳定性配置")
        val monitorViewModel = harness.monitorViewModel()
        val sessionId = startSimulation(monitorViewModel)

        val duplicateObservedAt = System.currentTimeMillis()
        val duplicateNanos = 42_000_000_000L
        harness.feed(
            rssi = -55,
            observedAtEpochMillis = duplicateObservedAt,
            observedAtNanos = duplicateNanos,
            payloadFingerprint = "duplicate-near-frame",
            deliverySequence = 900L,
        )
        harness.feed(
            rssi = -55,
            observedAtEpochMillis = duplicateObservedAt,
            observedAtNanos = duplicateNanos,
            payloadFingerprint = "duplicate-near-frame",
            deliverySequence = 900L,
        )
        harness.waitUntil("重复投递写入两条时间线记录") {
            harness.observations(sessionId).size == 2
        }
        val duplicates = harness.observations(sessionId)
        assertEquals(
            "同一扫描帧重复投递必须使用相同 frameId",
            duplicates[0].scanFrameId,
            duplicates[1].scanFrameId,
        )
        harness.waitForTestDecisionAt(duplicates[1].processedAtEpochMillis)
        assertEquals(
            "重复帧不能累积成为第二条确认证据",
            ProximityState.UNKNOWN,
            harness.container.liveMonitorController.state.value.test?.decision?.state,
        )

        harness.feed(rssi = -55)
        harness.feed(rssi = -55)
        harness.waitUntil("两条独立近端帧确认 NEAR") {
            harness.container.liveMonitorController.state.value.test?.decision?.state ==
                ProximityState.NEAR
        }
        assertTrue("近端冷启动不能补触发", harness.events(sessionId).isEmpty())

        monitorViewModel.stopSimulation()
        harness.waitUntil("近端冷启动测试结束") {
            harness.session(sessionId)?.status == SessionLifecycleStatus.COMPLETED
        }
    }

    @Test
    fun expiryAndScanRestartNeverReuseOldFarButFreshFarThenNearTriggersOnce() = runBlocking {
        harness.bindThroughDeviceEntry()
        val fastExpiry = harness.container.algorithmConfigStore.createConfig(
            name = "快速过期配置",
            config = ProximityConfig(
                algorithm = ProximityAlgorithm.BASELINE,
                enterThresholdDbm = -60,
                exitThresholdDbm = -75,
                confirmation = ConfirmationRequirement.ConsecutiveSamples(minimumSamples = 2),
                sampleValidityMillis = 10_000L,
                emaAlpha = 0.5,
            ),
        )
        assertTrue(harness.container.algorithmConfigStore.selectConfig(fastExpiry.configId))

        val monitorViewModel = harness.monitorViewModel()
        val sessionId = startSimulation(monitorViewModel)

        harness.feed(rssi = -88)
        harness.feed(rssi = -88)
        harness.waitUntil("确认 FAR") {
            harness.container.liveMonitorController.state.value.test?.decision?.state ==
                ProximityState.FAR
        }

        harness.waitUntil("无新样本时过期回 UNKNOWN", timeoutMillis = 20_000L) {
            val decision = harness.container.liveMonitorController.state.value.test?.decision
            decision?.state == ProximityState.UNKNOWN &&
                decision.reason == ProximityStateReason.SAMPLES_EXPIRED
        }
        harness.feed(rssi = -55)
        harness.feed(rssi = -55)
        harness.waitUntil("过期后恢复首批强样本确认 NEAR") {
            harness.container.liveMonitorController.state.value.test?.decision?.state ==
                ProximityState.NEAR
        }
        assertTrue("过期恢复后不能补触发", harness.events(sessionId).isEmpty())

        harness.container.targetMonitor.stopScan()
        harness.waitUntil("停扫回 UNKNOWN") {
            val decision = harness.container.liveMonitorController.state.value.test?.decision
            decision?.state == ProximityState.UNKNOWN &&
                decision.reason == ProximityStateReason.SCAN_UNAVAILABLE
        }
        harness.container.targetMonitor.startScan()
        harness.waitUntil("扫描恢复且身份重新确认") {
            harness.container.targetMonitor.state.value.scanState is
                com.byd.proximitylab.ble.ScanAvailability.Scanning &&
                harness.container.targetMonitor.state.value.identity.status ==
                IdentityStatus.CONFIRMED
        }
        harness.feed(rssi = -55)
        harness.feed(rssi = -55)
        harness.waitUntil("停扫恢复后首批强样本确认 NEAR") {
            harness.container.liveMonitorController.state.value.test?.decision?.state ==
                ProximityState.NEAR
        }
        assertTrue("停扫恢复后不能复用旧 FAR", harness.events(sessionId).isEmpty())

        harness.feed(rssi = -88)
        harness.feed(rssi = -88)
        harness.waitUntil("恢复后重新确认 FAR") {
            harness.container.liveMonitorController.state.value.test?.decision?.state ==
                ProximityState.FAR
        }
        harness.feed(rssi = -55)
        harness.feed(rssi = -55)
        harness.waitUntil("恢复后 FAR → NEAR 只产生一次事件") {
            harness.events(sessionId).size == 1
        }
        val observationsBeforeExtraNear = harness.observations(sessionId).size
        harness.feed(rssi = -55)
        harness.waitUntil("额外 NEAR 帧入库") {
            harness.observations(sessionId).size == observationsBeforeExtraNear + 1
        }
        harness.waitForTestDecisionAt(harness.observations(sessionId).last().processedAtEpochMillis)
        assertEquals(1, harness.events(sessionId).size)
        assertEquals(
            ProximityEventReason.CONFIRMED_NEAR_AFTER_CONFIRMED_FAR,
            harness.events(sessionId).single().reason,
        )

        monitorViewModel.stopSimulation()
        harness.waitUntil("过期与恢复测试结束") {
            harness.session(sessionId)?.status == SessionLifecycleStatus.COMPLETED
        }
    }

    @Test
    fun identityUncertaintySuppressesObservationsUntilOriginalAddressReturns() = runBlocking {
        harness.bindThroughDeviceEntry()
        selectStableConfig("身份稳定性配置")
        val monitorViewModel = harness.monitorViewModel()
        val sessionId = startSimulation(monitorViewModel)

        harness.feed(rssi = -55, address = SoftwareVerificationHarness.OTHER_ADDRESS)
        harness.feed(rssi = -55, address = SoftwareVerificationHarness.OTHER_ADDRESS)
        harness.waitUntil("新地址缺少唯一证据时身份不确定且无目标观测") {
            val state = harness.container.targetMonitor.state.value
            state.identity.status == IdentityStatus.UNCERTAIN &&
                state.targetObservationCount == 0
        }
        harness.waitUntil("身份变化写入会话时间线") {
            harness.timeline(sessionId)
                .mapNotNull { item -> item.event as? SessionTimelineEvent.IdentityChanged }
                .any { event -> !event.confirmed }
        }
        assertEquals(
            "身份未确认的广播不能作为判定观测入库",
            0,
            harness.observations(sessionId).size,
        )
        assertTrue(harness.events(sessionId).isEmpty())
        assertTrue(
            harness.timeline(sessionId)
                .mapNotNull { item -> item.event as? SessionTimelineEvent.IdentityChanged }
                .any { event -> !event.confirmed },
        )

        harness.feed(rssi = -88, address = SoftwareVerificationHarness.TARGET_ADDRESS)
        harness.feed(rssi = -88, address = SoftwareVerificationHarness.TARGET_ADDRESS)
        harness.waitUntil("原绑定地址恢复后身份确认并确认 FAR") {
            harness.container.targetMonitor.state.value.identity.status ==
                IdentityStatus.CONFIRMED &&
                harness.container.liveMonitorController.state.value.test?.decision?.state ==
                ProximityState.FAR
        }
        harness.feed(rssi = -55, address = SoftwareVerificationHarness.TARGET_ADDRESS)
        harness.feed(rssi = -55, address = SoftwareVerificationHarness.TARGET_ADDRESS)
        harness.waitUntil("恢复原地址后产生一次事件") {
            harness.events(sessionId).size == 1
        }
        assertEquals(
            SoftwareVerificationHarness.TARGET_ADDRESS,
            harness.observations(sessionId).last().address,
        )

        monitorViewModel.stopSimulation()
        harness.waitUntil("身份不确定测试结束") {
            harness.session(sessionId)?.status == SessionLifecycleStatus.COMPLETED
        }
    }

    @Test
    fun interruptedSessionIsRecoveredOnNextStartupAndCannotBeScoredAsMissed() = runBlocking {
        harness.bindThroughDeviceEntry()
        val samplingViewModel = harness.samplingViewModel()
        harness.waitUntil("采样页读取到绑定") {
            samplingViewModel.uiState.value.binding != null
        }
        samplingViewModel.selectSamplingType(SamplingType.FIXED_POINT)
        samplingViewModel.selectLocation(FixedSampleLocation.REAR)
        samplingViewModel.selectPhonePlacement(PhonePlacement.POCKET)
        samplingViewModel.updateName("软件验收中断")
        samplingViewModel.startSampling()
        harness.waitUntil("定点采样开始") {
            harness.container.sessionRecorder.activeSession.value?.samplingType ==
                SamplingType.FIXED_POINT
        }
        val sessionId = requireNotNull(harness.container.sessionRecorder.activeSession.value).id
        harness.feed(rssi = -88)
        harness.feed(rssi = -88)
        harness.waitUntil("中断前两条观测入库") {
            harness.observations(sessionId).size == 2
        }
        samplingViewModel.addReferenceMarker(ReferenceMarkerKind.EXPECTED_TRIGGER)
        harness.waitUntil("中断前标记入库") {
            harness.markers(sessionId).size == 1
        }
        harness.feed(rssi = -70)
        harness.waitUntil("标记后仍有一条覆盖观测") {
            harness.observations(sessionId).size == 3
        }
        assertEquals(
            SessionLifecycleStatus.RECORDING,
            harness.session(sessionId)?.status,
        )

        // 不执行正常 stop，直接以新容器重开同一数据库，模拟进程终止后启动恢复。
        harness.restartContainer()
        harness.waitUntil("启动恢复把遗留采集中 Session 标为中断") {
            harness.session(sessionId)?.status == SessionLifecycleStatus.INTERRUPTED &&
                (harness.session(sessionId)?.interruptedDetectedAtEpochMillis ?: 0L) > 0L
        }
        val recovered = requireNotNull(harness.session(sessionId))
        assertNull(recovered.endedAtEpochMillis)
        assertEquals(3, harness.observations(sessionId).size)
        assertEquals(1, harness.markers(sessionId).size)

        val reopenedViewModel = harness.samplingViewModel()
        reopenedViewModel.openSession(sessionId)
        harness.waitUntil("中断 Session 详情可打开") {
            reopenedViewModel.uiState.value.detail?.session?.id == sessionId
        }
        val detail = requireNotNull(reopenedViewModel.uiState.value.detail)
        assertEquals(SessionLifecycleStatus.INTERRUPTED, detail.session.status)
        assertEquals(3, detail.observationCount)
        assertEquals(1, detail.markers.size)
        assertNotNull(detail.session.interruptedDetectedAtEpochMillis)

        val comparisonResult = harness.container.sessionComparisonService.compare(
            sessionId = sessionId,
            targets = listOf(
                ComparisonTarget.Config(BuiltInAlgorithmConfigs.BASELINE.configId),
            ),
            window = ScoringWindow(),
        )
        assertTrue(comparisonResult is SessionComparisonResult.Completed)
        val trial = (comparisonResult as SessionComparisonResult.Completed)
            .comparison.trials.single()
        assertTrue(trial.replay.coverage.interrupted)
        assertEquals(TrialOutcome.INSUFFICIENT_OBSERVATION, trial.score.outcome)
        assertFalse(trial.score.includedInSummary)
        assertTrue(trial.score.exclusionReason.orEmpty().contains("观测不足"))
    }

    @Test
    fun unavailableNotificationKeepsEventAndReplayNeverReachesFeedback() = runBlocking {
        harness.alarm.capabilities = SimulationAlertCapabilities(
            notificationPermissionRequired = true,
            notificationPermissionGranted = false,
            notificationsEnabled = false,
            vibrationAvailable = true,
        )
        harness.bindThroughDeviceEntry()
        selectStableConfig("通知稳定性配置")
        val monitorViewModel = harness.monitorViewModel()
        val sessionId = startSimulation(monitorViewModel)

        harness.feed(rssi = -88)
        harness.feed(rssi = -88)
        harness.waitUntil("确认 FAR") {
            harness.container.liveMonitorController.state.value.test?.decision?.state ==
                ProximityState.FAR
        }
        harness.feed(rssi = -55)
        harness.feed(rssi = -55)
        harness.waitUntil("通知不可用时事件仍入库") {
            harness.events(sessionId).size == 1
        }
        harness.waitUntil("通知不可用结果单独写回") {
            harness.events(sessionId).single().feedbackStatus ==
                SimulatedEventFeedbackStatus.UNAVAILABLE
        }
        val event = harness.events(sessionId).single()
        assertEquals(ProximityEventReason.CONFIRMED_NEAR_AFTER_CONFIRMED_FAR, event.reason)
        assertEquals(-55, event.rawRssiDbm)
        assertTrue(harness.alarm.attempts.isNotEmpty())
        assertTrue("通知不可用时不能记录为已提交", harness.alarm.deliveries.isEmpty())

        monitorViewModel.stopSimulation()
        harness.waitUntil("通知不可用测试结束") {
            harness.session(sessionId)?.status == SessionLifecycleStatus.COMPLETED
        }

        val attemptsBeforeReplay = harness.alarm.attempts.size
        val eventsBeforeReplay = harness.events(sessionId)
        val replayResult = harness.container.sessionReplayService.replay(
            sessionId = sessionId,
            request = ReplayConfigRequest.OriginalSnapshot,
        )
        assertTrue(replayResult is SessionReplayResult.Completed)
        assertEquals(
            1,
            (replayResult as SessionReplayResult.Completed).record.events.size,
        )
        assertEquals("回放不能触发实时通知", attemptsBeforeReplay, harness.alarm.attempts.size)
        assertEquals(eventsBeforeReplay, harness.events(sessionId))
        assertNotEquals(
            SimulatedEventFeedbackStatus.NOT_ATTEMPTED,
            harness.events(sessionId).single().feedbackStatus,
        )
    }

    /** 非过期场景使用长样本有效期，避免模拟器高负载下正常处理间隔被误判为过期。 */
    private suspend fun selectStableConfig(name: String) {
        val config = harness.container.algorithmConfigStore.createConfig(
            name = name,
            config = ProximityConfig(
                algorithm = ProximityAlgorithm.BASELINE,
                enterThresholdDbm = -60,
                exitThresholdDbm = -75,
                confirmation = ConfirmationRequirement.ConsecutiveSamples(minimumSamples = 2),
                sampleValidityMillis = 60_000L,
                emaAlpha = 0.5,
            ),
        )
        assertTrue(harness.container.algorithmConfigStore.selectConfig(config.configId))
    }

    private suspend fun startSimulation(monitorViewModel: MonitorViewModel): String {
        monitorViewModel.startSimulationNow()
        harness.waitUntil("模拟测试开始") {
            val test = harness.container.liveMonitorController.state.value.test
            test != null &&
                test.running &&
                harness.session(test.session.id)?.status == SessionLifecycleStatus.RECORDING
        }
        return requireNotNull(harness.container.liveMonitorController.state.value.test).session.id
    }

}
