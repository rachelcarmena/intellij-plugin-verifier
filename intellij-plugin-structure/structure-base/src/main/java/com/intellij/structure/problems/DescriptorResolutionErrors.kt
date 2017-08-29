package com.intellij.structure.problems

import com.jetbrains.structure.plugin.PluginProblem

data class MultiplePluginDescriptorsInLibDirectory(val firstFileName: String,
                                                   val secondFileName: String) : PluginProblem() {
  override val level: PluginProblem.Level = PluginProblem.Level.ERROR
  override val message: String = "Found multiple plugin descriptors in plugin/lib/$firstFileName and plugin/lib/$secondFileName. Only one plugin must be bundled in a plugin distribution."
}


