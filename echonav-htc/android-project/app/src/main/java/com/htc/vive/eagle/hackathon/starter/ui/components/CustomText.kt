package com.htc.vive.eagle.hackathon.starter.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.htc.vive.eagle.hackathon.starter.ui.theme.AppColors

@Composable
fun CustomText (
    text: String,
    modifier: Modifier = Modifier,
    color: Color = AppColors.TextWhite,
    fontSize: TextUnit=16.sp,
    fontWeight : FontWeight = FontWeight.Normal,
    textAlign: TextAlign = TextAlign.Start
){
    Text(
        text = text,
        color = color,
        fontSize = fontSize,
        fontWeight = fontWeight,
        modifier = modifier,
        textAlign = textAlign
    )
}