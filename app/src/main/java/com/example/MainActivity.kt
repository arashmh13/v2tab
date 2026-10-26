package com.example

import android.app.Activity
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.AppDatabase
import com.example.data.BalancerRepository
import com.example.ui.BalancerViewModel
import com.example.ui.screens.CoreLogsScreen
import com.example.ui.screens.MainTabScreen
import com.example.ui.theme.CyberBackground
import com.example.ui.theme.CyberSurface
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.ElectricCyanDim
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

enum class V2TabDestination(
    val route: String,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    MAIN("main", R.string.nav_main, Icons.Filled.PowerSettingsNew, Icons.Outlined.PowerSettingsNew),
    LOGS("logs", R.string.nav_logs, Icons.Filled.Terminal, Icons.Outlined.Terminal)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val database = AppDatabase.getInstance(applicationContext)
        val repository = BalancerRepository(database.dao(), applicationContext)

        setContent {
            MyApplicationTheme {
                val vm: BalancerViewModel = viewModel(
                    factory = BalancerViewModel.Factory(repository, applicationContext)
                )
                V2TabApp(viewModel = vm)
            }
        }
    }
}

@Composable
fun V2TabApp(
    viewModel: BalancerViewModel
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()

    var currentDestination by rememberSaveable { mutableStateOf(V2TabDestination.MAIN) }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.startBalancerTunnel(context)
        } else {
            // Even if VPN dialog is dismissed on an emulator without TUN support, start local SOCKS5/HTTP balancer
            viewModel.startBalancerTunnel(context)
        }
    }

    val handleToggleConnect = {
        if (uiState.isTunnelConnected) {
            viewModel.stopBalancerTunnel(context)
        } else {
            val prepareIntent = try {
                VpnService.prepare(context)
            } catch (_: Exception) {
                null
            }
            if (prepareIntent != null) {
                try {
                    vpnPermissionLauncher.launch(prepareIntent)
                } catch (_: Exception) {
                    viewModel.startBalancerTunnel(context)
                }
            } else {
                viewModel.startBalancerTunnel(context)
            }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isExpandedScreen = maxWidth >= 680.dp

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets.safeDrawing,
            containerColor = CyberBackground,
            bottomBar = {
                if (!isExpandedScreen) {
                    NavigationBar(
                        containerColor = CyberSurface,
                        tonalElevation = 8.dp
                    ) {
                        V2TabDestination.entries.forEach { dest ->
                            val selected = currentDestination == dest
                            NavigationBarItem(
                                selected = selected,
                                onClick = { currentDestination = dest },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                        contentDescription = stringResource(dest.labelRes)
                                    )
                                },
                                label = {
                                    Text(
                                        text = stringResource(dest.labelRes),
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = ElectricCyan,
                                    selectedTextColor = ElectricCyan,
                                    indicatorColor = ElectricCyanDim,
                                    unselectedIconColor = TextMuted,
                                    unselectedTextColor = TextSecondary
                                ),
                                modifier = Modifier.testTag("nav_${dest.route}")
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (isExpandedScreen) {
                    NavigationRail(
                        containerColor = CyberSurface,
                        modifier = Modifier.fillMaxHeight()
                    ) {
                        Spacer(modifier = Modifier.height(12.dp))
                        V2TabDestination.entries.forEach { dest ->
                            val selected = currentDestination == dest
                            NavigationRailItem(
                                selected = selected,
                                onClick = { currentDestination = dest },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                        contentDescription = stringResource(dest.labelRes)
                                    )
                                },
                                label = { Text(stringResource(dest.labelRes)) },
                                modifier = Modifier.testTag("nav_rail_${dest.route}")
                            )
                        }
                    }
                }

                Column(modifier = Modifier.fillMaxSize()) {
                    AnimatedVisibility(visible = uiState.statusBannerMessage != null) {
                        uiState.statusBannerMessage?.let { msg ->
                            Surface(
                                color = ElectricCyanDim,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.dismissStatusBanner() }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = msg,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextPrimary,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = "OK",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = ElectricCyan,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    when (currentDestination) {
                        V2TabDestination.MAIN -> {
                            MainTabScreen(
                                uiState = uiState,
                                onSubscriptionUrlChange = viewModel::updateSubscriptionUrlInput,
                                onRefreshSubscription = viewModel::refreshOrImportSubscription,
                                onToggleConnect = handleToggleConnect
                            )
                        }
                        V2TabDestination.LOGS -> {
                            CoreLogsScreen(
                                nodeSnapshots = uiState.nodeSnapshots,
                                logs = logs,
                                isProbingAll = uiState.isProbingAll,
                                onRetestAllNodes = { viewModel.probeAllNodes(silent = false) },
                                onClearLogs = viewModel::clearLogs,
                                onNavigateBackHome = { currentDestination = V2TabDestination.MAIN }
                            )
                        }
                    }
                }
            }
        }
    }
}
