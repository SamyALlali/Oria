package com.htc.vive.eagle.hackathon.starter.ui.tab

import android.R
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.htc.vive.eagle.hackathon.starter.ui.components.ViveHeaderSurface
import com.htc.viveglass.sdk.simulator.ViveGlassSimulator
import com.htc.vive.eagle.hackathon.starter.ViveGlassKitManager


@Composable
fun GlassesScreen(clientManager: ViveGlassKitManager)
{
    val vm = remember(clientManager) { GlassTabModel(clientManager) }
    setOpenSimulatorEvent()
    val uiState by vm.uiState.collectAsStateWithLifecycle()

    ViveHeaderSurface(
        onClickButton = OnClick_SimulatorSheet,
        isSimulatorMode = uiState.isSimulator
    )
    {
        GlassTab(
            vm::onEvent, vm
        )
    }
}

@Composable
fun ChatScreen(clientManager: ViveGlassKitManager)
{
    val vm = remember(clientManager){ ChatTabModel(clientManager) }
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    setOpenSimulatorEvent()

    ViveHeaderSurface(
        onClickButton = OnClick_SimulatorSheet,
        isSimulatorMode = uiState.isSimulator
    )
    {
        ChatTab(
            vm::onEvent, vm
        )
    }
}

@Composable
fun AudioScreen(clientManager: ViveGlassKitManager)
{
    val vm = remember(clientManager) { AudioTabModel(clientManager) }
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    setOpenSimulatorEvent()

    ViveHeaderSurface(
        onClickButton = OnClick_SimulatorSheet,
        isSimulatorMode = uiState.isSimulator
    )
    {
        AudioTab(
            vm::onEvent, vm
        )
    }
}

@Composable
fun CameraScreen(clientManager: ViveGlassKitManager)
{
    val vm = remember(clientManager) { CameraTabModel(clientManager) }
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    setOpenSimulatorEvent()

    ViveHeaderSurface(
        onClickButton = OnClick_SimulatorSheet,
        isSimulatorMode = uiState.isSimulator
    )
    {
        CameraTab(
            vm::onEvent, vm
        )
    }
}

var OnClick_SimulatorSheet = {}

var viveglassesSimulator : ViveGlassSimulator? = null

fun setSimulator(simulator : ViveGlassSimulator)
{
    viveglassesSimulator = simulator
}

@Composable
fun setOpenSimulatorEvent()
{
    val context = LocalContext.current
    val activity = context.findFragmentActivity()
    val simulator = viveglassesSimulator ?: return

    OnClick_SimulatorSheet = openSimulator@{
        val act = activity
        if (act == null) {
            Toast.makeText(context, "Not a FragmentActivity", Toast.LENGTH_SHORT).show()
            return@openSimulator
        }
        val fm = activity.supportFragmentManager
        fm.registerFragmentLifecycleCallbacks(
            object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentStarted(fm: FragmentManager, f: Fragment) {
                    val dialog = (f as? DialogFragment)?.dialog ?: return
                    dialog.window?.setBackgroundDrawableResource(R.color.transparent)

                    dialog.findViewById<View>(
                        com.google.android.material.R.id.design_bottom_sheet
                    )?.setBackgroundResource(R.color.transparent)
                }
            },
            true
        )
        simulator.showUI(fm)
    }
}


fun Context.findFragmentActivity(): FragmentActivity? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is FragmentActivity) return c
        c = c.baseContext
    }
    return null
}
