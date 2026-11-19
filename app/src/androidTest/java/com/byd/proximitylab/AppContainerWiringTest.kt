package com.byd.proximitylab

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.byd.proximitylab.ble.ScanPermissionChecker
import com.byd.proximitylab.data.ProximityLabDatabase
import com.byd.proximitylab.session.FixedSampleLocation
import com.byd.proximitylab.session.PhonePlacement
import com.byd.proximitylab.session.SamplingSession
import com.byd.proximitylab.session.SamplingType
import com.byd.proximitylab.session.SessionLifecycleStatus
import com.byd.proximitylab.session.SessionStartRequest
import com.byd.proximitylab.session.SessionStartResult
import com.byd.proximitylab.session.SessionStopResult
import com.byd.proximitylab.session.SessionTimelineEvent
import com.byd.proximitylab.session.data.RoomSessionStore
import com.byd.proximitylab.target.data.RoomTargetBindingStore
import com.byd.proximitylab.testing.ControllableBleScanSource
import com.byd.proximitylab.testing.RecordingForegroundSessionLauncher
import com.byd.proximitylab.testing.RecordingSimulationAlarm
import com.byd.proximitylab.testing.testAdvertisement
import com.byd.proximitylab.testing.testBinding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * 通过独立 [AppContainer] 验证生产 wiring：启动恢复真正执行，store/recorder/monitor 串联可用。
 */
@RunWith(AndroidJUnit4::class)
class AppContainerWiringTest {

    private lateinit var context: Context
    private lateinit var databaseName: String
    private var container: AppContainer? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "app-container-wiring-${UUID.randomUUID()}.db"
    }

    @After
    fun tearDown() {
        container?.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun startupRecoveryAndRecorderUseTheSameContainerWiring() {
        seedRecordingSession()
        val source = ControllableBleScanSource()
        val appContainer = AppContainer(
            context = context,
            databaseName = databaseName,
            scanSource = source,
            // 本用例验证容器接线；平台权限与服务/通知由 11 的专门测试覆盖。
            scanPermissionChecker = ScanPermissionChecker.ALWAYS_GRANTED,
            foregroundSessionLauncher = RecordingForegroundSessionLauncher(),
            simulationAlarm = RecordingSimulationAlarm(),
        )
        container = appContainer

        waitUntil(TIMEOUT_MILLIS) {
            runBlocking { appContainer.sessionStore.loadSession(RECOVERY_SESSION_ID)?.status } ==
                SessionLifecycleStatus.INTERRUPTED
        }
        val recovered = requireNotNull(runBlocking { appContainer.sessionStore.loadSession(RECOVERY_SESSION_ID) })
        assertNull(recovered.endedAtEpochMillis)
        assertEquals(2_000L, recovered.lastRecordedAtEpochMillis)
        assertTrue((recovered.interruptedDetectedAtEpochMillis ?: 0L) > 0L)
        assertEquals(1, runBlocking { appContainer.sessionStore.loadTimeline(RECOVERY_SESSION_ID) }.size)

        val binding = testBinding(
            targetId = TARGET_ID,
            address = TARGET_ADDRESS,
            displayName = TARGET_NAME,
        )
        waitUntil(TIMEOUT_MILLIS) {
            appContainer.targetMonitor.state.value.binding?.targetId == TARGET_ID
        }
        val started = runBlocking {
            appContainer.sessionRecorder.start(
                SessionStartRequest(
                    binding = binding,
                    samplingType = SamplingType.FIXED_POINT,
                    name = "容器接线采样",
                    location = FixedSampleLocation.REAR,
                    estimatedDistanceMeters = null,
                    phonePlacement = PhonePlacement.HANDHELD,
                ),
            )
        }
        assertTrue(started is SessionStartResult.Started)
        assertEquals(1, source.startCount)
        val sessionId = (started as SessionStartResult.Started).session.id

        assertTrue(
            source.emit(
                testAdvertisement(
                    address = TARGET_ADDRESS,
                    rssi = -69,
                    deliverySequence = 1L,
                    payloadFingerprint = "container-wiring",
                ),
            ),
        )
        waitUntil(TIMEOUT_MILLIS) {
            runBlocking { appContainer.sessionStore.loadTimeline(sessionId) }
                .count { item -> item.event is SessionTimelineEvent.Observation } == 1
        }

        val stopped = runBlocking { appContainer.sessionRecorder.stop() }
        assertTrue(stopped is SessionStopResult.Stopped)
        assertEquals(1, source.stopCount)
        val completed = runBlocking { appContainer.sessionStore.loadSession(sessionId) }
        requireNotNull(completed)
        assertEquals(SessionLifecycleStatus.COMPLETED, completed.status)
        assertEquals(TARGET_ID, completed.targetId)
        assertEquals(
            1,
            runBlocking { appContainer.sessionStore.loadTimeline(sessionId) }
                .count { item -> item.event is SessionTimelineEvent.Observation },
        )
    }

    private fun seedRecordingSession() {
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
        try {
            runBlocking {
                RoomTargetBindingStore(database.targetBindingDao()).saveBinding(
                    testBinding(
                        targetId = TARGET_ID,
                        address = TARGET_ADDRESS,
                        displayName = TARGET_NAME,
                    ),
                )
                val store = RoomSessionStore(database.samplingSessionDao())
                store.createSession(
                    SamplingSession(
                        id = RECOVERY_SESSION_ID,
                        targetId = TARGET_ID,
                        targetDisplayName = TARGET_NAME,
                        targetAddress = TARGET_ADDRESS,
                        samplingType = SamplingType.FIXED_POINT,
                        name = "遗留采集中",
                        location = FixedSampleLocation.FRONT,
                        estimatedDistanceMeters = null,
                        phonePlacement = PhonePlacement.HANDHELD,
                        status = SessionLifecycleStatus.RECORDING,
                        startedAtEpochMillis = 1_000L,
                        endedAtEpochMillis = null,
                        lastRecordedAtEpochMillis = 1_000L,
                        interruptedDetectedAtEpochMillis = null,
                    ),
                )
                store.appendTimeline(
                    RECOVERY_SESSION_ID,
                    SessionTimelineEvent.Observation(
                        observationId = "recovery-observation",
                        targetId = TARGET_ID,
                        rawRssiDbm = -74,
                        observedAtEpochMillis = 1_900L,
                        receivedAtEpochMillis = 1_950L,
                        scanFrameId = 11L,
                        deliverySequence = 11L,
                        address = TARGET_ADDRESS,
                        payloadFingerprint = "recovery",
                        processedAtEpochMillis = 2_000L,
                    ),
                )
            }
        } finally {
            database.close()
        }
    }

    private fun waitUntil(timeoutMillis: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) {
                return
            }
            Thread.sleep(50L)
        }
        assertTrue("等待条件超时（${timeoutMillis}ms）", condition())
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
        const val RECOVERY_SESSION_ID = "recovery-session"
        const val TARGET_ID = "container-wiring-target"
        const val TARGET_ADDRESS = "AA:BB:CC:DD:EE:88"
        const val TARGET_NAME = "容器接线目标"
    }
}
