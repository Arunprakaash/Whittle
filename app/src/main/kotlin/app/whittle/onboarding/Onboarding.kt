package app.whittle.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import app.whittle.R
import app.whittle.design.components.WelcomeMascot
import app.whittle.design.components.WhittleIcon
import app.whittle.design.components.segmentedShape
import app.whittle.design.theme.WhittleTheme
import app.whittle.design.tokens.WhittleHaptic
import app.whittle.design.tokens.WhittleRadius
import app.whittle.design.tokens.WhittleSize
import app.whittle.design.tokens.WhittleSpace

/** First run's opening: what Whittle does, before it asks for anything. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WelcomeScreen(onStart: () -> Unit) {
    val haptics = WhittleTheme.haptics
    val colors = MaterialTheme.colorScheme
    val points = listOf(
        "preview" to R.string.welcome_point_apps,
        "hub" to R.string.welcome_point_connections,
        "lock" to R.string.welcome_point_private,
    )
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.surface)
            .safeDrawingPadding()
            .padding(horizontal = WhittleSpace.xxl),
    ) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(WhittleSpace.xxxl, Alignment.CenterVertically),
        ) {
            Box(Modifier.background(colors.primary, CircleShape).padding(WhittleSpace.l)) { WelcomeMascot() }
            Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.m)) {
                Text(stringResource(R.string.welcome_title), style = MaterialTheme.typography.headlineMedium)
                Text(stringResource(R.string.welcome_body), style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
            }
            Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.hairline)) {
                points.forEachIndexed { index, (icon, text) -> Point(icon, stringResource(text), segmentedShape(index, points.size)) }
            }
        }
        Column(Modifier.padding(top = WhittleSpace.l, bottom = WhittleSpace.xxl), verticalArrangement = Arrangement.spacedBy(WhittleSpace.s)) {
            Button(
                onClick = { haptics.perform(WhittleHaptic.Tap); onStart() },
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.fillMaxWidth().height(WhittleSize.Button.large),
            ) {
                Text(stringResource(R.string.welcome_start), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(WhittleSpace.s))
                WhittleIcon("arrow_forward", size = WhittleSize.Icon.l)
            }
            Text(
                stringResource(R.string.welcome_time),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** One thing Whittle does: an icon on a rounded tile, then a line. */
@Composable
private fun Point(icon: String, text: String, shape: RoundedCornerShape) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().background(colors.surfaceContainerLow, shape).padding(horizontal = WhittleSpace.l, vertical = WhittleSpace.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WhittleSpace.m),
    ) {
        Box(
            Modifier.size(WhittleSize.Tile.m).background(colors.secondaryContainer, RoundedCornerShape(WhittleRadius.m)),
            contentAlignment = Alignment.Center,
        ) { WhittleIcon(icon, size = WhittleSize.Icon.l, tint = colors.onSecondaryContainer) }
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Where first run is: one bar per step, filled up to [current], and "Step 2 of 2". */
@Composable
fun OnboardingSteps(current: Int, total: Int, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WhittleSpace.xs)) {
        repeat(total) { index ->
            Box(Modifier.size(WhittleSize.Tile.s, WhittleSpace.xs).background(if (index < current) colors.primary else colors.surfaceContainerHigh, CircleShape))
        }
        Text(
            stringResource(R.string.onboarding_step, current, total),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(start = WhittleSpace.s),
        )
    }
}

/** First run's steps that have a bar: Linux, then the model. */
const val ONBOARDING_STEPS = 2
const val ONBOARDING_STEP_LINUX = 1
const val ONBOARDING_STEP_MODEL = 2
