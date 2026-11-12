package app.aaps.ui.compose.sportMode

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.interfaces.overview.graph.BgInfoData
import app.aaps.core.interfaces.overview.graph.BgRange
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.icons.IcTtActivity
import app.aaps.core.ui.compose.navigation.icon
import app.aaps.core.ui.compose.stringResource

/** Height of the big buttons of the sport view: easy to hit while moving. */
private val SPORT_BUTTON_MIN_HEIGHT = 64.dp

/** The sport colour: the colour of the activity temp target. */
@Composable
private fun sportColor(): Color = AapsTheme.generalColors.ttActivity

/** The chip next to the temp target that starts the sport mode. */
@Composable
fun ExerciseChip(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val color = sportColor()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .border(1.dp, color, RoundedCornerShape(AapsSpacing.chipCornerRadius))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = AapsSpacing.medium, vertical = AapsSpacing.small)
    ) {
        Icon(IcTtActivity, contentDescription = null, tint = color, modifier = Modifier.size(AapsSpacing.chipIconSize))
        Spacer(Modifier.width(AapsSpacing.small))
        Text(stringResource(CoreUiStrings.sport_mode_exercise), style = MaterialTheme.typography.labelLarge, color = color)
    }
}

/**
 * The sport view at the top of the overview while half basal is on: the bar with what the loop makes of it
 * (basal in percent and per hour, the temp basal now) and End, then BG | IOB | target as tiles of the same size.
 */
@Composable
fun SportModeHeader(
    state: SportModeUiState,
    bgInfo: BgInfoData?,
    iobText: String,
    targetText: String,
    onEnd: () -> Unit,
    controlsEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    val color = sportColor()
    Column(modifier = modifier.padding(horizontal = AapsSpacing.medium, vertical = AapsSpacing.small)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(color.copy(alpha = 0.18f), RoundedCornerShape(AapsSpacing.large))
                .padding(AapsSpacing.medium)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(IcTtActivity, contentDescription = null, tint = color, modifier = Modifier.size(AapsSpacing.chipIconSize))
                Spacer(Modifier.width(AapsSpacing.small))
                Text(
                    stringResource(CoreUiStrings.sport_mode_half_basal_on),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                FilledTonalButton(enabled = controlsEnabled, onClick = onEnd) { Text(stringResource(CoreUiStrings.sport_mode_end)) }
            }
            if (state.highTarget && state.basalPercent != null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = AapsSpacing.small)) {
                    Text(
                        stringResource(CoreUiStrings.sport_mode_basal, state.basalPercent),
                        style = MaterialTheme.typography.titleLarge,
                        color = color,
                        modifier = Modifier.weight(1f)
                    )
                    if (state.basalRate != null && state.profileRate != null)
                        Text(stringResource(CoreUiStrings.sport_mode_basal_rate, state.basalRate, state.profileRate), style = MaterialTheme.typography.bodyMedium)
                }
            } else Text(
                stringResource(CoreUiStrings.sport_mode_waits_for_tt),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = AapsSpacing.small)
            )
            Row(modifier = Modifier.padding(top = AapsSpacing.extraSmall)) {
                Text(
                    state.tbrNow?.let { stringResource(CoreUiStrings.sport_mode_tbr_now, it) } ?: "",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                state.since?.let {
                    Text(stringResource(CoreUiStrings.sport_mode_since, it), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(AapsSpacing.medium),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = AapsSpacing.medium)
        ) {
            SportTile(
                label = stringResource(CoreUiStrings.sport_mode_bg),
                value = bgInfo?.bgText ?: "---",
                sub = bgInfo?.deltaText,
                valueColor = bgInfo?.bgRange?.let { bgColor(it) } ?: MaterialTheme.colorScheme.onSurface,
                strike = bgInfo?.isOutdated == true
            )
            SportTile(label = stringResource(CoreUiStrings.iob), value = iobText, sub = null, valueColor = MaterialTheme.colorScheme.onSurface)
            SportTile(label = stringResource(CoreUiStrings.sport_mode_target), value = targetText, sub = null, valueColor = color)
        }
    }
}

@Composable
private fun bgColor(range: BgRange): Color = when (range) {
    BgRange.HIGH     -> AapsTheme.generalColors.bgHigh
    BgRange.IN_RANGE -> AapsTheme.generalColors.bgInRange
    BgRange.LOW      -> AapsTheme.generalColors.bgLow
}

@Composable
private fun RowScope.SportTile(label: String, value: String, sub: String?, valueColor: Color, strike: Boolean = false) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .weight(1f)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(AapsSpacing.large))
            .padding(vertical = AapsSpacing.medium, horizontal = AapsSpacing.small)
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.headlineMedium,
            color = valueColor,
            textAlign = TextAlign.Center,
            maxLines = 1,
            textDecoration = if (strike) TextDecoration.LineThrough else TextDecoration.None
        )
        if (sub != null) Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** The big buttons under the graph: carbs, temp target, insulin. Insulin sits at the edge, away from the middle. */
@Composable
fun SportModeButtons(
    onCarbs: () -> Unit,
    onTempTarget: () -> Unit,
    onInsulin: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(AapsSpacing.medium),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AapsSpacing.medium, vertical = AapsSpacing.medium)
    ) {
        SportButton(ElementType.CARBS.icon(), stringResource(CoreUiStrings.sport_mode_carbs), onCarbs, enabled)
        SportButton(ElementType.TEMP_TARGET_MANAGEMENT.icon(), stringResource(CoreUiStrings.sport_mode_tt), onTempTarget, enabled)
        SportButton(ElementType.INSULIN.icon(), stringResource(CoreUiStrings.sport_mode_insulin), onInsulin, enabled)
    }
}

@Composable
private fun RowScope.SportButton(icon: ImageVector, label: String, onClick: () -> Unit, enabled: Boolean) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .weight(1f)
            .heightIn(min = SPORT_BUTTON_MIN_HEIGHT)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(AapsSpacing.chipIconSize))
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

/** "Activity finished": what happened during the sport scene. */
@Composable
fun SportSummaryDialog(lines: List<SportSummaryLine>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(IcTtActivity, contentDescription = null, tint = sportColor()) },
        title = { Text(stringResource(CoreUiStrings.sport_summary_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AapsSpacing.small)) {
                lines.forEach { line ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(line.label, modifier = Modifier.weight(1f))
                        Text(line.value, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(CoreUiStrings.ok)) } }
    )
}
