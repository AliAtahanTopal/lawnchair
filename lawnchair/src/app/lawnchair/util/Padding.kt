package app.lawnchair.util

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max

// TODO: https://mrmans0n.github.io/compose-rules/rules/#avoid-modifier-extension-factory-functions
@Suppress("ktlint:compose:modifier-composed-check")
fun Modifier.navigationBarsOrDisplayCutoutPadding(): Modifier = composed {
    val sides = WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
    val navigationBars = WindowInsets.navigationBars.only(sides).asPaddingValues()
    val displayCutout = WindowInsets.displayCutout.only(sides).asPaddingValues()
    padding(max(navigationBars, displayCutout))
}

@Composable
fun max(a: PaddingValues, b: PaddingValues) = remember(a, b) {
    object : PaddingValues {
        override fun calculateLeftPadding(layoutDirection: LayoutDirection): Dp {
            val aVal = a.calculateLeftPadding(layoutDirection)
            val bVal = b.calculateLeftPadding(layoutDirection)
            return when {
                !aVal.value.isFinite() -> bVal.let { if (it.value.isFinite()) it else 0.dp }
                !bVal.value.isFinite() -> aVal
                else -> max(aVal, bVal)
            }
        }

        override fun calculateTopPadding(): Dp {
            val aVal = a.calculateTopPadding()
            val bVal = b.calculateTopPadding()
            return when {
                !aVal.value.isFinite() -> bVal.let { if (it.value.isFinite()) it else 0.dp }
                !bVal.value.isFinite() -> aVal
                else -> max(aVal, bVal)
            }
        }

        override fun calculateRightPadding(layoutDirection: LayoutDirection): Dp {
            val aVal = a.calculateRightPadding(layoutDirection)
            val bVal = b.calculateRightPadding(layoutDirection)
            return when {
                !aVal.value.isFinite() -> bVal.let { if (it.value.isFinite()) it else 0.dp }
                !bVal.value.isFinite() -> aVal
                else -> max(aVal, bVal)
            }
        }

        override fun calculateBottomPadding(): Dp {
            val aVal = a.calculateBottomPadding()
            val bVal = b.calculateBottomPadding()
            return when {
                !aVal.value.isFinite() -> bVal.let { if (it.value.isFinite()) it else 0.dp }
                !bVal.value.isFinite() -> aVal
                else -> max(aVal, bVal)
            }
        }
    }
}

@Composable
operator fun PaddingValues.minus(b: PaddingValues): PaddingValues {
    val a = this
    return remember(a, b) {
        object : PaddingValues {
            override fun calculateLeftPadding(layoutDirection: LayoutDirection): Dp {
                val aLeft = a.calculateLeftPadding(layoutDirection)
                val bLeft = b.calculateLeftPadding(layoutDirection)
                if (!aLeft.value.isFinite() || !bLeft.value.isFinite()) return 0.dp
                return (aLeft - bLeft).coerceAtLeast(0.dp)
            }

            override fun calculateTopPadding(): Dp {
                val aTop = a.calculateTopPadding()
                val bTop = b.calculateTopPadding()
                if (!aTop.value.isFinite() || !bTop.value.isFinite()) return 0.dp
                return (aTop - bTop).coerceAtLeast(0.dp)
            }

            override fun calculateRightPadding(layoutDirection: LayoutDirection): Dp {
                val aRight = a.calculateRightPadding(layoutDirection)
                val bRight = b.calculateRightPadding(layoutDirection)
                if (!aRight.value.isFinite() || !bRight.value.isFinite()) return 0.dp
                return (aRight - bRight).coerceAtLeast(0.dp)
            }

            override fun calculateBottomPadding(): Dp {
                val aBottom = a.calculateBottomPadding()
                val bBottom = b.calculateBottomPadding()
                if (!aBottom.value.isFinite() || !bBottom.value.isFinite()) return 0.dp
                return (aBottom - bBottom).coerceAtLeast(0.dp)
            }
        }
    }
}
