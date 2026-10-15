package com.remote.androidcontroller.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ripple
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.remote.androidcontroller.network.NavigationAction
import com.remote.androidcontroller.ui.theme.DarkBorder
import com.remote.androidcontroller.ui.theme.DarkSurface
import com.remote.androidcontroller.ui.theme.EmeraldPrimary

/**
 * Authentic 3-Button Android Navigation Bar: Back, Home, Recent Apps.
 * Designed with standard minimum 48x48 dp touch hitboxes and haptic feedback.
 */
@Composable
fun AndroidNavigationBar(
    enabled: Boolean,
    onAction: (NavigationAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(DarkSurface)
            .border(width = 1.dp, color = DarkBorder, shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 1. ZURÜCK (BACK)
        NavButton(
            enabled = enabled,
            onClick = {
                runCatching { view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
                onAction(NavigationAction.BACK)
            }
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Zurück",
                tint = if (enabled) MaterialTheme.colorScheme.onSurface else Color.Gray,
                modifier = Modifier.size(24.dp)
            )
        }

        // 2. HOME (⭘)
        NavButton(
            enabled = enabled,
            onClick = {
                runCatching { view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
                onAction(NavigationAction.HOME)
            }
        ) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .border(
                        width = 2.5.dp,
                        color = if (enabled) MaterialTheme.colorScheme.onSurface else Color.Gray,
                        shape = CircleShape
                    )
            )
        }

        // 3. RECENT APPS (▢)
        NavButton(
            enabled = enabled,
            onClick = {
                runCatching { view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
                onAction(NavigationAction.RECENTS)
            }
        ) {
            Box(
                modifier = Modifier
                    .size(17.dp)
                    .border(
                        width = 2.5.dp,
                        color = if (enabled) MaterialTheme.colorScheme.onSurface else Color.Gray,
                        shape = RoundedCornerShape(3.dp)
                    )
            )
        }
    }
}

@Composable
private fun NavButton(
    enabled: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = true, radius = 28.dp),
                enabled = enabled,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}
