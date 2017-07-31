package com.jetbrains.structure.problems

import com.jetbrains.structure.plugin.PluginProblem
import java.io.File

data class IncorrectPluginFile(val file: File) : PluginProblem() {
  override val level: Level = Level.ERROR
  override val message: String = "Incorrect plugin file ${file.name}. Must be a .zip or .jar archive or a directory."
}

data class UnableToExtractZip(val pluginFile: File) : PluginProblem() {
  override val level: Level = Level.ERROR
  override val message: String = "Unable to extract plugin zip file ${pluginFile.name}"
}