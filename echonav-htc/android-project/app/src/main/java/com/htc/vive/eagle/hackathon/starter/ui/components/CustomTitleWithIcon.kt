package com.htc.vive.eagle.hackathon.starter.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.htc.vive.eagle.hackathon.starter.ui.theme.AppColors
import com.htc.vive.eagle.hackathon.starter.R

@Composable
fun CustomTitleWithIcon(
    resourceId : Int = R.drawable.glasses_solid_full,
    iconDes :String ="",
    iconSize :Int = 25,
    iconColor : Color = AppColors.TextWhite,
    titleText :String = "",
    titleColor: Color = AppColors.TextWhite,
    fontSize :Int = 25,
    fontWeight : FontWeight = FontWeight.Bold,
    arrangement : Arrangement.Horizontal = Arrangement.Start
) {
    Row(
        modifier = Modifier
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = arrangement,
    )

    {
        Icon(
            painter = painterResource(resourceId),
            contentDescription = iconDes,
            modifier = Modifier
                .requiredSize(iconSize.dp)
                .align(Alignment.CenterVertically),
            tint = iconColor,
        )
        Spacer(modifier = Modifier.width(5.dp))
        CustomText(titleText, fontSize = fontSize.sp , color = titleColor, fontWeight = fontWeight)
    }
}