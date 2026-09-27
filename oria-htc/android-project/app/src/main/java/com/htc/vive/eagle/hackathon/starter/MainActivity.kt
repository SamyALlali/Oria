package com.htc.vive.eagle.hackathon.starter

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavController
import com.htc.viveglass.sdk.ViveGlassKit
import com.htc.viveglass.sdk.simulator.ViveGlassSimulator
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.htc.viveglass.sdk.KeyEvent
import com.htc.viveglass.sdk.Log
import com.htc.viveglass.sdk.ViveGlass
import com.htc.vive.eagle.hackathon.starter.ui.tab.AppDestination
import com.htc.vive.eagle.hackathon.starter.ui.tab.AudioScreen
import com.htc.vive.eagle.hackathon.starter.ui.tab.CameraScreen
import com.htc.vive.eagle.hackathon.starter.ui.tab.ChatScreen
import com.htc.vive.eagle.hackathon.starter.ui.tab.GlassesScreen
import com.htc.vive.eagle.hackathon.starter.ui.tab.setSimulator
import com.htc.vive.eagle.hackathon.starter.ui.theme.AppColors
import com.htc.vive.eagle.hackathon.starter.util.DebugLogger
import com.htc.vive.eagle.hackathon.starter.util.Logger
import com.htc.vive.eagle.hackathon.starter.util.NoOpLogger
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.htc.vive.eagle.hackathon.starter.oria.lifecycle.PocketSessionService
import com.htc.vive.eagle.hackathon.starter.oria.OriaController
import com.htc.vive.eagle.hackathon.starter.oria.ui.OriaScreen
import com.htc.vive.eagle.hackathon.starter.oria.ui.OriaLabScreen
import com.htc.vive.eagle.hackathon.starter.oria.ui.OriaNavigation
import com.htc.vive.eagle.hackathon.starter.oria.recording.OriaLabPhase

class MainActivity : AppCompatActivity() {
    var oriaController: OriaController? = null
        private set
    private var echoPageRoute: String? = null
    private fun startOria() {
        if (echoPageRoute != AppDestination.Oria.route || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        // Oria uses HTC's video-only overload; microphone permission belongs to diagnostics.
        val controller = oriaController ?: return
        if (!controller.state.value.simulator) {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this,
                    Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
            PocketSessionService.request(this, controller) {
                echoPageRoute == AppDestination.Oria.route && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            }
        } else controller.start()
    }

    private fun startOriaLab() {
        if (echoPageRoute != AppDestination.OriaLab.route || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        oriaController?.startOriaLab()
    }

    private fun onEchoPageChanged(route: String?) {
        echoPageRoute = route
    }


    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startOria()
            else oriaController?.reportPocketError("Autorisez les notifications Oria pour démarrer l’assistance et garder son bouton Arrêter accessible")
        }

    private val bluetoothPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )
    } else {
        arrayOf(
            Manifest.permission.BLUETOOTH
        )
    }

    private val bluetoothPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val allGranted = permissions.all { it.value }
            if (allGranted) {
                Log.d(TAG, "All Bluetooth permissions granted")
            } else {
                Log.e(TAG, "Bluetooth permissions denied: ${permissions.filter { !it.value }.keys}")
            }
        }
    val enableDebugLog = false

    var viveClientManager : ViveGlassKitManager? =null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.instance =
            if (enableDebugLog) DebugLogger()
            else NoOpLogger()

        val missingPermissions = bluetoothPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isNotEmpty()) {
            bluetoothPermissionLauncher.launch(missingPermissions.toTypedArray())
        } else {
            Log.d(TAG, "All Bluetooth permissions already granted")
        }
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val appContext = applicationContext
        val glass = ViveGlass()
        val kit = ViveGlassKit(appContext)

        ViveGlassSimulator.create(appContext)
        val simulator = ViveGlassSimulator.instance()

        viveClientManager = ViveGlassKitManager(
            glass = glass,
            kit = kit,
            simulator = simulator,
            activity = this,
            appContext = appContext,
            audioManager = audioManager
        )

        val controller = OriaController(appContext, viveClientManager!!)
        oriaController = controller
        viveClientManager!!.setManualSpeechHandler(controller::speakManual)
        viveClientManager!!.setOriaMode(true)
        onEchoPageChanged(AppDestination.Oria.route)
        enableEdgeToEdge()
        setContent {
            val controller = oriaController ?: return@setContent
            val manager = viveClientManager ?: return@setContent
            SampleApp(manager, simulator, this@MainActivity, controller, ::startOria, ::startOriaLab, ::onEchoPageChanged)
        }
    }
    override fun onStart() {
        super.onStart()

        registerReceiver(
            bluetoothStateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        )
    }

    override fun onStop() {
        if (oriaController?.canContinueInBackground() != true) {
            oriaController?.stop("Application en arrière-plan · session arrêtée")
            viveClientManager?.stopMediaForBackground()
        }
        super.onStop()

        unregisterReceiver(bluetoothStateReceiver)
    }

    private fun getBluetoothAdapterState(): Int {
        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        val adapter = bluetoothManager.adapter

        return adapter?.state ?: BluetoothAdapter.ERROR
    }

    private fun bluetoothStateToString(state: Int): String {
        return when (state) {
            BluetoothAdapter.STATE_OFF -> "OFF"
            BluetoothAdapter.STATE_TURNING_OFF -> "TURNING_OFF"
            BluetoothAdapter.STATE_ON -> "ON"
            BluetoothAdapter.STATE_TURNING_ON -> "TURNING_ON"
            else -> "UNKNOWN"
        }
    }

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(
                    BluetoothAdapter.EXTRA_STATE,
                    BluetoothAdapter.ERROR
                )
                if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF)
                {
                    Log.d(TAG, "Bluetooth is turning off/off. Disconnecting ViveGlass.")
                    viveClientManager?.disconnect()
                }

                Log.d(TAG, "Bluetooth adapter state: ${bluetoothStateToString(state)}")
            }
        }
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        oriaController?.close()
        oriaController = null
        viveClientManager?.cleanup()
        viveClientManager = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SampleApp(
    viveClientManager: ViveGlassKitManager,
    simulator: ViveGlassSimulator,
    context: Context,
    oriaController: OriaController,
    onStartOria: () -> Unit,
    onStartOriaLab: () -> Unit,
    onEchoPageChanged: (String?) -> Unit,
) {
    val navController = rememberNavController()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val isConnected by viveClientManager.connection.collectAsStateWithLifecycle()
    val capture by oriaController.oriaLabState.collectAsStateWithLifecycle()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val inHtcDiagnostics = AppDestination.diagnosticItems.any { it.route == currentRoute }
    val notifyEchoPageChanged by rememberUpdatedState(onEchoPageChanged)
    var diagnosticReturnRoute by rememberSaveable { mutableStateOf(AppDestination.Oria.route) }

    // One resource owner for clicks, Back and restored destinations. The new page
    // cannot start media until the previous owner's work has been invalidated.
    DisposableEffect(navController, viveClientManager, oriaController) {
        var previousRoute: String? = null
        val destinationListener = NavController.OnDestinationChangedListener { _, destination, _ ->
            val nextRoute = destination.route
            if (nextRoute != previousRoute) {
                val previousEcho = AppDestination.echoItems.any { it.route == previousRoute }
                val nextEcho = AppDestination.echoItems.any { it.route == nextRoute }
                if (previousEcho && !nextEcho) {
                    oriaController.stop("Diagnostic HTC · session arrêtée et capture finalisée")
                } else if (!previousEcho) {
                    previousRoute?.let(viveClientManager::releasePreviousPage)
                }
                // Switching Oria/OriaLab shares the current live pipeline; recording is explicit.
                viveClientManager.setOriaMode(nextEcho)
                notifyEchoPageChanged(if (nextEcho) nextRoute else null)
                previousRoute = nextRoute
            }
        }
        navController.addOnDestinationChangedListener(destinationListener)
        onDispose {
            navController.removeOnDestinationChangedListener(destinationListener)
            notifyEchoPageChanged(null)
            if (AppDestination.echoItems.any { it.route == previousRoute }) {
                oriaController.stop("Écran fermé · session arrêtée et capture finalisée")
            }
            viveClientManager.setOriaMode(false)
        }
    }

    fun openDiagnostic(destination: AppDestination) {
        if (currentRoute == destination.route) return
        if (AppDestination.echoItems.any { it.route == currentRoute }) diagnosticReturnRoute = currentRoute!!
        navController.navigate(destination.route) {
            popUpTo(diagnosticReturnRoute) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    fun returnFromDiagnostic() {
        if (!navController.popBackStack(diagnosticReturnRoute, false)) {
            navController.navigate(diagnosticReturnRoute) {
                popUpTo(navController.graph.id)
                launchSingleTop = true
            }
        }
    }

    fun openEcho(destination: AppDestination) {
        if (currentRoute == destination.route) return
        navController.navigate(destination.route) {
            popUpTo(AppDestination.Oria.route) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    LaunchedEffect(isConnected, currentRoute) {
        if (!isConnected && inHtcDiagnostics && currentRoute != AppDestination.Glasses.route) {
            openDiagnostic(AppDestination.Glasses)
        }
    }

    LaunchedEffect(viveClientManager, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viveClientManager.keyEvent.collect { event ->
                if (event == KeyEvent.AIBUTTON) {
                    Toast.makeText(context, "KeyEvent received: $event", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Oria owns the main screen and its fixed controls. The HTC navigation
    // only exists while a secondary diagnostic page is open.
    Scaffold(
        containerColor = AppColors.BgDarkPrimary,
        contentColor = AppColors.TextGray400,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            if (inHtcDiagnostics) {
                TopAppBar(
                    title = { Text("Diagnostic HTC") },
                    navigationIcon = {
                        TextButton(onClick = ::returnFromDiagnostic) {
                            Text(if (diagnosticReturnRoute == AppDestination.OriaLab.route) "‹ Oria Lab" else "‹ Oria", color = AppColors.TextBlue400)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = AppColors.BgDarkPrimary,
                        titleContentColor = AppColors.TextGray400,
                    ),
                )
            } else {
                OriaNavigation(currentRoute, capture.phase == OriaLabPhase.RECORDING, ::openEcho)
            }
        },
        bottomBar = {
            if (inHtcDiagnostics) {
                NavigationBar(containerColor = AppColors.BgDarkPrimary) {
                    AppDestination.diagnosticItems.forEach { destination ->
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            onClick = { openDiagnostic(destination) },
                            icon = {
                                Icon(
                                    painter = painterResource(destination.iconRes),
                                    contentDescription = destination.label,
                                    modifier = Modifier.size(26.dp),
                                )
                            },
                            label = { Text(destination.label) },
                            enabled = isConnected || destination == AppDestination.Glasses,
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = if (isConnected) AppColors.BgBlue500_30 else Color.Transparent,
                                selectedIconColor = AppColors.TextBlue400,
                                selectedTextColor = AppColors.TextBlue400,
                                unselectedIconColor = AppColors.TextGray400,
                                unselectedTextColor = AppColors.TextGray400,
                                disabledTextColor = AppColors.BgDarkSecondary,
                                disabledIconColor = AppColors.BgDarkSecondary,
                            ),
                        )
                    }
                }
            }
        },
    ) { contentPadding ->
        NavHost(
            navController = navController,
            startDestination = AppDestination.Oria.route,
            modifier = Modifier.fillMaxSize().padding(contentPadding).consumeWindowInsets(contentPadding),
        ) {
            setSimulator(simulator)
            composable(AppDestination.Oria.route) {
                OriaScreen(oriaController, onStartOria) {
                    openDiagnostic(AppDestination.Glasses)
                }
            }
            composable(AppDestination.OriaLab.route) {
                OriaLabScreen(oriaController, onStartOriaLab) { openDiagnostic(AppDestination.Glasses) }
            }
            composable(AppDestination.Glasses.route) { GlassesScreen(viveClientManager) }
            composable(AppDestination.Chat.route) { ChatScreen(viveClientManager) }
            composable(AppDestination.Audio.route) { AudioScreen(viveClientManager) }
            composable(AppDestination.Camera.route) { CameraScreen(viveClientManager) }
        }
    }
}
