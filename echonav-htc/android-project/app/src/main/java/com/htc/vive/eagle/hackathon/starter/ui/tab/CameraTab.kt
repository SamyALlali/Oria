package com.htc.vive.eagle.hackathon.starter.ui.tab

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.htc.vive.eagle.hackathon.starter.ui.components.CustomText
import com.htc.vive.eagle.hackathon.starter.ui.components.CustomHorizontalDivider
import com.htc.vive.eagle.hackathon.starter.ui.components.CustomTitleWithIcon
import com.htc.vive.eagle.hackathon.starter.ui.components.CustomButton
import com.htc.vive.eagle.hackathon.starter.R.drawable
import com.htc.vive.eagle.hackathon.starter.ui.theme.AppColors

@Composable
fun CameraTab(
    onEvent: (CameraEvent) -> Unit,
    vm : CameraTabModel
)
{
    val states = vm.uiState.collectAsStateWithLifecycle()
    val bmp by vm.latestImageReceived.collectAsState()

    val spacerHeight = 30.dp

    var fractionVideoPreview by remember { mutableStateOf(0f) }
    var fractionImgPreview by remember { mutableStateOf(0f) }

    Spacer(modifier = Modifier.height(20.dp))
    Column(
        modifier = Modifier
            .padding(start = 25.dp, end = 25.dp)
            .fillMaxSize(),
        horizontalAlignment = Alignment.Start
    ){
        //--------------------------------------------------
        Spacer(modifier = Modifier.height(10.dp))
        CustomTitleWithIcon(
            drawable.camera_solid_full,
            iconSize = 25,
            iconDes = "",
            iconColor = AppColors.TextBlue400,
            titleText = " Photo & Video Capture",
            fontSize = 23
        )
        Spacer(modifier = Modifier.height(10.dp))
        CustomHorizontalDivider()
        //--------------------------------------------------

        Spacer(modifier = Modifier.height(spacerHeight))
        CustomTitleWithIcon(
            drawable.camera_solid_full,
            iconDes = "",
            iconColor = AppColors.BgIndigo600,
            iconSize = 25,
            titleText = "Take Photo: ",
            fontSize = 16,
            fontWeight = FontWeight.Normal
        )
        Spacer(modifier = Modifier.height(10.dp))
        CustomButton("Take Photo"
            , color = AppColors.BgIndigo600
            , height = 50
            , onClick = {
                fractionImgPreview = 1f
                onEvent(CameraEvent.TakePhotoClicked)
            })
        Spacer(modifier = Modifier.height(spacerHeight))

        //--------------------------------------------------

        CustomTitleWithIcon(
            drawable.video_solid_full,
            iconDes = "",
            iconColor = AppColors.TextRed400,
            iconSize = 25,
            titleText = "Video Recording: ",
            fontSize = 16,
            fontWeight = FontWeight.Normal
        )
        Spacer(modifier = Modifier.height(10.dp))
        CustomButton(
            text = if(!states.value.isVideoRecording)"Start Recording" else "Stop Recording",
            color = if(!states.value.isVideoRecording) AppColors.BgRed600 else AppColors.BgDarkSecondary,
            height = 50,
            enable = true,
            onClick = {
                if(!states.value.isVideoRecording)
                    onEvent(CameraEvent.StartRecordingClicked)
                else
                    onEvent(CameraEvent.StopRecordingClicked)
            }
        )
        Spacer(modifier = Modifier.height(spacerHeight))

        //--------------------------------------------------

        CustomTitleWithIcon(
            drawable.desktop_solid_full,
            iconDes = "",
            iconColor = AppColors.TextBlue400,
            iconSize = 25,
            titleText = "Media Preview",
            fontSize = 16,
            fontWeight = FontWeight.Normal
        )
        Spacer(modifier = Modifier.height(10.dp))

        val configuration = LocalConfiguration.current
        val screenWidthDp = configuration.screenWidthDp.dp -50.dp // Column padding each side 25
        val targetHeight = minOf((screenWidthDp / states.value.previewRatio),300.dp)
        val targetWidth = targetHeight * states.value.previewRatio

        Box(
            modifier = Modifier
                .width(targetWidth)
                .height(targetHeight)
                .padding(top =8.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(color = AppColors.BgGray800)
                .dashedBorder(
                    color = AppColors.BorderGray600,
                    strokeWidth = 3.dp,
                    cornerRadius = 10.dp,
                    intervals = floatArrayOf(15f, 15f)
                )
                .align(Alignment.CenterHorizontally),
            contentAlignment = Alignment.Center
        ){
            if((!states.value.isImageCapturing && bmp == null) || // imageCallback received but image received is null
                states.value.isVideoRecording
            ){
                fractionImgPreview = 0f
            }
            fractionVideoPreview = if(!states.value.isVideoRecording) 0f else 1f

            if(fractionImgPreview == 0f && fractionVideoPreview == 0f)
            {    CustomText(
                    "Live Camera Feed / Last Capture Preview",
                    color = AppColors.TextGray500
                )
            }

            StreamPreview(modifier = Modifier
                .padding(2.dp)
                .fillMaxSize(fractionVideoPreview)
                .clip(RoundedCornerShape(10.dp)),
                onSurfaceReadyCallback = { surface ->
                    vm.attachSurface(surface)
                },
                onSurfaceDestroyed = {
                    vm.detachSurface()
                }
            )

            bmp?.let {
                Image(
                    modifier = Modifier
                        .fillMaxSize(fractionImgPreview),
                    bitmap = it.asImageBitmap(),
                    contentDescription = null
                )
            }
        }
    }
}
fun Modifier.dashedBorder(
    color: Color,
    strokeWidth: Dp = 1.dp,
    cornerRadius: Dp = 0.dp,
    intervals: FloatArray = floatArrayOf(10f, 10f)
) = this.then(
    Modifier.drawBehind {
        val stroke = Stroke(
            width = strokeWidth.toPx(),
            pathEffect = PathEffect.dashPathEffect(intervals, 0f)
        )

        val width = size.width
        val height = size.height

        drawRoundRect(
            color = color,
            topLeft = Offset(0f, 0f),
            size = Size(width, height),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                cornerRadius.toPx(),
                cornerRadius.toPx()
            ),
            style = stroke
        )
    }
)

@Composable
fun StreamPreview(
    modifier: Modifier,
    onSurfaceReadyCallback : (Surface) -> Unit,
    onSurfaceDestroyed : () -> Unit
){
    val onReady by rememberUpdatedState(onSurfaceReadyCallback)
    val onDestroyed by rememberUpdatedState(onSurfaceDestroyed)

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextureView(ctx).apply {
                var surface: Surface? = null
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                        surface = Surface(st)
                        onReady(surface!!)
                    }
                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                        onDestroyed()
                        surface?.release()
                        surface = null
                        return true
                    }
                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                }
            }
        }
    )
}
