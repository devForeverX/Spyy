package com.remote.androidcontroller.ui.screens

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ripple
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remote.androidcontroller.ui.theme.DarkBorder
import com.remote.androidcontroller.ui.theme.DarkSurface
import com.remote.androidcontroller.ui.theme.DarkSurfaceElevated
import com.remote.androidcontroller.ui.theme.EmeraldPrimary
import com.remote.androidcontroller.ui.theme.TextPrimary
import com.remote.androidcontroller.ui.theme.TextSecondary

@Composable
fun VirtualKeyboardScreen(
    enabled: Boolean,
    onTextInput: (text: String, isEnter: Boolean, isBackspace: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var isShiftActive by remember { mutableStateOf(false) }
    var isSymbolsMode by remember { mutableStateOf(false) }
    var isQwertz by remember { mutableStateOf(true) } // Toggle between QWERTZ and QWERTY
    val view = LocalView.current

    val row1 = if (isSymbolsMode) listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
    else listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "ß")

    val row2 = if (isSymbolsMode) listOf("+", "×", "÷", "=", "/", "_", "<", ">", "[", "]")
    else if (isQwertz) listOf("q", "w", "e", "r", "t", "z", "u", "i", "o", "p", "ü")
    else listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p")

    val row3 = if (isSymbolsMode) listOf("!", "@", "#", "$", "%", "^", "&", "*", "(", ")")
    else if (isQwertz) listOf("a", "s", "d", "f", "g", "h", "j", "k", "l", "ö", "ä")
    else listOf("a", "s", "d", "f", "g", "h", "j", "k", "l")

    val row4Letters = if (isSymbolsMode) listOf("-", "'", "\"", ":", ";", ",", "?")
    else if (isQwertz) listOf("y", "x", "c", "v", "b", "n", "m")
    else listOf("z", "x", "c", "v", "b", "n", "m")

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(DarkSurface)
            .border(1.dp, DarkBorder, RoundedCornerShape(12.dp))
            .padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // ROW 1
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally)
        ) {
            row1.forEach { char ->
                KeyButton(
                    label = char,
                    weight = 1f,
                    enabled = enabled,
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        onTextInput(char, false, false)
                    }
                )
            }
        }

        // ROW 2
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally)
        ) {
            row2.forEach { char ->
                val display = if (isShiftActive) char.uppercase() else char
                KeyButton(
                    label = display,
                    weight = 1f,
                    enabled = enabled,
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        onTextInput(display, false, false)
                    }
                )
            }
        }

        // ROW 3
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally)
        ) {
            row3.forEach { char ->
                val display = if (isShiftActive) char.uppercase() else char
                KeyButton(
                    label = display,
                    weight = 1f,
                    enabled = enabled,
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        onTextInput(display, false, false)
                    }
                )
            }
        }

        // ROW 4 (Shift + Letters + Backspace)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally)
        ) {
            // SHIFT KEY
            KeySpecialButton(
                weight = 1.4f,
                isActive = isShiftActive,
                enabled = enabled,
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    isShiftActive = !isShiftActive
                }
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowUpward,
                    contentDescription = "Shift",
                    tint = if (isShiftActive) EmeraldPrimary else TextPrimary,
                    modifier = Modifier.height(18.dp)
                )
            }

            row4Letters.forEach { char ->
                val display = if (isShiftActive) char.uppercase() else char
                KeyButton(
                    label = display,
                    weight = 1f,
                    enabled = enabled,
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        onTextInput(display, false, false)
                    }
                )
            }

            // BACKSPACE KEY
            KeySpecialButton(
                weight = 1.4f,
                enabled = enabled,
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onTextInput("", false, true)
                }
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Backspace,
                    contentDescription = "Rücktaste",
                    tint = TextPrimary,
                    modifier = Modifier.height(18.dp)
                )
            }
        }

        // ROW 5 (?123 / Layout Switch / Space / Enter)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)
        ) {
            // ?123 toggle
            KeyTextSpecialButton(
                label = if (isSymbolsMode) "ABC" else "?123",
                weight = 1.5f,
                enabled = enabled,
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    isSymbolsMode = !isSymbolsMode
                }
            )

            // Layout toggle (DE / EN)
            KeyTextSpecialButton(
                label = if (isQwertz) "DE" else "EN",
                weight = 1.0f,
                enabled = enabled,
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    isQwertz = !isQwertz
                }
            )

            // SPACE BAR
            KeyButton(
                label = "Leertaste",
                weight = 4.5f,
                enabled = enabled,
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onTextInput(" ", false, false)
                }
            )

            // DOT .
            KeyButton(
                label = ".",
                weight = 1f,
                enabled = enabled,
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onTextInput(".", false, false)
                }
            )

            // ENTER KEY
            KeySpecialButton(
                weight = 1.8f,
                backgroundColor = EmeraldPrimary,
                enabled = enabled,
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onTextInput("", true, false)
                }
            ) {
                Text(
                    text = "↵",
                    color = Color.Black,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            }
        }
    }
}

@Composable
private fun RowScope.KeyButton(
    label: String,
    weight: Float,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .weight(weight)
            .height(44.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(DarkSurfaceElevated)
            .border(1.dp, DarkBorder, RoundedCornerShape(6.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = true),
                enabled = enabled,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (enabled) TextPrimary else TextSecondary,
            fontSize = if (label.length > 2) 12.sp else 16.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun RowScope.KeySpecialButton(
    weight: Float,
    backgroundColor: Color = DarkSurfaceElevated,
    isActive: Boolean = false,
    enabled: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .weight(weight)
            .height(44.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (isActive) DarkBorder else backgroundColor)
            .border(1.dp, DarkBorder, RoundedCornerShape(6.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = true),
                enabled = enabled,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
private fun RowScope.KeyTextSpecialButton(
    label: String,
    weight: Float,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .weight(weight)
            .height(44.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(DarkSurfaceElevated)
            .border(1.dp, DarkBorder, RoundedCornerShape(6.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = true),
                enabled = enabled,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = TextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}
