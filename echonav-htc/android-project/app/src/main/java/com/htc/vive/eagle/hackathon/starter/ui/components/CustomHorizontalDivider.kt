package com.htc.vive.eagle.hackathon.starter.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.htc.vive.eagle.hackathon.starter.ui.theme.AppColors

@Composable
fun CustomHorizontalDivider()
{
    HorizontalDivider(
        thickness = 1.dp, color = AppColors.BorderGray600,
        modifier = Modifier
            .padding(top = 10.dp, start = 0.dp, end = 0.dp, bottom = 10.dp)
    )
}