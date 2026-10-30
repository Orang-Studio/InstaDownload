package me.ibrahimsn.smoothbottombar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class SmoothBarItem(
    val icon: Painter,
    val label: String,
    val contentDescription: String? = label,
    val hasBadge: Boolean = false
)

@Composable
fun SmoothBottomBar(
    items: List<SmoothBarItem>,
    selectedIndex: Int,
    onItemSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onItemReselected: ((Int) -> Unit)? = null,
    backgroundColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    indicatorColor: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.20f),
    indicatorRadius: Dp = 14.dp,
    itemTextColor: Color = MaterialTheme.colorScheme.primary,
    itemTextSize: TextUnit = 12.sp,
    itemIconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    itemIconTintActive: Color = MaterialTheme.colorScheme.primary,
    itemBadgeColor: Color = Color(0xFFE91E63),
    itemAnimDurationMillis: Int = 220
) {
    Surface(
        color = backgroundColor,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(64.dp)
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEachIndexed { index, item ->
                val isSelected = index == selectedIndex
                val animDuration = itemAnimDurationMillis
                val interactionSource = remember { MutableInteractionSource() }

                val animatedWeight by animateFloatAsState(
                    targetValue = if (isSelected) 1.5f else 1.0f,
                    animationSpec = tween(animDuration, easing = FastOutSlowInEasing),
                    label = "SmoothBarItemWeight"
                )

                val animatedPillColor by animateColorAsState(
                    targetValue = if (isSelected) indicatorColor else Color.Transparent,
                    animationSpec = tween(animDuration),
                    label = "SmoothBarItemPill"
                )

                val animatedIconTint by animateColorAsState(
                    targetValue = if (isSelected) itemIconTintActive else itemIconTint,
                    animationSpec = tween(animDuration),
                    label = "SmoothBarItemIconTint"
                )

                val animatedTextColor by animateColorAsState(
                    targetValue = if (isSelected) itemTextColor else itemIconTint,
                    animationSpec = tween(animDuration),
                    label = "SmoothBarItemTextColor"
                )

                Box(
                    modifier = Modifier
                        .weight(animatedWeight)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(indicatorRadius))
                        .background(animatedPillColor)
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            role = Role.Tab
                        ) {
                            if (isSelected) {
                                onItemReselected?.invoke(index)
                            } else {
                                onItemSelected(index)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                painter = item.icon,
                                contentDescription = item.contentDescription,
                                tint = animatedIconTint,
                                modifier = Modifier.size(24.dp)
                            )
                            if (item.hasBadge) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .align(Alignment.TopEnd)
                                        .offset(x = 3.dp, y = (-2).dp)
                                        .clip(CircleShape)
                                        .background(itemBadgeColor)
                                )
                            }
                        }

                        AnimatedVisibility(
                            visible = isSelected,
                            enter = fadeIn(animationSpec = tween(animDuration)),
                            exit = fadeOut(animationSpec = tween(animDuration / 2))
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = item.label,
                                    color = animatedTextColor,
                                    fontSize = itemTextSize,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
