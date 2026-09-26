package com.htc.vive.eagle.hackathon.starter.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.htc.vive.eagle.hackathon.starter.ui.theme.AppColors
import com.htc.vive.eagle.hackathon.starter.R

@Composable
fun CustomBox(
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.Center,
    height: Dp = 300.dp,
    fraction: Float = 0.9f,
    content: @Composable BoxScope.() -> Unit ={}
)
{
    val shape = RoundedCornerShape(10.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth(fraction)
            .height(height)
            .shadow(
                elevation = 15.dp,
                spotColor = Color.Black,
                shape = shape
            )
            .background(AppColors.BgDarkPrimary),
        contentAlignment = contentAlignment,
        content = content
    )
}

@Composable
fun CustomBoxWithButton(
    text : String ="",
    resourceId : Int = R.drawable.power_off_solid_full,
    buttonColor :Color = AppColors.BgRed600,
    buttonContentColor : Color = AppColors.TextWhite,
    textReplaceContent : String =""
)
{
    Box(
        modifier = Modifier
            .height(70.dp)
            .fillMaxWidth(0.9f)
            .clip(shape = RoundedCornerShape(10.dp))
            .background(AppColors.BgDarkSecondary),

        ){
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 10.dp, end = 10.dp),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            CustomText(text, fontSize = 20.sp)
        }
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 10.dp, end = 10.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {},
                modifier = Modifier
                    .size(width = 80.dp, height = 40.dp)
                    .shadow(
                        elevation = 15.dp,
                        spotColor = Color.Black,
                        shape = RoundedCornerShape(10.dp)
                    )
                    .background(buttonColor)

            ) {
                Icon(
                    painter = painterResource(resourceId),
                    tint = buttonContentColor,
                    contentDescription = "",
                    modifier = Modifier
                        .requiredSize(20.dp)
                )
                Text(textReplaceContent, color = AppColors.TextWhite, fontWeight = FontWeight.Bold)
            }
        }
    }
}