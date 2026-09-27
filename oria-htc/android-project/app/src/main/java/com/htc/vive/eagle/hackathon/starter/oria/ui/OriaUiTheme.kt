package com.htc.vive.eagle.hackathon.starter.oria.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val OriaLightColors = lightColorScheme(
    primary = Color(0xFF075B50), onPrimary = Color.White,
    primaryContainer = Color(0xFFDFEFE7), onPrimaryContainer = Color(0xFF123D32),
    secondary = Color(0xFF415D52), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3EBE5), onSecondaryContainer = Color(0xFF233A30),
    tertiary = Color(0xFF705100), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFEFCA), onTertiaryContainer = Color(0xFF513900),
    background = Color(0xFFF4F6F2), onBackground = Color(0xFF162D27),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF162D27),
    surfaceVariant = Color(0xFFE8EEE8), onSurfaceVariant = Color(0xFF435A50),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF0F4EE),
    surfaceContainer = Color(0xFFEBF0E9),
    surfaceContainerHigh = Color(0xFFE5EBE3),
    surfaceContainerHighest = Color(0xFFDFE6DD),
    outline = Color(0xFF5E7469), outlineVariant = Color(0xFFC2CFC5),
    error = Color(0xFF9A2926), onError = Color.White,
    errorContainer = Color(0xFFFFEDEA), onErrorContainer = Color(0xFF70201D),
    inverseSurface = Color(0xFF163D31), inverseOnSurface = Color(0xFFF3F8F4),
    inversePrimary = Color(0xFFAFE5CB),
)

private val OriaDarkColors = darkColorScheme(
    primary = Color(0xFF9EE4CC), onPrimary = Color(0xFF062F25),
    primaryContainer = Color(0xFF234D3F), onPrimaryContainer = Color(0xFFD4F5E6),
    secondary = Color(0xFFBCD1C3), onSecondary = Color(0xFF17372A),
    secondaryContainer = Color(0xFF2D4438), onSecondaryContainer = Color(0xFFD8EBDD),
    tertiary = Color(0xFFE4C77B), onTertiary = Color(0xFF3C2E00),
    tertiaryContainer = Color(0xFF4F3F10), onTertiaryContainer = Color(0xFFFFEFBD),
    background = Color(0xFF0E1915), onBackground = Color(0xFFF0F7F1),
    surface = Color(0xFF182721), onSurface = Color(0xFFF0F7F1),
    surfaceVariant = Color(0xFF2C4035), onSurfaceVariant = Color(0xFFBDCEC1),
    surfaceContainerLowest = Color(0xFF0A1410),
    surfaceContainerLow = Color(0xFF15221C),
    surfaceContainer = Color(0xFF1C2A22),
    surfaceContainerHigh = Color(0xFF25362B),
    surfaceContainerHighest = Color(0xFF304135),
    outline = Color(0xFF9DB5A5), outlineVariant = Color(0xFF42594A),
    error = Color(0xFFFFB4AB), onError = Color(0xFF561B17),
    errorContainer = Color(0xFF592923), onErrorContainer = Color(0xFFFFDAD4),
    inverseSurface = Color(0xFFE1EFE4), inverseOnSurface = Color(0xFF183526),
    inversePrimary = Color(0xFF075B50),
)

private fun style(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = FontFamily.SansSerif, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp,
)

private val OriaTypography = Typography(
    headlineLarge = style(34, 40, FontWeight.Bold),
    headlineMedium = style(28, 36, FontWeight.Bold),
    headlineSmall = style(26, 34, FontWeight.SemiBold),
    titleLarge = style(24, 30, FontWeight.SemiBold),
    titleMedium = style(20, 28, FontWeight.SemiBold),
    titleSmall = style(18, 26, FontWeight.SemiBold),
    bodyLarge = style(18, 26), bodyMedium = style(16, 24), bodySmall = style(16, 24),
    labelLarge = style(18, 24, FontWeight.SemiBold),
    labelMedium = style(16, 22, FontWeight.Medium),
    labelSmall = style(14, 20, FontWeight.Medium),
)

/** Follow the system light/dark preference and retain Android's font scaling. */
@Composable
fun OriaUiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) OriaDarkColors else OriaLightColors,
        typography = OriaTypography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(24.dp),
            extraLarge = RoundedCornerShape(28.dp),
        ),
        content = content,
    )
}

@Composable
fun OriaSectionTitle(title: String, modifier: Modifier = Modifier) {
    Text(title, modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface)
}

/** One named switch in the accessibility tree; its whole row is the touch target. */
@Composable
fun OriaSettingToggle(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 64.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .semantics { stateDescription = if (checked) "Activé" else "Désactivé" }
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (description != null) Text(description, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled,
            modifier = Modifier.clearAndSetSemantics { })
    }
}
