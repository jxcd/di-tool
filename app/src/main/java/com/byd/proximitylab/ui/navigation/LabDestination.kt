package com.byd.proximitylab.ui.navigation

/**
 * App 的四个顶层任务入口。后续票沿用这里的路由、文案和测试标识。
 */
enum class LabDestination(
    val route: String,
    val label: String,
    val headline: String,
    val description: String,
    val pendingWork: String,
) {
    Device(
        route = "device",
        label = "设备",
        headline = "设备",
        description = "扫描附近的 BLE 设备，确认需要监测的目标。",
        pendingWork = "只读扫描、候选展示与人工绑定由票据 02 接入。",
    ),
    Monitor(
        route = "monitor",
        label = "实时监控",
        headline = "实时监控",
        description = "查看目标观测、原始与滤波 RSSI、接近状态和模拟事件。",
        pendingWork = "目标观测与实时曲线由票据 05、07 接入。",
    ),
    Sampling(
        route = "sampling",
        label = "采样",
        headline = "采样",
        description = "记录定点与行走采样的原始观测，并重新打开已保存的 Session。",
        pendingWork = "采样生命周期与本地 Session 存储由票据 04、08 接入。",
    ),
    Lab(
        route = "lab",
        label = "算法实验室",
        headline = "算法实验室",
        description = "管理算法配置，用历史 Session 回放并比较触发效果。",
        pendingWork = "算法配置、回放与对比由票据 03、06、09、10 接入。",
    ),
    ;

    val screenTestTag: String
        get() = "screen_$route"

    val navTestTag: String
        get() = "nav_$route"

    companion object {
        val start: LabDestination = Device
    }
}
