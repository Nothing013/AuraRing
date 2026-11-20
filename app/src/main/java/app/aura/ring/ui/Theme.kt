package app.aura.ring.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF7DF9FF),
    onPrimary = Color(0xFF002022),
    primaryContainer = Color(0xFF004A50),
    onPrimaryContainer = Color(0xFFB6FBFF),
    secondary = Color(0xFFFF6EC7),
    onSecondary = Color(0xFF3A0028),
    secondaryContainer = Color(0xFF5C1143),
    onSecondaryContainer = Color(0xFFFFD8EC),
    tertiary = Color(0xFFB388FF),
    onTertiary = Color(0xFF23005C),
    tertiaryContainer = Color(0xFF3F1D82),
    onTertiaryContainer = Color(0xFFE9DDFF),
    background = Color(0xFF05070A),
    onBackground = Color(0xFFE6EDF5),
    surface = Color(0xFF0A0F15),
    onSurface = Color(0xFFE6EDF5),
    surfaceVariant = Color(0xFF17202A),
    onSurfaceVariant = Color(0xFFA8B7C7),
    surfaceContainerLowest = Color(0xFF04060A),
    surfaceContainerLow = Color(0xFF0C1219),
    surfaceContainer = Color(0xFF111821),
    surfaceContainerHigh = Color(0xFF17202A),
    surfaceContainerHighest = Color(0xFF1E2935),
    outline = Color(0xFF2B3B4C),
    outlineVariant = Color(0xFF1B2733),
    error = Color(0xFFFF8A8A),
    onError = Color(0xFF3B0000),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF006874),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF9EEFFB),
    onPrimaryContainer = Color(0xFF001F23),
    secondary = Color(0xFF9C2A6E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFD8EC),
    onSecondaryContainer = Color(0xFF3A0028),
    tertiary = Color(0xFF6236B5),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE9DDFF),
    onTertiaryContainer = Color(0xFF23005C),
    background = Color(0xFFF6FAFD),
    onBackground = Color(0xFF11181E),
    surface = Color(0xFFFBFDFF),
    onSurface = Color(0xFF11181E),
    surfaceVariant = Color(0xFFDCE6F0),
    onSurfaceVariant = Color(0xFF41515F),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F6FB),
    surfaceContainer = Color(0xFFE8F1F8),
    surfaceContainerHigh = Color(0xFFE1EBF3),
    surfaceContainerHighest = Color(0xFFDAE5EE),
    outline = Color(0xFF7C8B99),
    outlineVariant = Color(0xFFC4D1DC),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
)

private val AuraShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

@Composable
fun AuraTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        shapes = AuraShapes,
        content = content,
    )
}
