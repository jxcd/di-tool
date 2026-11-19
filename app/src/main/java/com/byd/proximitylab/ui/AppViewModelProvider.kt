package com.byd.proximitylab.ui

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.byd.proximitylab.algorithmconfig.AlgorithmConfigStore
import com.byd.proximitylab.comparison.SessionComparisonService
import com.byd.proximitylab.feedback.ForegroundSessionCoordinator
import com.byd.proximitylab.feedback.SimulationAlarm
import com.byd.proximitylab.live.LiveMonitorController
import com.byd.proximitylab.replay.SessionReplayService
import com.byd.proximitylab.session.SessionMarkerRecorder
import com.byd.proximitylab.session.SessionRecorder
import com.byd.proximitylab.session.SessionStore
import com.byd.proximitylab.simulation.SimulationRunStore
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

    fun labFactory(
        store: AlgorithmConfigStore,
        comparisonService: SessionComparisonService? = null,
    ): ViewModelProvider.Factory =
        viewModelFactory {
            initializer { AlgorithmLabViewModel(store, comparisonService) }
        }

    fun monitorFactory(
        controller: LiveMonitorController,
        configStore: AlgorithmConfigStore? = null,
        sessionStore: SessionStore? = null,
        simulationStore: SimulationRunStore? = null,
        markerRecorder: SessionMarkerRecorder? = null,
        simulationAlarm: SimulationAlarm? = null,
        foregroundCoordinator: ForegroundSessionCoordinator? = null,
    ): ViewModelProvider.Factory =
        viewModelFactory {
            initializer {
                MonitorViewModel(
                    controller,
                    configStore,
                    sessionStore,
                    simulationStore,
                    markerRecorder,
                    simulationAlarm,
                    foregroundCoordinator,
                )
            }
        }

    fun deviceFactory(monitor: TargetMonitor): ViewModelProvider.Factory = viewModelFactory {
        initializer { DeviceViewModel(monitor) }
    }

    fun samplingFactory(
        recorder: SessionRecorder,
        store: SessionStore,
        observationSource: TargetObservationSource,
        markerRecorder: SessionMarkerRecorder,
        simulationStore: SimulationRunStore? = null,
        replayService: SessionReplayService? = null,
    ): ViewModelProvider.Factory = viewModelFactory {
        initializer {
            SamplingViewModel(
                recorder = recorder,
                store = store,
                observationSource = observationSource,
                markerRecorder = markerRecorder,
                simulationStore = simulationStore,
                replayService = replayService,
            )
        }
    }
}
