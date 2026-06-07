package com.opensiri.agent.bootstrap.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opensiri.agent.bootstrap.ui.theme.*

@Composable
fun ChainProgressScreen(viewModel: InstallViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showLog by remember { mutableStateOf(false) }

    OneZionTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Background,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 40.dp)
            ) {
                // Status bar spacer
                Spacer(Modifier.height(8.dp))

                // Title
                Text(
                    text = "Setting up",
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontSize = 26.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = OnSurface,
                    ),
                )
                Text(
                    text = "OneZion Agent",
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontSize = 26.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Primary,
                    ),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Preparing your phone's AI brain",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = OnSurfaceVariant,
                        fontSize = 14.sp,
                    ),
                )

                Spacer(Modifier.height(28.dp))

                // Overall progress bar
                OverallProgress(state)

                Spacer(Modifier.height(24.dp))

                // Chain of phases
                ChainPhases(state)

                Spacer(Modifier.height(24.dp))

                // Bottom actions
                BottomActions(
                    hasError = state.hasError,
                    isComplete = state.isComplete,
                    isRunning = state.isRunning,
                    onRetry = { viewModel.retryInstall() },
                    onCopyLogs = { /* TODO: clipboard */ },
                )

                Spacer(Modifier.height(16.dp))

                // Log toggle
                LogSection(
                    showLog = showLog,
                    logLines = state.logLines,
                    onToggle = { showLog = !showLog },
                )

                Spacer(Modifier.height(40.dp))
            }
        }

        // Auto-start on first composition
        LaunchedEffect(Unit) {
            if (!state.isRunning && !state.isComplete && !state.hasError) {
                viewModel.startInstall()
            }
        }
    }
}

@Composable
private fun OverallProgress(state: InstallState) {
    val progress = if (state.totalMB > 0) state.downloadedMB / state.totalMB else 0f

    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = 0.06f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction = progress.coerceIn(0f, 1f))
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        brush = Brush.horizontalGradient(listOf(Primary, Color(0xFFD0BCFF)))
                    )
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = if (state.isComplete) "Ready!" else "Phase ${state.currentPhase + 1}/6 · ${state.phases.getOrNull(state.currentPhase)?.name ?: "..."}",
                style = MaterialTheme.typography.bodySmall.copy(
                    color = OnSurfaceVariant,
                    fontSize = 11.sp,
                ),
            )
            Text(
                text = "${state.downloadedMB.toInt()} / ${state.totalMB.toInt()} MB",
                style = MaterialTheme.typography.bodySmall.copy(
                    color = OnSurfaceVariant,
                    fontSize = 11.sp,
                ),
            )
        }
    }
}

@Composable
private fun ChainPhases(state: InstallState) {
    Box(
        modifier = Modifier.padding(start = 28.dp),
    ) {
        // Vertical timeline line
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(x = (-17).dp, y = 16.dp)
                .width(2.dp)
                .height((state.phases.size * 100).dp) // approximate
                .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(1.dp))
        )

        Column {
            state.phases.forEach { phase ->
                PhaseCard(
                    phase = phase,
                    currentStep = if (phase.id == state.currentPhase) state.currentStep else -1,
                )
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun PhaseCard(phase: Phase, currentStep: Int) {
    val isActive = phase.state == PhaseState.ACTIVE
    val isCompleted = phase.state == PhaseState.COMPLETED

    Row {
        // Node circle
        PhaseNode(
            state = phase.state,
            content = when {
                isCompleted -> "✓"
                else -> "${phase.id + 1}"
            },
        )

        Spacer(Modifier.width(12.dp))

        // Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isActive) Primary.copy(alpha = 0.08f)
                else Color.White.copy(alpha = 0.03f)
            ),
            border = if (isActive) CardDefaults.outlinedCardBorder().copy(
                brush = Brush.linearGradient(listOf(Primary.copy(alpha = 0.2f), Color.Transparent))
            ) else null,
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                // Header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(phase.icon, fontSize = 18.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = phase.name,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = FontWeight.Medium,
                            color = OnSurface,
                            fontSize = 15.sp,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    Badge(phase)
                }

                Spacer(Modifier.height(4.dp))
                Text(
                    text = phase.desc,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = OnSurfaceVariant,
                        fontSize = 12.sp,
                    ),
                )

                // Sub-steps (only when active)
                AnimatedVisibility(visible = isActive) {
                    Column(modifier = Modifier.padding(top = 10.dp)) {
                        phase.steps.forEachIndexed { index, step ->
                            val stepState = when {
                                index < currentStep -> StepState.DONE
                                index == currentStep -> StepState.CURRENT
                                else -> StepState.PENDING
                            }
                            SubStepRow(step, stepState)
                        }
                    }
                }

                // Summary (when completed)
                if (isCompleted) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(
                            text = "📦 ${phase.sizeMB} MB",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = OnSurfaceVariant,
                                fontSize = 10.sp,
                            ),
                        )
                        Text(
                            text = "⏱ ${(phase.sizeMB * 1.5).toInt()}s",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = OnSurfaceVariant,
                                fontSize = 10.sp,
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PhaseNode(state: PhaseState, content: String) {
    val infiniteTransition = rememberInfiniteTransition()
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse,
        ),
    )

    val bg = when (state) {
        PhaseState.COMPLETED -> Success
        PhaseState.ACTIVE -> Primary
        PhaseState.FAILED -> Error
        PhaseState.PENDING -> Color.Transparent
    }
    val border = if (state == PhaseState.PENDING) Color.White.copy(alpha = 0.12f) else Color.Transparent

    Box(
        modifier = Modifier
            .size(24.dp)
            .then(
                if (state == PhaseState.ACTIVE)
                    Modifier.shadow(
                        elevation = (8f * pulseScale).dp,
                        shape = CircleShape,
                        ambientColor = ActiveGlow,
                        spotColor = ActiveGlow,
                    )
                else Modifier
            )
            .clip(CircleShape)
            .background(bg, CircleShape)
            .then(
                if (state == PhaseState.PENDING)
                    Modifier.background(Color.Transparent, CircleShape)
                else Modifier
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (state == PhaseState.PENDING) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(Color.Transparent)
            )
        }
        Text(
            text = content,
            color = when (state) {
                PhaseState.PENDING -> Color.White.copy(alpha = 0.3f)
                else -> Color.White
            },
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
    }

    // Border for pending
    if (state == PhaseState.PENDING) {
        // We draw border visually through the parent's background
        // The Box already has a transparent bg with outlineColor
    }
}

@Composable
private fun Badge(phase: Phase) {
    val (bg, fg) = when (phase.state) {
        PhaseState.COMPLETED -> Pair(Success.copy(alpha = 0.15f), SuccessLight)
        PhaseState.ACTIVE -> Pair(Primary.copy(alpha = 0.2f), Color(0xFFD0BCFF))
        PhaseState.FAILED -> Pair(Error.copy(alpha = 0.15f), Error)
        PhaseState.PENDING -> Pair(Color.White.copy(alpha = 0.05f), Color.White.copy(alpha = 0.3f))
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = bg,
    ) {
        Text(
            text = phase.badge,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.bodySmall.copy(
                color = fg,
                fontSize = 11.sp,
            ),
        )
    }
}

@Composable
private fun SubStepRow(step: SubStep, state: StepState) {
    val (icon, color) = when (state) {
        StepState.DONE -> Pair("✓", SuccessLight.copy(alpha = 0.7f))
        StepState.CURRENT -> Pair("◉", Color(0xFFD0BCFF))
        StepState.PENDING -> Pair("○", Color.White.copy(alpha = 0.3f))
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .background(
                if (state == StepState.CURRENT) Primary.copy(alpha = 0.1f)
                else Color.White.copy(alpha = 0.02f),
                RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = icon,
            fontSize = 11.sp,
            modifier = Modifier.width(16.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = step.name,
            style = MaterialTheme.typography.bodySmall.copy(
                color = color,
                fontSize = 11.sp,
            ),
            modifier = Modifier.weight(1f),
        )
        Text(
            text = step.size,
            style = MaterialTheme.typography.bodySmall.copy(
                color = color.copy(alpha = 0.5f),
                fontSize = 10.sp,
            ),
        )
    }
}

@Composable
private fun BottomActions(
    hasError: Boolean,
    isComplete: Boolean,
    isRunning: Boolean,
    onRetry: () -> Unit,
    onCopyLogs: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedButton(
            onClick = onCopyLogs,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = OnSurfaceVariant,
            ),
            border = ButtonDefaults.outlinedButtonBorder.copy(
                brush = Brush.linearGradient(listOf(Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.02f)))
            ),
        ) {
            Text("📋 Copy Logs", fontSize = 14.sp)
        }

        if (hasError && !isRunning) {
            Button(
                onClick = onRetry,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Primary,
                ),
            ) {
                Text("🔄 Retry", fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun LogSection(
    showLog: Boolean,
    logLines: List<LogLine>,
    onToggle: () -> Unit,
) {
    Column {
        Text(
            text = if (showLog) "▲ Hide install log" else "▼ Show install log",
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle() }
                .padding(8.dp),
            style = MaterialTheme.typography.bodySmall.copy(
                color = OnSurfaceVariant,
                fontSize = 12.sp,
            ),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )

        AnimatedVisibility(visible = showLog) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 120.dp),
                shape = RoundedCornerShape(12.dp),
                color = Color.Black.copy(alpha = 0.3f),
            ) {
                LazyColumn(
                    modifier = Modifier.padding(12.dp),
                ) {
                    items(logLines.takeLast(50)) { line ->
                        val color = when (line.type) {
                            "error" -> Error
                            "warn" -> Warn
                            "success" -> SuccessLight
                            else -> Color.White.copy(alpha = 0.4f)
                        }
                        Text(
                            text = line.message,
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = color,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                            ),
                            modifier = Modifier.padding(vertical = 1.dp),
                        )
                    }
                }
            }
        }
    }
}
