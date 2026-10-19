package app.lawnchair.ui.preferences.components.colorpreference

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.HdrAuto
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.lawnchair.theme.color.ColorOption
import app.lawnchair.ui.theme.isSelectedThemeDark

@Composable
fun <T> ColorDot(
    entry: ColorPreferenceEntry<T>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val colorLight = entry.lightColor(context)
    val colorDark = entry.darkColor(context)

    val color = if (isSelectedThemeDark) colorDark else colorLight

    if (entry.value is ColorOption.Transparent) {
        ColorDot(
            color = Color.Transparent,
            modifier = modifier,
        )
    } else if ((entry.value as? ColorOption) != ColorOption.Default && colorLight != 0 || (entry.value is ColorOption.CustomColor)) {
        ColorDot(
            color = Color(color),
            modifier = modifier,
        )
    } else {
        DefaultColorDot(modifier = modifier)
    }
}

@Composable
private fun ColorDot(
    color: Color,
    modifier: Modifier = Modifier,
) {
    val borderModifier = if (color.alpha < 1f) {
        Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
    } else {
        Modifier
    }
    Box(
        modifier = modifier
            .size(30.dp)
            .clip(CircleShape)
            .then(borderModifier)
            .background(color = color),
    )
}

@Composable
fun DefaultColorDot(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.HdrAuto,
            contentDescription = null,
            tint = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
