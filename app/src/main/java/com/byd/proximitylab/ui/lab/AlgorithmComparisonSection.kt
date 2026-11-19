package com.byd.proximitylab.ui.lab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.byd.proximitylab.comparison.ComparisonScoreSummary
import com.byd.proximitylab.comparison.ComparisonSessionContext
import com.byd.proximitylab.comparison.ComparisonTarget
import com.byd.proximitylab.comparison.ComparisonTrialResult
import com.byd.proximitylab.comparison.ConfigScoreSummary
import com.byd.proximitylab.comparison.ScoredTrialEvent
import com.byd.proximitylab.comparison.SessionComparison
import com.byd.proximitylab.comparison.TrialOutcome
import com.byd.proximitylab.session.SessionSummary
import com.byd.proximitylab.session.durationMillis
import com.byd.proximitylab.ui.chart.RssiChart
import java.util.Locale

/**
 * 10 的实验室对比区：选择同一 Session 和一套或多套配置，调用 09 回放并按人工参考评分。
 *
 * 界面只消费 [AlgorithmLabUiState.comparison]；运行、存储和评分都在 ViewModel/Service 中完成。
 */
@Composable
internal fun AlgorithmComparisonSection(
    state: AlgorithmLabUiState,
    onSelectSession: (String) -> Unit,
    onToggleTarget: (ComparisonTarget) -> Unit,
    onEarlyToleranceChange: (String) -> Unit,
    onLateToleranceChange: (String) -> Unit,
    onRun: () -> Unit,
) {
    val comparison = state.comparison
    if (!comparison.available) {
        return
    }

    HorizontalDivider()

    Text(
        modifier = Modifier.testTag("lab_compare_section"),
        text = "Session 对比与评分",
        style = MaterialTheme.typography.titleMedium,
    )
    Text(
        text = "选择同一 Session 和一套或多套算法配置，用 09 回放同一时间线。" +
            "评分窗口只用于评价人工参考，不进入 RSSI 判定。",
        style = MaterialTheme.typography.bodySmall,
    )

    comparison.message?.let { message ->
        Text(
            modifier = Modifier.testTag("lab_compare_message"),
            text = message,
            style = MaterialTheme.typography.bodyMedium,
        )
    }

    if (comparison.sessions.isEmpty()) {
        Text(
            modifier = Modifier.testTag("lab_compare_sessions_empty"),
            text = "尚无已保存的 Session。请先在采样页完成一次采样或模拟测试。",
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }

    Text(text = "选择 Session", style = MaterialTheme.typography.titleSmall)
    comparison.sessions.take(MAX_COMPARISON_SESSIONS).forEach { summary ->
        ComparisonSessionRow(
            summary = summary,
            selected = summary.session.id == comparison.selectedSessionId,
            onClick = { onSelectSession(summary.session.id) },
        )
    }
    if (comparison.sessions.size > MAX_COMPARISON_SESSIONS) {
        Text(
            text = "共 ${comparison.sessions.size} 个 Session，仅列出最近 $MAX_COMPARISON_SESSIONS 个。",
            style = MaterialTheme.typography.bodySmall,
        )
    }

    comparison.context?.let { context ->
        Text(
            modifier = Modifier.testTag("lab_compare_context"),
            text = comparisonContextText(context),
            style = MaterialTheme.typography.bodySmall,
        )
    }

    Text(text = "选择配置（可多选）", style = MaterialTheme.typography.titleSmall)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (comparison.context?.configSnapshot != null) {
            val target = ComparisonTarget.OriginalSnapshot
            FilterChip(
                modifier = Modifier.testTag("lab_compare_original"),
                selected = target in comparison.selectedTargets,
                onClick = { onToggleTarget(target) },
                label = { Text("当次配置（快照）") },
            )
        }
        state.configs.forEach { config ->
            val target = ComparisonTarget.Config(config.configId)
            FilterChip(
                modifier = Modifier.testTag("lab_compare_config_${config.configId}"),
                selected = target in comparison.selectedTargets,
                onClick = { onToggleTarget(target) },
                label = {
                    Text(
                        if (config.configId == state.selectedConfigId) {
                            "${config.name}（当前配置）"
                        } else {
                            config.name
                        },
                    )
                },
            )
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            modifier = Modifier.weight(1f).testTag("lab_compare_early_window"),
            value = comparison.earlyToleranceSeconds,
            onValueChange = onEarlyToleranceChange,
            label = { Text("提前容差（秒）") },
            singleLine = true,
        )
        OutlinedTextField(
            modifier = Modifier.weight(1f).testTag("lab_compare_late_window"),
            value = comparison.lateToleranceSeconds,
            onValueChange = onLateToleranceChange,
            label = { Text("迟到容差（秒）") },
            singleLine = true,
        )
    }
    Text(
        text = "评分窗 = 期望触发标记 − 提前容差 … 标记 + 迟到容差，两端都记为按时。" +
            "时间窗仅用于评分，按实测校准。",
        style = MaterialTheme.typography.bodySmall,
    )
    comparison.windowError?.let { error ->
        Text(
            modifier = Modifier.testTag("lab_compare_window_error"),
            text = error,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }

    Button(
        modifier = Modifier.fillMaxWidth().testTag("lab_compare_run"),
        enabled = comparison.selectedSessionId != null &&
            comparison.selectedTargets.isNotEmpty() &&
            !comparison.running,
        onClick = onRun,
    ) {
        Text(if (comparison.running) "评分中…" else "对比并评分")
    }

    comparison.result?.let { result ->
        ComparisonResultCard(result)
    }
}

@Composable
private fun ComparisonSessionRow(
    summary: SessionSummary,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val session = summary.session
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .testTag("lab_compare_session_${session.id}")
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(
                text = "${session.name} · ${session.samplingType.label} · ${session.status.label}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "标签 ${session.evaluationLabel.label} · 样本 ${summary.observationCount} · " +
                    "覆盖 ${formatComparisonSeconds(session.durationMillis)}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ComparisonResultCard(result: SessionComparison) {
    HorizontalDivider()
    Text(
        modifier = Modifier.testTag("lab_compare_result_title"),
        text = "对比结果 · ${result.session.name}",
        style = MaterialTheme.typography.titleSmall,
    )
    Text(
        modifier = Modifier.testTag("lab_compare_window"),
        text = comparisonWindowText(result),
        style = MaterialTheme.typography.bodySmall,
    )
    RssiChart(
        data = result.chart,
        testTagPrefix = "lab_compare_chart",
    )

    if (result.failures.isNotEmpty()) {
        Text(
            modifier = Modifier.testTag("lab_compare_failures"),
            text = "未纳入：${result.failures.joinToString("；") { failure ->
                "${failure.label}：${failure.reason}"
            }}",
            style = MaterialTheme.typography.bodySmall,
        )
    }

    Text(text = "评分汇总", style = MaterialTheme.typography.titleSmall)
    comparisonSummaryText(result.summary).forEachIndexed { index, text ->
        Text(
            modifier = Modifier.testTag("lab_compare_summary_$index"),
            text = text,
            style = MaterialTheme.typography.bodySmall,
        )
    }

    result.trials.forEachIndexed { index, trial ->
        ComparisonTrialCard(index = index, trial = trial)
    }
}

@Composable
private fun ComparisonTrialCard(
    index: Int,
    trial: ComparisonTrialResult,
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth().testTag("lab_compare_result_$index"),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                modifier = Modifier.testTag("lab_compare_outcome_$index"),
                text = "结果：${trial.score.outcome.label}",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                modifier = Modifier.testTag("lab_compare_score_$index"),
                text = comparisonTrialScoreText(trial),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                modifier = Modifier.testTag("lab_compare_result_config_$index"),
                text = comparisonTrialConfigText(trial),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                modifier = Modifier.testTag("lab_compare_events_$index"),
                text = comparisonTrialEventsText(trial),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                modifier = Modifier.testTag("lab_compare_states_$index"),
                text = comparisonTrialStatesText(trial),
                style = MaterialTheme.typography.bodySmall,
            )
            if (trial.score.diagnostics.isNotEmpty()) {
                Text(
                    modifier = Modifier.testTag("lab_compare_diagnostics_$index"),
                    text = "诊断：${trial.score.diagnostics.joinToString("；")}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

internal fun comparisonContextText(context: ComparisonSessionContext): String {
    val evaluation = context.evaluation
    val basis = when {
        evaluation.isNegativeSample -> "评分依据：无需触发（负样本）。"
        evaluation.isPositiveSample -> "评分依据：唯一期望触发标记 " +
            formatSessionOffset(
                requireNotNull(evaluation.expectedTriggerAtEpochMillis) -
                    context.session.startedAtEpochMillis,
            ) + "。"

        evaluation.expectedTriggerConflict -> "评分依据：多个期望触发参考点，跳过评分。"
        evaluation.missingExpectedTrigger -> "评分依据：覆盖范围内缺少期望触发标记。"
        else -> "评分依据：未标记，仅展示诊断。"
    }
    val snapshot = context.configSnapshot?.let { value ->
        "${value.name}（${value.config.algorithm.name}，快照 ${value.snapshotId}）"
    } ?: "无"
    return buildString {
        append("人工参考：覆盖内 ${evaluation.markersInCoverage.size} 个 / 共 ${context.markers.size} 个")
        append(" · 期望触发 ${evaluation.expectedTriggerMarkersInCoverage.size} 个")
        if (evaluation.expectedTriggerConflict) {
            append("（冲突）")
        }
        append(" · 标签 ${evaluation.label.label} · $basis 当次配置：$snapshot")
    }
}

internal fun comparisonWindowText(comparison: SessionComparison): String {
    val markerAt = comparison.evaluation.expectedTriggerAtEpochMillis
    if (markerAt != null) {
        val start = comparison.window.startAt(markerAt)
        val end = comparison.window.endAt(markerAt)
        val sessionStart = comparison.session.startedAtEpochMillis
        return "评分窗口（单位：秒）：标记 ${formatSessionOffset(markerAt - sessionStart)} · " +
            "窗起点 ${formatSessionOffset(start - sessionStart)} · " +
            "窗终点 ${formatSessionOffset(end - sessionStart)} · " +
            "相对标记 ${formatSignedSeconds(start - markerAt)} / ${formatSignedSeconds(end - markerAt)}"
    }
    return if (comparison.evaluation.isNegativeSample) {
        "评分窗口（单位：秒）：负样本不设允许窗口"
    } else {
        "评分窗口（单位：秒）：无评分依据"
    }
}

internal fun comparisonTrialScoreText(trial: ComparisonTrialResult): String {
    val score = trial.score
    return buildString {
        when (score.outcome) {
            TrialOutcome.EARLY,
            TrialOutcome.ON_TIME,
            TrialOutcome.LATE,
            -> {
                val first = score.firstEvent
                append("首条事件 ")
                append(first?.let { event -> formatSessionOffset(event.atSessionMillis) } ?: "无")
                append("（相对标记 ")
                append(first?.offsetFromMarkerMillis?.let(::formatSignedSeconds) ?: "-")
                append("）")
                append(" · 评分窗 ")
                append(score.windowStartSessionMillis?.let(::formatSessionOffset) ?: "-")
                append("–")
                append(score.windowEndSessionMillis?.let(::formatSessionOffset) ?: "-")
                append("（含端点）")
                if (score.extraEventCount > 0) {
                    append(" · 额外事件 ${score.extraEventCount} 条（重复）")
                }
            }

            TrialOutcome.MISSED -> {
                append(score.missedCause?.label ?: "漏触发")
                append(" · ")
                append(score.skipDetail)
            }

            TrialOutcome.FALSE_TRIGGER -> {
                append("误触发事件 ${score.falseTriggerCount} 条")
                append(" · ")
                append(score.skipDetail)
            }

            TrialOutcome.NO_FALSE_TRIGGER -> append(score.skipDetail)

            TrialOutcome.INSUFFICIENT_OBSERVATION -> append(score.skipDetail)

            TrialOutcome.SKIPPED -> {
                append(score.skipReason?.label ?: "缺少评分依据")
                append(" · ")
                append(score.skipDetail)
            }
        }
    }
}

internal fun comparisonTrialConfigText(trial: ComparisonTrialResult): String = buildString {
    append("配置：${trial.config.name}")
    append(" · 来源 ${trial.configSource.label}")
    append(" · 快照 ${trial.config.snapshotId}")
    append(" · ")
    append(versionText("算法", trial.versions.snapshotAlgorithmVersion, trial.versions.executedAlgorithmVersion))
    append(" · ")
    append(
        versionText(
            "触发规则",
            trial.versions.snapshotTriggerRuleVersion,
            trial.versions.executedTriggerRuleVersion,
        ),
    )
    append(" · 回放 ${trial.outcome.label}")
    if (trial.isOriginalRunConfig) {
        append(" · 当次运行配置")
    }
    if (trial.isCurrentSelection) {
        append(" · 当前配置")
    }
    if (trial.versions.versionMismatch) {
        append(" · 版本差异：")
        append(
            trial.versions.differences.joinToString("，") { difference ->
                "${difference.kind.label} ${difference.recordedValue} → ${difference.executedValue}"
            },
        )
    }
}

internal fun comparisonTrialEventsText(trial: ComparisonTrialResult): String {
    if (trial.score.events.isEmpty()) {
        return "事件：0 条"
    }
    return "事件：" + trial.score.events.joinToString(" · ") { event ->
        val time = event.sampleOffsetText()
        "${event.role.label} $time ${event.type.name}/${event.reason.name} ${event.rawRssiDbm} dBm"
    }
}

internal fun comparisonTrialStatesText(trial: ComparisonTrialResult): String {
    val states = trial.replay.states.joinToString(" · ") { state ->
        "${formatSessionOffset(state.atSessionMillis)} ${state.state.name}/${state.reason.name}"
    }
    val final = trial.replay.finalState
    return "回放状态：$states · 最终 ${final.state.name}/${final.reason.name} " +
        "@ ${formatSessionOffset(final.atSessionMillis)}"
}

internal fun comparisonSummaryText(summary: ComparisonScoreSummary): List<String> =
    summary.configs.map(::configSummaryText)

internal fun configSummaryText(config: ConfigScoreSummary): String = buildString {
    append("${config.configName}：纳入评分 ${config.includedSessionCount} 个 Session")
    append(" · 剔除 ${config.excludedSessionCount} 个")
    if (config.exclusions.isNotEmpty()) {
        append("（")
        append(
            config.exclusions.joinToString("；") { exclusion ->
                "${exclusion.sessionName}：${exclusion.reason}"
            },
        )
        append("）")
    }
    append(" · ")
    append(
        listOf(
            TrialOutcome.EARLY,
            TrialOutcome.ON_TIME,
            TrialOutcome.LATE,
            TrialOutcome.MISSED,
            TrialOutcome.FALSE_TRIGGER,
            TrialOutcome.NO_FALSE_TRIGGER,
        ).joinToString(" / ") { outcome ->
            "${outcome.label} ${config.outcomeCounts[outcome] ?: 0}"
        },
    )
    append(" · 额外事件 ${config.extraEventCount} · 误触发事件 ${config.falseTriggerCount}")
}

internal fun formatComparisonSeconds(durationMillis: Long): String =
    String.format(Locale.ROOT, "%.1f 秒", durationMillis / 1_000.0)

internal fun formatSessionOffset(offsetMillis: Long): String =
    String.format(Locale.ROOT, "%+.1f 秒", offsetMillis / 1_000.0)

internal fun formatSignedSeconds(offsetMillis: Long): String =
    String.format(Locale.ROOT, "%+.1f 秒", offsetMillis / 1_000.0)

private fun versionText(name: String, recorded: String, executed: String): String =
    if (recorded == executed) {
        "$name $executed"
    } else {
        "$name $recorded → 执行 $executed"
    }

private fun ScoredTrialEvent.sampleOffsetText(): String =
    offsetFromMarkerMillis?.let(::formatSignedSeconds) ?: formatSessionOffset(atSessionMillis)

private const val MAX_COMPARISON_SESSIONS = 8
