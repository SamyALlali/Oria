package com.htc.vive.eagle.hackathon.starter.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.text.font.FontWeight
import com.htc.vive.eagle.hackathon.starter.ui.theme.AppColors

@Composable
fun ColumnScope.CustomButton(
    text :String,
    height: Int = 50,
    modifier: Modifier = Modifier,
    contentAlignment: Alignment.Horizontal = Alignment.CenterHorizontally,
    onClick: () -> Unit ={},
    color: Color = Color.White,
    disabledColor :Color = color,
    fontWeight: FontWeight = FontWeight.Bold,
    enable : Boolean = true
) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(25),
        modifier = Modifier
            .fillMaxWidth(0.95f)
            .height(height.dp)
            .align(alignment = contentAlignment),
        colors = ButtonDefaults.buttonColors(
            containerColor = color,
            contentColor = Color.White,
            disabledContainerColor = disabledColor,
            disabledContentColor = AppColors.TextGray500
        ),
        enabled =enable
    )
    {
        Text(text, fontWeight = fontWeight)
    }
}