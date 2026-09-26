package com.htc.vive.eagle.hackathon.starter.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.htc.vive.eagle.hackathon.starter.ui.theme.AppColors

@Composable
fun CustomTextField(
    value: String,
    textPlaceHolder: String,
    onValueChange: (String) -> Unit,
    enable : Boolean,
    fraction:Float = 0.75f,
)
{
    TextField(
        enabled  = enable,
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(textPlaceHolder) } ,
        textStyle = TextStyle(
            color = AppColors.TextWhite,
            fontSize = 16.sp
        ),
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            focusedContainerColor = AppColors.BgDarkSecondary,
            unfocusedContainerColor = AppColors.BgDarkSecondary,
            focusedTextColor = AppColors.TextGray200,
            unfocusedTextColor = AppColors.TextGray200,
            unfocusedPlaceholderColor = AppColors.TextGray400,
            disabledTextColor = AppColors.TextGray200,
            disabledPlaceholderColor = AppColors.TextGray200,
            disabledContainerColor = AppColors.BgDarkSecondary,
            cursorColor = AppColors.TextWhite,
        ),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(fraction)
    )
}