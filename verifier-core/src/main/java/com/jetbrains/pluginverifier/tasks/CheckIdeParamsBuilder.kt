package com.jetbrains.pluginverifier.tasks

import com.jetbrains.plugin.structure.intellij.version.IdeVersion
import com.jetbrains.pluginverifier.dependencies.resolution.IdeDependencyFinder
import com.jetbrains.pluginverifier.misc.closeOnException
import com.jetbrains.pluginverifier.options.CmdOpts
import com.jetbrains.pluginverifier.options.OptionsParser
import com.jetbrains.pluginverifier.parameters.jdk.JdkDescriptor
import com.jetbrains.pluginverifier.plugin.PluginCoordinate
import com.jetbrains.pluginverifier.plugin.PluginDetailsProvider
import com.jetbrains.pluginverifier.repository.PluginIdAndVersion
import com.jetbrains.pluginverifier.repository.PluginRepository
import com.jetbrains.pluginverifier.repository.UpdateInfo
import com.jetbrains.pluginverifier.utils.IdeResourceUtil
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.IOException

class CheckIdeParamsBuilder(val pluginRepository: PluginRepository, val pluginDetailsProvider: PluginDetailsProvider) : TaskParametersBuilder {
  override fun build(opts: CmdOpts, freeArgs: List<String>): CheckIdeParams {
    if (freeArgs.isEmpty()) {
      throw IllegalArgumentException("You have to specify IDE to check. For example: \"java -jar verifier.jar check-ide ~/EAPs/idea-IU-133.439\"")
    }
    val ideFile = File(freeArgs[0])
    if (!ideFile.isDirectory) {
      throw IllegalArgumentException("IDE path must be a directory: " + ideFile)
    }
    OptionsParser.createIdeDescriptor(ideFile, opts).closeOnException { ideDescriptor ->
      val jdkDescriptor = JdkDescriptor(OptionsParser.getJdkDir(opts))
      val externalClassesPrefixes = OptionsParser.getExternalClassesPrefixes(opts)
      OptionsParser.getExternalClassPath(opts).closeOnException { externalClassPath ->
        val problemsFilters = OptionsParser.getProblemsFilters(opts)

        val (checkAllBuilds, checkLastBuilds) = parsePluginToCheckList(opts)

        val excludedPlugins = parseExcludedPlugins(opts)

        val pluginsToCheck = getDescriptorsToCheck(checkAllBuilds, checkLastBuilds, ideDescriptor.ideVersion)
        val dependencyResolver = IdeDependencyFinder(ideDescriptor.ide, pluginRepository, pluginDetailsProvider)
        return CheckIdeParams(ideDescriptor, jdkDescriptor, pluginsToCheck, excludedPlugins, externalClassesPrefixes, externalClassPath, checkAllBuilds, problemsFilters, dependencyResolver)
      }
    }
  }

  /**
   * (id-s of plugins to check all builds, id-s of plugins to check last builds)
   */
  private fun parsePluginToCheckList(opts: CmdOpts): Pair<List<String>, List<String>> {
    val pluginsCheckAllBuilds = arrayListOf<String>()
    val pluginsCheckLastBuilds = arrayListOf<String>()

    pluginsCheckAllBuilds.addAll(opts.pluginToCheckAllBuilds)
    pluginsCheckLastBuilds.addAll(opts.pluginToCheckLastBuild)

    val pluginsFile = opts.pluginsToCheckFile
    if (pluginsFile != null) {
      try {
        BufferedReader(FileReader(pluginsFile)).use { reader ->
          var s: String?
          while (true) {
            s = reader.readLine()
            if (s == null) break
            s = s.trim { it <= ' ' }
            if (s.isEmpty() || s.startsWith("//")) continue

            var checkAllBuilds = true
            if (s.endsWith("$")) {
              s = s.substring(0, s.length - 1).trim { it <= ' ' }
              checkAllBuilds = false
            }
            if (s.startsWith("$")) {
              s = s.substring(1).trim { it <= ' ' }
              checkAllBuilds = false
            }

            if (s.isEmpty()) continue

            if (checkAllBuilds) {
              pluginsCheckAllBuilds.add(s)
            } else {
              pluginsCheckLastBuilds.add(s)
            }
          }
        }
      } catch (e: IOException) {
        throw RuntimeException("Failed to read plugins file " + pluginsFile + ": " + e.message, e)
      }

    }

    return Pair<List<String>, List<String>>(pluginsCheckAllBuilds, pluginsCheckLastBuilds)
  }


  private fun getDescriptorsToCheck(checkAllBuilds: List<String>, checkLastBuilds: List<String>, ideVersion: IdeVersion) =
      getUpdateInfosToCheck(checkAllBuilds, checkLastBuilds, ideVersion).map { PluginCoordinate.ByUpdateInfo(it, pluginRepository) }

  private fun getUpdateInfosToCheck(checkAllBuilds: List<String>, checkLastBuilds: List<String>, ideVersion: IdeVersion): List<UpdateInfo> {
    if (checkAllBuilds.isEmpty() && checkLastBuilds.isEmpty()) {
      return pluginRepository.getLastCompatibleUpdates(ideVersion)
    } else {
      val myActualUpdatesToCheck = arrayListOf<UpdateInfo>()

      checkAllBuilds.flatMapTo(myActualUpdatesToCheck) {
        pluginRepository.getAllCompatibleUpdatesOfPlugin(ideVersion, it)
      }

      checkLastBuilds.distinct().mapNotNullTo(myActualUpdatesToCheck) {
        pluginRepository.getAllCompatibleUpdatesOfPlugin(ideVersion, it)
            .sortedByDescending { it.updateId }
            .firstOrNull()
      }

      return myActualUpdatesToCheck
    }
  }

  private fun parseExcludedPlugins(opts: CmdOpts): List<PluginIdAndVersion> {
    val epf = opts.excludedPluginsFile ?: return emptyList()
    File(epf).bufferedReader().use { br ->
      return IdeResourceUtil.getBrokenPluginsByLines(br.readLines())
    }
  }


}