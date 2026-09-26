package com.htc.vive.eagle.hackathon.starter.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.htc.vive.eagle.hackathon.starter.ui.theme.AppColors
import com.htc.vive.eagle.hackathon.starter.R.drawable

@Preview
@Composable
fun ViveHeaderSurface(
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.Center,
    isSimulatorMode : Boolean = false,
    onClickButton : ()->Unit = {},
    content: @Composable BoxScope.() -> Unit ={},
){

    CustomSurface()
    {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ){
            Header("VIVE", isSimulatorMode, onClickButton)
            Spacer(modifier = Modifier.height(10.dp))
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopCenter,
                content = content
            )
        }
    }
}

@Composable
fun CustomSurface(
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit ={},
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = AppColors.BgDarkPrimary,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ){
            Spacer(modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars))
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopCenter,
                content = content
            )
        }
    }

}

@Composable
private fun Header(
    text: String,
    isSimulatorMode: Boolean = false,
    onClick : () -> Unit
) {
    Box(
        modifier = Modifier
            .padding(start = 25.dp, end = 25.dp)
            .height(50.dp)
    )
    {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxSize()
        ) {
            Text(text,
                modifier = Modifier
                    .padding(top = 10.dp, bottom = 10.dp),
                style = TextStyle(
                    letterSpacing = 3.sp
                ),
                fontSize = 32.sp,
                color = AppColors.TextWhite,
                fontWeight = FontWeight.Bold
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
            modifier = Modifier.fillMaxSize()
        ) {
            val iconColors = IconButtonDefaults.iconButtonColors(
                disabledContentColor =Color.Transparent,
                disabledContainerColor = Color.Transparent)

            IconButton(
                onClick= onClick,
                enabled = isSimulatorMode,
                colors = iconColors)
            {
                Icon(
                    painter = painterResource(drawable.glasses_solid_full),
                    contentDescription = "Send",
                    modifier = Modifier
                        .padding(start = 10.dp, end = 10.dp)
                        .requiredSize(32.dp),
                    tint = if(isSimulatorMode)AppColors.TextBlue400 else Color.Transparent,
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(10.dp))
    HorizontalDivider(
        thickness = 1.dp, color = AppColors.BorderGray700_50,
        modifier = Modifier
            .padding(top = 0.dp, start = 0.dp, end = 0.dp, bottom = 10.dp)
    )
}

