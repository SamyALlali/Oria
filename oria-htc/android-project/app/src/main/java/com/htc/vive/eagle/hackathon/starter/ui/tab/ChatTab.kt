package com.htc.vive.eagle.hackathon.starter.ui.tab

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.htc.vive.eagle.hackathon.starter.R
import com.htc.vive.eagle.hackathon.starter.ui.components.CustomText
import com.htc.vive.eagle.hackathon.starter.ui.components.CustomTextField
import com.htc.vive.eagle.hackathon.starter.R.drawable
import com.htc.vive.eagle.hackathon.starter.ui.components.CustomHorizontalDivider
import com.htc.vive.eagle.hackathon.starter.ui.components.CustomTitleWithIcon
import com.htc.vive.eagle.hackathon.starter.ui.theme.AppColors

@Composable
fun ChatTab(
    onEvent: (ChatEvent) -> Unit,
    vm : ChatTabModel
)
{
    val states = vm.uiState.collectAsStateWithLifecycle()

    var enableSendTTS :Boolean = states.value.textTTSInput.isNotBlank()

    val spacerHeight = 30.dp
    val spacerHeightSecond = 10.dp
    Spacer(modifier = Modifier.height(20.dp))

    Column(
        modifier = Modifier
            .padding(start = 25.dp, end = 25.dp)
            .fillMaxSize(),
        horizontalAlignment = Alignment.Start
    ){
        //--------------------------------------------------
        Spacer(modifier = Modifier.height(spacerHeightSecond))
        CustomTitleWithIcon(
            drawable.comment_dots_solid_full,
            iconSize = 25,
            iconDes = "",
            iconColor = AppColors.TextBlue400,
            titleText = " Text Capture ",
            fontSize = 23
        )
        Spacer(modifier = Modifier.height(spacerHeightSecond))
        CustomHorizontalDivider()

        //--------------------------------------------------

        Spacer(modifier = Modifier.height(spacerHeight))
        CustomText(
            "Send Message to Glasses: ",
            color = AppColors.TextGray300
        )
        Spacer(modifier = Modifier.height(spacerHeightSecond))

        Row(verticalAlignment = Alignment.CenterVertically)
        {
            var textTTS by remember { mutableStateOf("") }
            CustomTextField(
                value = textTTS,
                textPlaceHolder = "Enter text for TTS...",
                enable = true,
                fraction = 0.8f,
                onValueChange = {
                    textTTS = it
                    onEvent(ChatEvent.TTSTextFieldChanged(textTTS))
                })

            Spacer(modifier = Modifier.width(15.dp))
            Button(
                onClick = { onEvent(ChatEvent.SendTTSClicked) },
                shape = RoundedCornerShape(15.dp),
                modifier = Modifier
                    .size(48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppColors.BgBlue600,
                    contentColor = AppColors.TextWhite
                ),
                enabled = enableSendTTS
            ) {
                Icon(
                    painter = painterResource(R.drawable.paper_plane_solid_full),
                    contentDescription = "Send",
                    modifier = Modifier.requiredSize(24.dp))
            }
        }
        Spacer(modifier = Modifier.height(spacerHeight))

        //--------------------------------------------------

        CustomText(
            "Glasses Message Received: ",
            color = AppColors.TextGray300
        )
        Spacer(modifier = Modifier.height(spacerHeightSecond))
        Row(verticalAlignment = Alignment.CenterVertically)
        {
            var textSTT by remember { mutableStateOf("") }
            textSTT = states.value.textReceivedMsg
            CustomTextField(
                textPlaceHolder = "Received message from user...\n(Click microphone to hear) ",
                enable = false,
                fraction = 0.8f,
                value = textSTT,
                onValueChange = {
                    textSTT = it
                }
            )

            val micPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { isGranted ->
                if (isGranted) {
                    // Mic access allowed
                } else {
                    // Permission denied
                }
            }
            Spacer(modifier = Modifier.width(15.dp))

            val infiniteTransition = rememberInfiniteTransition(label = "idleScale")
            val scale by infiniteTransition.animateFloat(
                initialValue = 1f,
                targetValue = 1.25f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = 1000,
                        easing = FastOutSlowInEasing
                    ),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "scaleAnim"
            )

            Button(
                onClick = {
                    if(states.value.isSimulator)
                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)

                    onEvent(ChatEvent.STTReceivedMsgClicked)
                          },
                shape = RoundedCornerShape(15.dp),
                modifier = Modifier
                    .size(48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if(!states.value.isStartTranscribe)AppColors.BgGreen600 else AppColors.BgGray700,
                    contentColor = if(!states.value.isStartTranscribe)AppColors.TextWhite else AppColors.TextRed500
                ),
            ) {
                Icon(
                    painter = painterResource(if(!states.value.isStartTranscribe)drawable.microphone_solid_full else drawable.circle_solid_full),
                    contentDescription = "Send",
                    modifier = Modifier
                        .requiredSize((24).dp)
                        .scale(if(!states.value.isStartTranscribe)1f else scale))
            }
        }
        //--------------------------------------------------
    }
}
