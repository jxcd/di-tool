package com.byd.proximitylab

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.byd.proximitylab.ble.AndroidBleScanSource
import com.byd.proximitylab.ble.BleScanSource
import com.byd.proximitylab.data.ProximityLabDatabase
import com.byd.proximitylab.target.NoVerifiedIdentityEvidence
import com.byd.proximitylab.target.TargetAssociationPolicy
import com.byd.proximitylab.target.TargetBindingStore
import com.byd.proximitylab.target.TargetMonitor
import com.byd.proximitylab.target.data.RoomTargetBindingStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class ProximityLabApplication : Application() {

    val container: AppContainer by lazy { AppContainer(this) }
}

/**
 * 普通构造器接线。04/05/11 复用同一 [TargetMonitor] 和扫描来源，不引入 DI 框架。
 *
 * 04 的 Session 存储应扩展 [ProximityLabDatabase] 并做迁移，不新建第二个 Room 数据库。
 */
class AppContainer(context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val database: ProximityLabDatabase = Room.databaseBuilder(
        context.applicationContext,
        ProximityLabDatabase::class.java,
        ProximityLabDatabase.NAME,
    ).build()

    val bindingStore: TargetBindingStore = RoomTargetBindingStore(database.targetBindingDao())

    val scanSource: BleScanSource = AndroidBleScanSource(context.applicationContext)

    /**
     * 生产环境暂不提供可复验身份证据：真实车辆地址轮换的唯一依据留给票据 14。
     * 因此地址变化走保守分支，保留原绑定并报告身份不确定。
     */
    val targetMonitor: TargetMonitor = TargetMonitor(
        scanSource = scanSource,
        bindingStore = bindingStore,
        scope = scope,
        policy = TargetAssociationPolicy(NoVerifiedIdentityEvidence),
    )
}
