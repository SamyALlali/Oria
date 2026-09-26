package com.htc.vive.eagle.hackathon.starter.ui.tab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.htc.vive.eagle.hackathon.starter.ui.components.CustomHorizontalDivider
import com.htc.vive.eagle.hackathon.starter.ui.components.CustomText
import com.htc.vive.eagle.hackathon.starter.ui.components.CustomTitleWithIcon
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.htc.vive.eagle.hackathon.starter.R.drawable
import com.htc.vive.eagle.hackathon.starter.ui.components.CustomButton
import com.htc.vive.eagle.hackathon.starter.ui.theme.AppColors

@Composable
fun GlassTab(
    onEvent: (GlassEvent) ->Unit,
    vm : GlassTabModel
) {
    val states = vm.uiState.collectAsStateWithLifecycle()
    val connectionStatus = if(states.value.isConnected)"Connected" else "Disconnected"

    val spacerHeight = 30.dp
    val spacerHeightSecond = 10.dp

    var selectedMode by rememberSaveable { mutableStateOf(DeviceMode.NULL) }
    val isStartEnabled = selectedMode != DeviceMode.NULL
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
            drawable.glasses_solid_full,
            iconColor = AppColors.TextBlue400,
            iconSize = 25,
            titleText = " Glass Status",
            fontSize = 23
        )
        Spacer(modifier = Modifier.height(spacerHeightSecond))
        CustomHorizontalDivider()
        //--------------------------------------------------

        Spacer(modifier = Modifier.height(spacerHeightSecond))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp),
        ){
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start

            ){
                CustomText("Device Mode:")
            }

            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End

            ){
                    ExposedDropdown(
                        selectedOption = selectedMode,
                        onSelected = {
                            selectedMode = it
                            onEvent(GlassEvent.DeviceModeChanged(selectedMode))
                        }
                )
            }
        }

        //--------------------------------------------------

        Spacer(modifier = Modifier.height(spacerHeight))
        Box(
            modifier = Modifier
            .fillMaxWidth()
            .height(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start

            ) {
                CustomText(
                    "Connection Status : ",
                    color = AppColors.TextGray300
                )
            }

            CustomTitleWithIcon(
                drawable.circle_solid_full,
                iconDes = "",
                iconSize = 15,
                iconColor = if (!states.value.isConnected) AppColors.TextRed500 else AppColors.TextGreen400,
                titleText = connectionStatus,
                titleColor = if (!states.value.isConnected) AppColors.TextRed500 else AppColors.TextGreen400,
                fontSize = 16,
                arrangement = Arrangement.End
            )
        }
        Spacer(modifier = Modifier.height(spacerHeight))

        CustomButton(states.value.textConnectButton,
            color = AppColors.BgBlue600,
            disabledColor = AppColors.BgDarkSecondary,
            height = 50,
            enable = isStartEnabled,
            onClick = {
            onEvent(GlassEvent.ConnectClicked)
        })
        Spacer(modifier = Modifier.height(spacerHeight))
        //--------------------------------------------------
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExposedDropdown(
    selectedOption: DeviceMode,
    onSelected: (DeviceMode) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    //var selectedOption by rememberSaveable  { mutableStateOf(DeviceMode.NULL) }
    val options = listOf(DeviceMode.EAGLE, DeviceMode.SIMULATOR)

    val colorTextFieldColors = TextFieldDefaults.colors(
        focusedContainerColor = AppColors.BgDarkSecondary,
        unfocusedContainerColor = AppColors.BgDarkSecondary,
        focusedTextColor = AppColors.TextWhite,
        unfocusedTextColor = AppColors.TextWhite,
        focusedIndicatorColor = Color.Transparent,
        unfocusedIndicatorColor = Color.Transparent,
        focusedTrailingIconColor = AppColors.TextWhite,
        unfocusedTrailingIconColor = AppColors.TextWhite,
        focusedPlaceholderColor = AppColors.TextWhite,
        unfocusedPlaceholderColor = AppColors.TextWhite
    )

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = Modifier.padding(0.dp)
    ) {
        Box(
            modifier = Modifier
                .menuAnchor()
                .width(200.dp)
                .height(55.dp)
                .shadow(10.dp, RoundedCornerShape(10.dp))
                .background(AppColors.BgDarkPrimary, RoundedCornerShape(10.dp))
        ) {
            OutlinedTextField(
                modifier = Modifier.fillMaxSize(),
                value = selectedOption.label,
                onValueChange = {},
                readOnly = true,
                placeholder = { Text("Select", fontWeight = FontWeight.Bold, color = AppColors.TextGray400) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                shape = RoundedCornerShape(10.dp),
                colors = colorTextFieldColors,
                textStyle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold),
                singleLine = true
            )
        }

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = AppColors.BgDarkSecondary,
            shape = RoundedCornerShape(10.dp)
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label,
                        color = AppColors.TextWhite,
                        fontSize = 16.sp)
                    },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    },
                )
            }
        }
    }
}



