package com.byd.proximitylab.target.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.byd.proximitylab.data.ProximityLabDatabase
import com.byd.proximitylab.target.BoundTarget
import com.byd.proximitylab.target.TargetAction
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TargetBindingRoomStoreTest {

    private lateinit var context: Context
    private lateinit var databaseName: String
    private var database: ProximityLabDatabase? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "target-binding-test-${UUID.randomUUID()}.db"
    }

    @After
    fun tearDown() {
        database?.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun savedBindingIsReadableAfterDatabaseReopen() = runBlocking {
        val binding = BoundTarget(
            targetId = "persisted-target",
            displayName = "BYD candidate",
            address = "AA:BB:CC:DD:EE:02",
            boundAtEpochMillis = 1_800_000_000_000L,
            verifiedIdentityToken = "verified-token",
            verifiedIdentityDescription = "测试证据",
            lastAction = TargetAction.ADDRESS_CONTINUED,
            lastActionAtEpochMillis = 1_800_000_100_000L,
        )

        var store = RoomTargetBindingStore(openDatabase().targetBindingDao())
        store.saveBinding(binding)
        database?.close()

        store = RoomTargetBindingStore(openDatabase().targetBindingDao())
        assertEquals(binding, store.loadBinding())

        store.clearBinding()
        assertNull(store.loadBinding())
    }

    @Test
    fun onlyOneBindingRowIsKeptWhenRebinding() = runBlocking {
        var store = RoomTargetBindingStore(openDatabase().targetBindingDao())
        store.saveBinding(
            BoundTarget(
                targetId = "first",
                displayName = "First",
                address = "AA:BB:CC:DD:EE:01",
                boundAtEpochMillis = 1L,
                verifiedIdentityToken = null,
                verifiedIdentityDescription = null,
                lastAction = TargetAction.BOUND,
                lastActionAtEpochMillis = 1L,
            ),
        )
        store.saveBinding(
            BoundTarget(
                targetId = "second",
                displayName = "Second",
                address = "AA:BB:CC:DD:EE:02",
                boundAtEpochMillis = 2L,
                verifiedIdentityToken = null,
                verifiedIdentityDescription = null,
                lastAction = TargetAction.REBOUND,
                lastActionAtEpochMillis = 2L,
            ),
        )

        assertEquals("second", store.loadBinding()?.targetId)
        assertEquals("AA:BB:CC:DD:EE:02", store.loadBinding()?.address)
    }

    private fun openDatabase(): ProximityLabDatabase {
        val opened = Room.databaseBuilder(
            context,
            ProximityLabDatabase::class.java,
            databaseName,
        ).build()
        database?.close()
        database = opened
        return opened
    }
}
