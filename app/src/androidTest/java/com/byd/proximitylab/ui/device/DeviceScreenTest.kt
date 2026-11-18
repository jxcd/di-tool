package com.byd.proximitylab.ui.device

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.byd.proximitylab.ble.BydCandidateClassifier
import com.byd.proximitylab.ble.ManufacturerDataRecord
import com.byd.proximitylab.ble.ScanAvailability
import com.byd.proximitylab.data.ProximityLabDatabase
import com.byd.proximitylab.target.AssociationBasis
import com.byd.proximitylab.target.IdentityStatus
import com.byd.proximitylab.target.IdentityUncertaintyReason
import com.byd.proximitylab.target.TargetMonitor
import com.byd.proximitylab.target.TargetMonitorState
import com.byd.proximitylab.target.data.RoomTargetBindingStore
import com.byd.proximitylab.testing.ControllableBleScanSource
import com.byd.proximitylab.testing.testAdvertisement
import com.byd.proximitylab.testing.testBinding
import com.byd.proximitylab.ui.theme.ProximityLabTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DeviceScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var database: ProximityLabDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var source: ControllableBleScanSource
    private lateinit var store: RoomTargetBindingStore
    private lateinit var viewModel: DeviceViewModel

    private val addressA = "AA:BB:CC:DD:EE:01"
    private val addressB = "AA:BB:CC:DD:EE:02"
    private val addressC = "AA:BB:CC:DD:EE:03"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "device-screen-test-${UUID.randomUUID()}.db"
        openMonitor()
    }

    @After
    fun tearDown() {
        cancelMonitorScope()
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun scanShowsEmptyPromptAndRendersGenericAndBydCandidatesWithUnknownFields() {
        setContent()

        composeRule.onNodeWithTag("device_scan_start").performClick()
        waitFor { it.scanState is ScanAvailability.Scanning }

        composeRule.onNodeWithTag("device_scan_state").assertTextContains("扫描中")
        composeRule.onNodeWithTag("device_candidates_empty").assertIsDisplayed()

        assertTrue(
            source.emit(
                testAdvertisement(
                    address = addressA,
                    name = null,
                    rssi = -72,
                    serviceUuids = emptyList(),
                    manufacturerData = emptyList(),
                ),
            ),
        )
        waitFor { it.candidates.size == 1 }

        composeRule.onNodeWithTag("device_candidate_$addressA")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("名称未知").assertExists()
        composeRule.onNodeWithText("Service UUID：未知").assertExists()
        composeRule.onNodeWithText("Manufacturer Data：未知").assertExists()
        composeRule.onNodeWithText("通用 BLE 设备").assertExists()
        composeRule.onNodeWithTag("device_bind_$addressA").assertExists()

        // 通用设备（名称缺失）也可以人工确认并绑定。
        composeRule.onNodeWithTag("device_bind_$addressA")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("device_action_confirm").performClick()
        waitFor { it.binding?.address == addressA }
        val genericTargetId = viewModel.uiState.value.binding?.targetId.orEmpty()
        assertTrue(genericTargetId.isNotBlank())
        composeRule.onNodeWithTag("device_binding_target_id")
            .performScrollTo()
            .assertTextContains(genericTargetId, substring = true)

        assertTrue(source.emit(bydAdvertisement(addressB, rssi = -50)))
        waitFor { it.candidates.size == 2 }

        composeRule.onNodeWithTag("device_candidate_byd_$addressB")
            .performScrollTo()
            .assertTextContains("比亚迪候选", substring = true)
        composeRule.onNodeWithText(
            "Service UUID：${BydCandidateClassifier.CLASSIC_SERVICE_UUID}",
            substring = true,
        ).assertExists()
        composeRule.onNodeWithText("Manufacturer Data：0x3494=DEADBEEF", substring = true)
            .assertExists()

        assertTrue(
            source.emit(
                testAdvertisement(
                    address = null,
                    name = null,
                    payloadFingerprint = "no-address-fingerprint",
                ),
            ),
        )
        waitFor { it.candidates.size == 3 }
        composeRule.onNodeWithText("地址：地址不可取得")
            .performScrollTo()
            .assertExists()
        composeRule.onNodeWithText("地址不可取得，无法绑定或关联。")
            .performScrollTo()
            .assertExists()
    }

    @Test
    fun conservativeAssociationKeepsBindingAndManualActionsKeepOrReplaceTargetId() {
        runBlocking {
            store.saveBinding(
                testBinding(
                    targetId = "target-A",
                    address = addressA,
                    displayName = "Bound target A",
                ),
            )
        }
        setContent()
        waitFor { it.binding?.targetId == "target-A" }

        composeRule.onNodeWithTag("device_binding_target_id")
            .assertTextContains("target-A", substring = true)

        composeRule.onNodeWithTag("device_scan_start").performClick()
        waitFor { it.scanState is ScanAvailability.Scanning }

        source.emit(bydAdvertisement(addressA, rssi = -70))
        waitFor { it.targetObservedInCurrentScan }
        composeRule.onNodeWithTag("device_identity_status").assertTextContains("身份已确认")

        // 另一候选更强且同品牌，但原绑定仍被观测：保留原绑定，只接受 A。
        source.emit(bydAdvertisement(addressB, rssi = -25))
        waitFor { it.candidates.size == 2 }
        composeRule.waitForIdle()
        val whileBothVisible = viewModel.uiState.value
        assertEquals(addressA, whileBothVisible.binding?.address)
        assertEquals(IdentityStatus.CONFIRMED, whileBothVisible.identity.status)
        assertEquals(addressA, whileBothVisible.lastTargetObservation?.advertisement?.address)
        assertEquals(1, whileBothVisible.targetObservationCount)
        assertEquals(listOf(addressB), whileBothVisible.identity.pendingCandidateAddresses)
        composeRule.onNodeWithTag("device_binding_address")
            .assertTextContains(addressA, substring = true)
        composeRule.onNodeWithTag("device_identity_detail")
            .assertTextContains(addressB, substring = true)

        // 重开扫描，清空候选后只出现 B：原地址消失且新地址无可靠证据。
        composeRule.onNodeWithTag("device_scan_stop").performClick()
        waitFor { it.scanState is ScanAvailability.Stopped }
        composeRule.onNodeWithTag("device_scan_start").performClick()
        waitFor { it.scanState is ScanAvailability.Scanning && it.candidates.isEmpty() }

        // 新扫描已清空候选，不能继续展示上一会话的待确认候选和匹配依据。
        val afterRestart = viewModel.uiState.value
        assertEquals(AssociationBasis.PERSISTED_BINDING, afterRestart.identity.basis)
        assertTrue(afterRestart.identity.pendingCandidateAddresses.isEmpty())
        composeRule.onNodeWithTag("device_identity_detail")
            .assertTextContains("本地已保存绑定", substring = true)

        source.emit(bydAdvertisement(addressB, rssi = -25))
        waitFor { it.identity.status == IdentityStatus.UNCERTAIN }

        val uncertain = viewModel.uiState.value
        assertEquals("target-A", uncertain.binding?.targetId)
        assertEquals(addressA, uncertain.binding?.address)
        assertEquals(
            IdentityUncertaintyReason.NO_VERIFIED_IDENTITY_EVIDENCE,
            uncertain.identity.uncertainty?.reason,
        )
        assertEquals(0, uncertain.targetObservationCount)
        composeRule.onNodeWithTag("device_identity_status").assertTextContains("身份不确定")
        composeRule.onNodeWithTag("device_identity_detail")
            .assertTextContains("缺少可复验", substring = true)
        composeRule.onNodeWithTag("device_binding_address")
            .assertTextContains(addressA, substring = true)

        // 重新开始扫描不应在没有新证据时清除身份不确定。
        composeRule.onNodeWithTag("device_scan_stop").performClick()
        waitFor { it.scanState is ScanAvailability.Stopped }
        composeRule.onNodeWithTag("device_scan_start").performClick()
        waitFor { it.scanState is ScanAvailability.Scanning && it.candidates.isEmpty() }
        assertEquals(IdentityStatus.UNCERTAIN, viewModel.uiState.value.identity.status)
        assertEquals(0, viewModel.uiState.value.targetObservationCount)
        assertNull(viewModel.uiState.value.lastTargetObservation)
        assertTrue(viewModel.uiState.value.identity.pendingCandidateAddresses.isEmpty())
        composeRule.onNodeWithTag("device_identity_status").assertTextContains("身份不确定")
        composeRule.onNodeWithTag("device_identity_detail")
            .assertTextContains("扫描重新开始", substring = true)

        source.emit(bydAdvertisement(addressB, rssi = -25))
        waitFor { state -> state.candidates.any { it.address == addressB } }

        // 人工确认为原设备：保留 target-A。
        composeRule.onNodeWithTag("device_reconfirm_$addressB")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("device_action_confirm").performClick()
        waitFor { it.binding?.address == addressB && it.identity.basis == AssociationBasis.USER_RECONFIRMATION }

        val reconfirmed = viewModel.uiState.value
        assertEquals("target-A", reconfirmed.binding?.targetId)
        assertEquals(addressB, reconfirmed.binding?.address)
        composeRule.onNodeWithTag("device_binding_target_id")
            .assertTextContains("target-A", substring = true)

        // 人工改绑：建立新的目标标识。
        source.emit(testAdvertisement(address = addressC, name = "New target"))
        waitFor { state -> state.candidates.any { it.address == addressC } }
        composeRule.onNodeWithTag("device_rebind_$addressC")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("device_action_confirm").performClick()
        waitFor { it.binding?.address == addressC }

        val rebound = viewModel.uiState.value
        assertNotEquals("target-A", rebound.binding?.targetId)
        assertEquals(addressC, rebound.binding?.address)
        assertEquals(AssociationBasis.USER_REBIND, rebound.identity.basis)
        assertEquals(0, rebound.targetObservationCount)
        assertNull(rebound.lastTargetObservation)
        composeRule.onNodeWithTag("device_target_presence")
            .assertTextContains("尚未收到绑定目标的观测", substring = true)
    }

    @Test
    fun permissionBluetoothErrorAndTargetNotSeenHaveDistinctOutputs() {
        runBlocking {
            store.saveBinding(testBinding(targetId = "target-A", address = addressA))
        }
        setContent()
        waitFor { it.binding?.targetId == "target-A" }

        source.setScanState(ScanAvailability.PermissionMissing(revoked = false))
        waitFor { it.scanState is ScanAvailability.PermissionMissing }
        composeRule.onNodeWithTag("device_scan_state").assertTextContains("缺少扫描权限")
        composeRule.onNodeWithTag("device_scan_grant").assertExists()

        source.setScanState(ScanAvailability.PermissionMissing(revoked = true))
        waitFor { (it.scanState as? ScanAvailability.PermissionMissing)?.revoked == true }
        composeRule.onNodeWithTag("device_scan_state").assertTextContains("扫描权限已被撤销")
        composeRule.onNodeWithTag("device_scan_detail").assertTextContains("重新授予", substring = true)
        composeRule.onNodeWithTag("device_target_presence").assertTextContains("扫描不可用", substring = true)

        source.setScanState(ScanAvailability.BluetoothOff)
        waitFor { it.scanState is ScanAvailability.BluetoothOff }
        composeRule.onNodeWithTag("device_scan_state").assertTextContains("蓝牙已关闭")
        composeRule.onNodeWithTag("device_target_presence").assertTextContains("扫描不可用", substring = true)

        source.setScanState(ScanAvailability.Failed("测试扫描错误"))
        waitFor { it.scanState is ScanAvailability.Failed }
        composeRule.onNodeWithTag("device_scan_state").assertTextContains("扫描失败")
        composeRule.onNodeWithTag("device_scan_detail").assertTextContains("测试扫描错误", substring = true)

        composeRule.onNodeWithTag("device_scan_start").performClick()
        waitFor { it.scanState is ScanAvailability.Scanning }
        composeRule.onNodeWithTag("device_scan_state").assertTextContains("扫描中")
        composeRule.onNodeWithTag("device_scan_detail")
            .assertTextContains("持续没有回调只表示缺少观测", substring = true)
        composeRule.onNodeWithText("没有扫描回调不能证明车辆没有广播", substring = true)
            .assertExists()
        composeRule.onNodeWithTag("device_target_presence")
            .assertTextContains("尚未收到绑定目标的观测", substring = true)
        assertEquals(0, viewModel.uiState.value.targetObservationCount)
    }

    @Test
    fun savedBindingIsReadableAfterDatabaseReopenAndShownOnDevicePage() {
        runBlocking {
            store.saveBinding(
                testBinding(
                    targetId = "persisted-target",
                    address = addressA,
                    displayName = "Persisted target",
                ),
            )
        }

        reopenDatabaseAndMonitor()
        setContent()
        waitFor { it.binding?.targetId == "persisted-target" }

        composeRule.onNodeWithTag("device_binding_target_id")
            .assertTextContains("persisted-target", substring = true)
        composeRule.onNodeWithTag("device_binding_address")
            .assertTextContains(addressA, substring = true)
        composeRule.onNodeWithText("Persisted target").assertExists()
    }

    private fun openMonitor() {
        database = Room.databaseBuilder(
            context,
            ProximityLabDatabase::class.java,
            databaseName,
        ).build()
        store = RoomTargetBindingStore(database.targetBindingDao())
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        source = ControllableBleScanSource()
        val monitor = TargetMonitor(
            scanSource = source,
            bindingStore = store,
            scope = scope,
        )
        viewModel = DeviceViewModel(monitor)
    }

    private fun reopenDatabaseAndMonitor() {
        cancelMonitorScope()
        database.close()
        openMonitor()
    }

    private fun cancelMonitorScope() {
        runBlocking {
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    private fun setContent() {
        composeRule.setContent {
            ProximityLabTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DeviceScreen(viewModel = viewModel)
                }
            }
        }
    }

    private fun waitFor(predicate: (TargetMonitorState) -> Boolean) {
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            predicate(viewModel.uiState.value)
        }
        composeRule.waitForIdle()
    }

    private fun bydAdvertisement(address: String, rssi: Int) = testAdvertisement(
        address = address,
        name = "BYD BLE3 candidate",
        rssi = rssi,
        serviceUuids = listOf(BydCandidateClassifier.CLASSIC_SERVICE_UUID),
        manufacturerData = listOf(
            ManufacturerDataRecord(
                companyId = BydCandidateClassifier.BYD_MANUFACTURER_ID,
                payloadHex = "DEADBEEF",
            ),
        ),
    )
}
