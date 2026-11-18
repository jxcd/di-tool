package com.byd.proximitylab.ui

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.byd.proximitylab.session.SessionRecorder
import com.byd.proximitylab.session.SessionStore
import com.byd.proximitylab.target.TargetMonitor
import com.byd.proximitylab.target.TargetObservationSource
import com.byd.proximitylab.ui.device.DeviceViewModel
import com.byd.proximitylab.ui.lab.AlgorithmLabViewModel
import com.byd.proximitylab.ui.monitor.MonitorViewModel
import com.byd.proximitylab.ui.sampling.SamplingViewModel

/**
 * 普通构造器接线的 ViewModel 工厂。后续票在这里为页面补充依赖，不引入 DI 框架。
 */
object AppViewModelProvider {

    val Factory: ViewModelProvider.Factory = viewModelFactory {
        initializer { MonitorViewModel() }
        initializer { AlgorithmLabViewModel() }
    }

    fun deviceFactory(monitor: TargetMonitor): ViewModelProvider.Factory = viewModelFactory {
        initializer { DeviceViewModel(monitor) }
    }

    fun samplingFactory(
        recorder: SessionRecorder,
        store: SessionStore,
        observationSource: TargetObservationSource,
    ): ViewModelProvider.Factory = viewModelFactory {
        initializer { SamplingViewModel(recorder, store, observationSource) }
    }
}
