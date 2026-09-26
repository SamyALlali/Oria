package com.htc.vive.eagle.hackathon.starter.ui.tab

import com.htc.vive.eagle.hackathon.starter.R

sealed class AppDestination(
    val route: String,
    val label: String,
    val iconRes: Int
)
{
    data object Glasses : AppDestination("glasses", "Glasses", R.drawable.glasses_solid_full)
    data object Chat : AppDestination("chat","Chat", R.drawable.comment_dots_solid_full)
    data object Audio : AppDestination("audio", "Audio", R.drawable.headphones_solid_full)
    data object Camera : AppDestination("camera", "Camera", R.drawable.camera_solid_full)
    data object EchoNav : AppDestination("echonav", "Oria", R.drawable.desktop_solid_full)
    data object EchoTest : AppDestination("echotest", "Oria Lab", R.drawable.camera_solid_full)

    companion object{
        // Loading a destination object first initializes this superclass before its
        // INSTANCE exists. Build the lists only after that initialization completes.
        val diagnosticItems: List<AppDestination> by lazy { listOf(Glasses, Chat, Audio, Camera) }
        val echoItems: List<AppDestination> by lazy { listOf(EchoNav, EchoTest) }
    }
}
