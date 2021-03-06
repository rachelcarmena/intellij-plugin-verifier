/*
 * Copyright 2000-2020 JetBrains s.r.o. and other contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */

package org.jetbrains.plugins.verifier.service.server.controllers

import com.jetbrains.plugin.structure.base.utils.rethrowIfInterrupted
import com.jetbrains.pluginverifier.filtering.IgnoreCondition
import org.jetbrains.plugins.verifier.service.server.ServerContext
import org.jetbrains.plugins.verifier.service.server.configuration.properties.AuthorizationProperties
import org.jetbrains.plugins.verifier.service.server.exceptions.AuthenticationFailedException
import org.jetbrains.plugins.verifier.service.server.views.IgnoredProblemsPage
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam

@Controller
@EnableConfigurationProperties(AuthorizationProperties::class)
class IgnoredProblemsController(
  private val authorizationProperties: AuthorizationProperties
) {

  private companion object {
    val logger: Logger = LoggerFactory.getLogger(IgnoredProblemsController::class.java)
  }

  @Autowired
  private lateinit var serverContext: ServerContext

  @GetMapping("/ignored-problems")
  fun ignoredProblemsPageEndpoint() = IgnoredProblemsPage(serverContext.serviceDAO.ignoreConditions)

  @PostMapping("/modify-ignored-problems")
  fun modifyIgnoredProblemsEndpoint(
    @RequestParam("ignored.problems") ignoredProblems: String,
    @RequestParam("admin.password") adminPassword: String
  ): String {
    if (adminPassword != authorizationProperties.password) {
      throw AuthenticationFailedException("Incorrect password")
    }
    val ignoreConditions = try {
      parseIgnoreConditions(ignoredProblems)
    } catch (e: Exception) {
      e.rethrowIfInterrupted()
      val msg = "Unable to parse ignored problems: ${e.message}"
      logger.warn(msg, e)
      throw IllegalArgumentException(msg)
    }
    serverContext.serviceDAO.replaceIgnoreConditions(ignoreConditions)
    return "redirect:/ignored-problems"
  }

  private fun parseIgnoreConditions(ignoredProblems: String) = ignoredProblems.lines()
    .map { it.trim() }
    .filterNot { it.isEmpty() }
    .map { IgnoreCondition.parseCondition(it) }
}