package com.jetbrains.pluginverifier

import com.jetbrains.plugin.structure.intellij.version.IdeVersion
import com.jetbrains.pluginverifier.repository.PluginInfo

sealed class VerificationTarget {

  data class Ide(val ideVersion: IdeVersion) : VerificationTarget()

  data class Plugin(val plugin: PluginInfo) : VerificationTarget()

}