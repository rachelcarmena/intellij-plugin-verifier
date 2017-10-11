package com.jetbrains.pluginverifier.reporting.verification

import com.jetbrains.pluginverifier.dependencies.DependenciesGraph
import com.jetbrains.pluginverifier.misc.closeLogged
import com.jetbrains.pluginverifier.reporting.Reporter
import com.jetbrains.pluginverifier.reporting.ignoring.ProblemIgnoredEvent
import com.jetbrains.pluginverifier.results.Verdict
import com.jetbrains.pluginverifier.results.problems.Problem
import com.jetbrains.pluginverifier.results.warnings.Warning
import java.io.Closeable

/**
 * @author Sergey Patrikeev
 */
data class VerificationReporterSet(
    val verdictReporters: List<Reporter<Verdict>>,
    val messageReporters: List<Reporter<String>>,
    val progressReporters: List<Reporter<Double>>,
    val warningReporters: List<Reporter<Warning>>,
    val problemsReporters: List<Reporter<Problem>>,
    val dependenciesGraphReporters: List<Reporter<DependenciesGraph>>,
    val ignoredProblemReporters: List<Reporter<ProblemIgnoredEvent>>
) : Closeable {
  override fun close() {
    verdictReporters.forEach { it.closeLogged() }
    messageReporters.forEach { it.closeLogged() }
    progressReporters.forEach { it.closeLogged() }
    problemsReporters.forEach { it.closeLogged() }
    warningReporters.forEach { it.closeLogged() }
    dependenciesGraphReporters.forEach { it.closeLogged() }
    ignoredProblemReporters.forEach { it.closeLogged() }
  }
}