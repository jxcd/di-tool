package com.byd.proximitylab.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.byd.proximitylab.ui.device.DeviceScreen
import com.byd.proximitylab.ui.lab.AlgorithmLabScreen
import com.byd.proximitylab.ui.monitor.MonitorScreen
import com.byd.proximitylab.ui.navigation.LabDestination
import com.byd.proximitylab.ui.sampling.SamplingScreen

@Composable
fun ProximityLabApp(modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route
    val currentDestination = LabDestination.entries
        .firstOrNull { destination -> destination.route == currentRoute }
        ?: LabDestination.start

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            NavigationBar {
                LabDestination.entries.forEach { destination ->
                    NavigationBarItem(
                        modifier = Modifier.testTag(destination.navTestTag),
                        selected = destination == currentDestination,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = when (destination) {
                                    LabDestination.Device -> Icons.Filled.Search
                                    LabDestination.Monitor -> Icons.Filled.PlayArrow
                                    LabDestination.Sampling -> Icons.Filled.Add
                                    LabDestination.Lab -> Icons.Filled.Settings
                                },
                                contentDescription = null,
                            )
                        },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = LabDestination.start.route,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            composable(LabDestination.Device.route) { DeviceScreen() }
            composable(LabDestination.Monitor.route) { MonitorScreen() }
            composable(LabDestination.Sampling.route) { SamplingScreen() }
            composable(LabDestination.Lab.route) { AlgorithmLabScreen() }
        }
    }
}
