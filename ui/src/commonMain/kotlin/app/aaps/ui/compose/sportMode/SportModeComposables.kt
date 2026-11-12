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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

/** Pick the scene and the duration, then start. */
@Composable
fun SportModeStartDialog(
    scenes: List<SportSceneOption>,
    preselectedId: String?,
    onStart: (sceneId: String, minutes: Int) -> Unit,
    onDismiss: () -> Unit
) {
    var sceneId by remember { mutableStateOf(preselectedId ?: scenes.firstOrNull()?.id) }
    var minutes by remember { mutableIntStateOf(60) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(IcTtActivity, contentDescription = null, tint = sportColor()) },
        title = { Text(stringResource(CoreUiStrings.sport_mode_start_title)) },
        text = {
            if (scenes.isEmpty()) Text(stringResource(CoreUiStrings.sport_mode_no_scenes))
            else Column {
                Text(stringResource(CoreUiStrings.sport_mode_scene), style = MaterialTheme.typography.titleSmall)
                scenes.forEach { scene ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = scene.id == sceneId, role = Role.RadioButton, onClick = { sceneId = scene.id })
                    ) {
                        RadioButton(selected = scene.id == sceneId, onClick = null)
                        Spacer(Modifier.width(AapsSpacing.medium))
                        Text(scene.name)
                    }
                }
                Spacer(Modifier.padding(top = AapsSpacing.medium))
                Text(stringResource(CoreUiStrings.sport_mode_duration), style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(AapsSpacing.medium)) {
                    listOf(30, 60, 90).forEach { m ->
                        FilterChip(
                            selected = minutes == m,
                            onClick = { minutes = m },
                            label = { Text(stringResource(CoreUiStrings.format_mins, m)) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            val id = sceneId
            TextButton(enabled = id != null, onClick = { if (id != null) onStart(id, minutes) }) {
                Text(stringResource(CoreUiStrings.sport_mode_start))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(CoreUiStrings.cancel)) } }
    )
}

/**
 * The sport view at the top of the overview while the sport scene runs: the bar with the time left, what the
 * loop makes of it (half basal), +15 / +30 / End, and BG | IOB | target as tiles of the same size.
 */
@Composable
fun SportModeHeader(
    state: SportModeUiState,
    bgInfo: BgInfoData?,
    iobText: String,
    targetText: String,
    onExtend: (minutes: Int) -> Unit,
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
                    stringResource(CoreUiStrings.sport_mode_bar, state.remainingText),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                TextButton(enabled = controlsEnabled, onClick = { onExtend(15) }) { Text(stringResource(CoreUiStrings.sport_mode_extend_15)) }
                TextButton(enabled = controlsEnabled, onClick = { onExtend(30) }) { Text(stringResource(CoreUiStrings.sport_mode_extend_30)) }
                TextButton(enabled = controlsEnabled, onClick = onEnd) { Text(stringResource(CoreUiStrings.sport_mode_end)) }
            }
            when {
                state.noEffect           -> Text(
                    stringResource(CoreUiStrings.sport_mode_no_effect),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )

                state.basalPercent != null -> Text(
                    stringResource(CoreUiStrings.sport_mode_basal, state.basalPercent),
                    style = MaterialTheme.typography.titleMedium,
                    color = color
                )
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
            SportTile(
                label = stringResource(CoreUiStrings.sport_mode_target),
                value = targetText,
                sub = state.endClock?.let { stringResource(CoreUiStrings.sport_mode_target_until, it) },
                valueColor = color
            )
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
