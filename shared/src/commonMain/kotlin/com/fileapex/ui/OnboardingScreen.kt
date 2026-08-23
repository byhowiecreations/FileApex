package com.fileapex.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fileapex.i18n.stringRes
import com.fileapex.platform.OnboardingPermissionStep

/**
 * First-run onboarding: intro plus one grant card at a time (paginated when needed).
 */
@Composable
fun OnboardingScreen(
    steps: List<OnboardingPermissionStep>,
    deniedStepIds: Set<String>,
    onGrantStep: (stepId: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val focusedStepId = steps.firstOrNull { !it.granted }?.id

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        val pages = remember(steps, maxHeight) {
            val firstPageCards = if (maxHeight < 640.dp) 1 else 2
            paginateOnboardingSteps(steps, cardsOnFirstPage = firstPageCards)
        }
        val pageIndex = remember(steps, focusedStepId, pages) {
            pages.indexOfFirst { page -> page.any { it.id == focusedStepId } }
                .coerceAtLeast(0)
        }
        val page = pages[pageIndex]
        val showIntro = pageIndex == 0

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                if (showIntro) {
                    OnboardingIntroHeader()
                    Spacer(modifier = Modifier.height(20.dp))
                } else {
                    Text(
                        text = stringRes("permissions_of", pageIndex + 1, pages.size),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
                page.forEach { step ->
                    OnboardingPermissionCard(
                        step = step,
                        focused = step.id == focusedStepId,
                        denied = step.id in deniedStepIds,
                        onGrant = { onGrantStep(step.id) }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
            if (pages.size > 1) {
                OnboardingPageIndicator(
                    pageIndex = pageIndex,
                    pageCount = pages.size
                )
            }
        }
    }
}

@Composable
private fun OnboardingIntroHeader() {
    Text(
        text = stringRes("onboarding_intro_title"),
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(modifier = Modifier.height(10.dp))
    Text(
        text = stringRes("onboarding_wifi_intro"),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(modifier = Modifier.height(14.dp))
    Text(
        text = stringRes("onboarding_tap_grant"),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun OnboardingPermissionCard(
    step: OnboardingPermissionStep,
    focused: Boolean,
    denied: Boolean,
    onGrant: () -> Unit
) {
    val containerColor = when {
        step.granted -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        focused -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        else -> MaterialTheme.colorScheme.surface
    }
    val elevation = if (focused && !step.granted) 4.dp else 1.dp
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = elevation)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = step.permissionName,
                style = MaterialTheme.typography.labelLarge,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = step.reason,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (step.granted) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = stringRes("granted"),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            } else {
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onGrant,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = focused
                ) {
                    Text(if (focused) stringRes("grant") else stringRes("grant_next"))
                }
                if (denied) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = step.deniedHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else if (!focused) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringRes("complete_highlighted_first"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun OnboardingPageIndicator(pageIndex: Int, pageCount: Int) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
    ) {
        Text(
            text = stringRes("page_of", pageIndex + 1, pageCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (pageIndex + 1 < pageCount) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringRes("onboarding_grant_then_continue"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** Splits steps across pages so each screen fits ~2 cards under the intro (3 on tall layouts). */
internal fun paginateOnboardingSteps(
    steps: List<OnboardingPermissionStep>,
    cardsOnFirstPage: Int = 2,
    cardsOnOtherPages: Int = 3
): List<List<OnboardingPermissionStep>> {
    if (steps.isEmpty()) return emptyList()
    val pages = mutableListOf<List<OnboardingPermissionStep>>()
    var index = 0
    val firstCount = cardsOnFirstPage.coerceAtMost(steps.size)
    pages += steps.subList(index, index + firstCount)
    index += firstCount
    while (index < steps.size) {
        val end = (index + cardsOnOtherPages).coerceAtMost(steps.size)
        pages += steps.subList(index, end)
        index = end
    }
    return pages
}
