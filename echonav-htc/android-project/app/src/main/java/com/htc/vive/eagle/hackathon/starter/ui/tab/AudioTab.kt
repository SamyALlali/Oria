package com.htc.vive.eagle.hackathon.starter.ui.tab

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.htc.vive.eagle.hackathon.starter.ui.components.CustomButton
import com.htc.vive.eagle.hackathon.starter.ui.components.CustomText
import com.htc.vive.eagle.hackathon.starter.ui.components.CustomHorizontalDivider
import com.htc.vive.eagle.hackathon.starter.ui.components.CustomTitleWithIcon
import com.htc.vive.eagle.hackathon.starter.R.drawable
import com.htc.vive.eagle.hackathon.starter.ui.theme.AppColors

@Composable
fun AudioTab(
    onEvent: (AudioEvent) -> Unit,
    vm: AudioTabModel
)
{
    val states = vm.uiState.collectAsStateWithLifecycle()

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
            drawable.microphone_solid_full,
            iconSize = 25,
            iconDes = "",
            iconColor = AppColors.TextBlue400,
            titleText = " Audio Control ",
            fontSize = 23
        )
        Spacer(modifier = Modifier.height(spacerHeightSecond))
        CustomHorizontalDivider()
        //--------------------------------------------------

        Spacer(modifier = Modifier.height(spacerHeight))
        CustomText(
            "Play Audio ",
            color = AppColors.TextWhite
        )

        Spacer(modifier = Modifier.height(spacerHeightSecond))
        CustomButton(
            text = if(!states.value.isAudioPlaying)"Play Sample Audio" else "Audio Playing..."
            , color = if(!states.value.isAudioPlaying) AppColors.BgBlue600 else AppColors.BgDarkSecondary
            , fontWeight = FontWeight.Bold
            , height = 50
            , onClick = {onEvent(AudioEvent.PlaySampleAudioClick)})
        Spacer(modifier = Modifier.height(spacerHeight))

        //--------------------------------------------------

        CustomText(
            "Audio Recording: ",
            color = AppColors.TextWhite
        )
        Spacer(modifier = Modifier.height(spacerHeightSecond))
        CustomButton(
            text =  if(!states.value.isRecording)"Start Recording" else "Stop Recording"
            , color = if (!states.value.isRecording) AppColors.BgGreen600 else AppColors.BgRed600
            , fontWeight = FontWeight.Bold
            , height = 50
            , onClick = {onEvent(AudioEvent.StartRecordingClick)})

        //--------------------------------------------------

    }
}