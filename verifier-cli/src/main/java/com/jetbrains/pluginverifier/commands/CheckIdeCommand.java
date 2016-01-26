package com.jetbrains.pluginverifier.commands;

import com.google.common.base.Predicate;
import com.google.common.collect.*;
import com.intellij.structure.domain.Idea;
import com.intellij.structure.domain.IdeaPlugin;
import com.intellij.structure.domain.JDK;
import com.intellij.structure.pool.ClassPool;
import com.jetbrains.pluginverifier.PluginVerifierOptions;
import com.jetbrains.pluginverifier.VerificationContextImpl;
import com.jetbrains.pluginverifier.VerifierCommand;
import com.jetbrains.pluginverifier.format.UpdateInfo;
import com.jetbrains.pluginverifier.problems.NoCompatibleUpdatesProblem;
import com.jetbrains.pluginverifier.problems.Problem;
import com.jetbrains.pluginverifier.problems.VerificationProblem;
import com.jetbrains.pluginverifier.repository.RepositoryManager;
import com.jetbrains.pluginverifier.results.ProblemSet;
import com.jetbrains.pluginverifier.utils.*;
import com.jetbrains.pluginverifier.utils.teamcity.TeamCityLog;
import com.jetbrains.pluginverifier.utils.teamcity.TeamCityUtil;
import com.jetbrains.pluginverifier.verifiers.Verifiers;
import org.apache.commons.cli.CommandLine;
import org.jdom.JDOMException;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * @author Sergey Evdokimov
 */
public class CheckIdeCommand extends VerifierCommand {

  /**
   * List of IntelliJ plugins which has defined module inside
   * (e.g. plugin "org.jetbrains.plugins.ruby" has a module "com.intellij.modules.ruby" inside)
   */
  private static final ImmutableList<String> INTELLIJ_MODULES_PLUGIN_IDS =
      ImmutableList.of("org.jetbrains.plugins.ruby", "com.jetbrains.php", "org.jetbrains.android", "Pythonid");
  private TeamCityUtil.ReportGrouping myGrouping;
  private TeamCityLog myTc;
  private JDK myJdk;
  private PluginVerifierOptions myVerifierOptions;
  private ClassPool myExternalClassPath;
  private Idea myIde;
  private List<String> myCheckAllBuilds;
  private List<String> myCheckLastBuilds;
  private Collection<UpdateInfo> myUpdatesToCheck;
  private List<String> myToBeCheckedPluginIds;
  private List<UpdateInfo> myCompatibleToBeCheckedUpdates;
  private String myDumpBrokenPluginsFile;
  private String myReportFile;
  private boolean myCheckExcludedBuilds;
  private Predicate<UpdateInfo> myExcludedUpdatesFilter;
  private Map<UpdateInfo, ProblemSet> myResults;
  private Set<UpdateInfo> myImportantUpdates;
  private List<Pair<UpdateInfo, ? extends Problem>> myIncorrectPlugins;
  private Multimap<String, String> myExcludedPlugins;

  public CheckIdeCommand() {
    super("check-ide");
  }


  private static void saveResultsToXml(@NotNull String xmlFile,
                                       @NotNull String ideVersion,
                                       @NotNull Map<UpdateInfo, ProblemSet> results) throws IOException {
    Map<UpdateInfo, Collection<Problem>> problems = new LinkedHashMap<UpdateInfo, Collection<Problem>>();

    for (Map.Entry<UpdateInfo, ProblemSet> entry : results.entrySet()) {
      problems.put(entry.getKey(), entry.getValue().getAllProblems());
    }

    ProblemUtils.saveProblems(new File(xmlFile), ideVersion, problems);
  }

  /**
   * Prints Build Problems in the Overview page or as tests
   */
  private static void printTeamCityProblems(@NotNull TeamCityLog log,
                                            @NotNull Map<UpdateInfo, ProblemSet> results,
                                            @NotNull Predicate<UpdateInfo> updateFilter,
                                            @NotNull TeamCityUtil.ReportGrouping reportGrouping) {
    if (log == TeamCityLog.NULL_LOG) return;

    //list of problems without their exact problem location (only affected plugin)
    Multimap<Problem, UpdateInfo> problems = ArrayListMultimap.create();

    //fill problems map
    for (Map.Entry<UpdateInfo, ProblemSet> entry : results.entrySet()) {
      if (!updateFilter.apply(entry.getKey())) {
        continue; //this is excluded plugin
      }

      for (Problem problem : entry.getValue().getAllProblems()) {
        problems.put(problem, entry.getKey());
      }
    }

    if (reportGrouping.equals(TeamCityUtil.ReportGrouping.PLUGIN_WITH_LOCATION)) {
      TeamCityUtil.printReportWithLocations(log, Maps.filterKeys(results, updateFilter));
    } else {
      TeamCityUtil.printReport(log, problems, reportGrouping);
    }
  }

  /**
   * Checks if for all the specified plugins to be checked there is
   * a build compatible with a specified IDE in the Plugin Repository
   *
   * @return number of "missing update" and "broken plugin" problems
   */
  private static int printMissingAndIncorrectPlugins(@NotNull TeamCityLog tc,
                                                     @NotNull String version,
                                                     @NotNull List<String> toBeCheckedPluginIds,
                                                     @NotNull List<UpdateInfo> compatibleUpdates,
                                                     @NotNull List<Pair<UpdateInfo, ? extends Problem>> incorrectPlugins,
                                                     @NotNull Predicate<UpdateInfo> updateFilter) {
    if (tc == TeamCityLog.NULL_LOG) return 0;

    Multimap<Problem, UpdateInfo> problems = ArrayListMultimap.create();

    //fill verification problems
    for (Pair<UpdateInfo, ? extends Problem> incorrectPlugin : incorrectPlugins) {
      UpdateInfo updateInfo = incorrectPlugin.getFirst();
      if (updateFilter.apply(updateInfo)) {
        problems.put(incorrectPlugin.getSecond(), incorrectPlugin.getFirst());
      }
    }

    //fill missing plugin problems
    Set<String> missingPluginIds = new HashSet<String>();
    for (String pluginId : new HashSet<String>(toBeCheckedPluginIds)) {
      boolean hasCompatibleUpdate = false;
      for (UpdateInfo update : compatibleUpdates) {
        if (StringUtil.equals(pluginId, update.getPluginId())) {
          hasCompatibleUpdate = true;
          break;
        }
      }
      if (!hasCompatibleUpdate) {
        missingPluginIds.add(pluginId);
      }
    }

    for (String missingPluginId : missingPluginIds) {
      problems.put(new NoCompatibleUpdatesProblem(missingPluginId, version), new UpdateInfo(missingPluginId, missingPluginId, "no compatible update"));
    }


    TeamCityUtil.printReport(tc, problems, TeamCityUtil.ReportGrouping.PLUGIN);
    return problems.size();
  }

  private void fillArguments(@NotNull CommandLine commandLine, @NotNull List<String> freeArgs) throws IOException, JDOMException {
    if (freeArgs.isEmpty()) {
      throw FailUtil.fail("You have to specify IDE to check. For example: \"java -jar verifier.jar check-ide ~/EAPs/idea-IU-133.439\"");
    }

    File ideToCheck = new File(freeArgs.get(0));
    if (!ideToCheck.isDirectory()) {
      throw FailUtil.fail("IDE home is not a directory: " + ideToCheck);
    }

    myGrouping = TeamCityUtil.ReportGrouping.parseGrouping(commandLine);

    myTc = TeamCityLog.getInstance(commandLine);

    myJdk = createJdk(commandLine);

    myVerifierOptions = PluginVerifierOptions.parseOpts(commandLine);

    myExternalClassPath = getExternalClassPath(commandLine);

    myIde = new Idea(ideToCheck, myJdk, myExternalClassPath);
    updateIdeVersionFromCmd(myIde, commandLine);

    Pair<List<String>, List<String>> pluginsIds = Util.extractPluginToCheckList(commandLine);
    myCheckAllBuilds = pluginsIds.first;
    myCheckLastBuilds = pluginsIds.second;

    if (myCheckAllBuilds.isEmpty() && myCheckLastBuilds.isEmpty()) {
      myUpdatesToCheck = RepositoryManager.getInstance().getAllCompatibleUpdates(myIde.getVersion());
    } else {
      myUpdatesToCheck = new ArrayList<UpdateInfo>();

      if (myCheckAllBuilds.size() > 0) {
        myUpdatesToCheck.addAll(RepositoryManager.getInstance().getCompatibleUpdatesForPlugins(myIde.getVersion(), myCheckAllBuilds));
      }

      if (myCheckLastBuilds.size() > 0) {
        Map<String, UpdateInfo> lastBuilds = new HashMap<String, UpdateInfo>();

        for (UpdateInfo info : RepositoryManager.getInstance().getCompatibleUpdatesForPlugins(myIde.getVersion(), myCheckLastBuilds)) {
          UpdateInfo existsBuild = lastBuilds.get(info.getPluginId());

          //choose last build
          if (existsBuild == null || existsBuild.getUpdateId() < info.getUpdateId()) {
            lastBuilds.put(info.getPluginId(), info);
          }
        }

        myUpdatesToCheck.addAll(lastBuilds.values());
      }
    }

    //preserve initial lists of plugins
    myToBeCheckedPluginIds = Util.concat(myCheckAllBuilds, myCheckLastBuilds);
    myCompatibleToBeCheckedUpdates = new ArrayList<UpdateInfo>(myUpdatesToCheck);

    myDumpBrokenPluginsFile = commandLine.getOptionValue("d");
    myReportFile = commandLine.getOptionValue("report");

    myExcludedPlugins = Util.getExcludedPlugins(commandLine);

    myExcludedUpdatesFilter = new Predicate<UpdateInfo>() {
      @Override
      public boolean apply(UpdateInfo input) {
        return !myExcludedPlugins.containsEntry(input.getPluginId(), input.getVersion());
      }
    };

    //whether to check excluded builds or not
    myCheckExcludedBuilds = myDumpBrokenPluginsFile != null || myReportFile != null;


    if (!myCheckExcludedBuilds) {
      //drop out excluded plugins and don't check them
      myUpdatesToCheck = Collections2.filter(myUpdatesToCheck, myExcludedUpdatesFilter);
    }

    //move important IntelliJ plugins to the beginning of check-list
    //(those plugins which contain defined IntelliJ module inside)
    myUpdatesToCheck = prepareUpdates(myUpdatesToCheck);

    myImportantUpdates = prepareImportantUpdates(myUpdatesToCheck);

    myResults = new HashMap<UpdateInfo, ProblemSet>();

    //list of plugins which were not checked due to some error (first = plugin; second = error message; third = caused exception)
    myIncorrectPlugins = new ArrayList<Pair<UpdateInfo, ? extends Problem>>();
  }

  @NotNull
  private Collection<UpdateInfo> prepareUpdates(@NotNull Collection<UpdateInfo> updates) {
    Collection<UpdateInfo> important = new ArrayList<UpdateInfo>();
    Collection<UpdateInfo> notImportant = new ArrayList<UpdateInfo>();
    for (UpdateInfo update : updates) {
      String pluginId = update.getPluginId();
      if (INTELLIJ_MODULES_PLUGIN_IDS.contains(pluginId)) {
        important.add(update);
      } else {
        notImportant.add(update);
      }
    }
    important.addAll(notImportant);
    return important;
  }

  @Override
  public int execute(@NotNull CommandLine commandLine, @NotNull List<String> freeArgs) throws Exception {
    fillArguments(commandLine, freeArgs);


    //-----------------------------VERIFICATION---------------------------------------
    long time = System.currentTimeMillis();

    int updatesProceed = 0;

    for (UpdateInfo updateJson : myUpdatesToCheck) {
      TeamCityLog.Block block = myTc.blockOpen(updateJson.toString());

      try {
        File updateFile = RepositoryManager.getInstance().getOrLoadUpdate(updateJson);

        IdeaPlugin plugin = IdeaPlugin.createFromZip(updateFile);

        System.out.println(String.format("Verifying plugin %s (#%d out of %d)...", updateJson, (++updatesProceed), myUpdatesToCheck.size()));

        VerificationContextImpl ctx = new VerificationContextImpl(myVerifierOptions, myIde);
        Verifiers.processAllVerifiers(plugin, ctx);

        myResults.put(updateJson, ctx.getProblems());

        if (ctx.getProblems().isEmpty()) {
          System.out.println("plugin " + updateJson + " is OK");
          myTc.message(updateJson + " is OK");
        } else {
          int count = ctx.getProblems().count();
          System.out.println("has " + count + " problems");

          if (myExcludedUpdatesFilter.apply(updateJson)) {
            myTc.messageError(updateJson + " has " + count + " problems");
          } else {
            myTc.message(updateJson + " has problems, but is excluded in brokenPlugins.json");
          }

          ctx.getProblems().printProblems(System.out, "    ");
        }


        if (myImportantUpdates.contains(updateJson)) {
          //add a plugin with defined IntelliJ module to IDEA
          //it gives us a chance to refer to such plugins by their defined module-name
          myIde.addCustomPlugin(plugin);
        }

      } catch (Exception e) {
        final String message;
        if (e instanceof IOException) {
          message = "Failed to read/download plugin " + updateJson + " because " + e.getLocalizedMessage();
        } else {
          message = "Failed to verify plugin " + updateJson + " because " + e.getLocalizedMessage();
        }
        myIncorrectPlugins.add(Pair.create(updateJson, new VerificationProblem(e.getLocalizedMessage(), updateJson.toString())));

        System.err.println(message);
        e.printStackTrace();
        myTc.messageError(message, Util.getStackTrace(e));
      } finally {
        block.close();
      }

    }

    //-----------------------------PRINT RESULTS----------------------------------------

    System.out.println("Verification completed (" + ((System.currentTimeMillis() - time) / 1000) + " seconds)");

    //Save results to XML if necessary
    if (commandLine.hasOption("xr")) {
      saveResultsToXml(commandLine.getOptionValue("xr"), myIde.getVersion(), myResults);
    }


    //if all the builds were checked (including excluded builds from brokenPlugins.txt)
    if (myCheckExcludedBuilds) {

      if (myDumpBrokenPluginsFile != null) {
        System.out.println("Dumping list of broken plugins to " + myDumpBrokenPluginsFile);

        Util.dumbBrokenPluginsList(myDumpBrokenPluginsFile, Collections2.filter(myUpdatesToCheck, new Predicate<UpdateInfo>() {
          @Override
          public boolean apply(UpdateInfo update) {
            return myResults.get(update) != null && !myResults.get(update).isEmpty(); //update to check contains some problem
          }
        }));
      }

      if (myReportFile != null) {
        File file = new File(myReportFile);
        System.out.println("Saving report to " + file.getAbsolutePath());

        CheckIdeHtmlReportBuilder.build(file, myIde.getVersion(), myToBeCheckedPluginIds, myExcludedUpdatesFilter, myResults);
      }
    }

    printTeamCityProblems(myTc, myResults, myExcludedUpdatesFilter, myGrouping);

    int totalProblemsCnt = printMissingAndIncorrectPlugins(myTc, myIde.getVersion(), myToBeCheckedPluginIds, myCompatibleToBeCheckedUpdates, myIncorrectPlugins, myExcludedUpdatesFilter);

    Set<Problem> allProblems = new HashSet<Problem>();

    for (ProblemSet problemSet : Maps.filterKeys(myResults, myExcludedUpdatesFilter).values()) {
      allProblems.addAll(problemSet.getAllProblems());
    }


    totalProblemsCnt += allProblems.size();

    //-----------------------------PRINT CHECK STATUS----------------------------------------

    if (totalProblemsCnt > 0) {
      myTc.buildStatusFailure("IDE " + myIde.getVersion() + " has " + totalProblemsCnt + StringUtil.pluralize(" problem", totalProblemsCnt));
      System.out.printf("IDE %s has %d problems", myIde.getVersion(), totalProblemsCnt);
      return 2;
    } else {
      myTc.buildStatusSuccess("IDE " + myIde.getVersion() + " has no broken API problems!");
    }

    return 0;
  }

  /**
   * Drops out non-last builds of IntelliJ plugins
   * IntelliJ plugin is a plugin which defines intellij-module in its plugin.xml
   */
  @NotNull
  private Set<UpdateInfo> prepareImportantUpdates(@NotNull Collection<UpdateInfo> updates) {
    Map<String, Integer> lastBuilds = new HashMap<String, Integer>();
    for (UpdateInfo update : updates) {
      String pluginId = update.getPluginId();
      if (INTELLIJ_MODULES_PLUGIN_IDS.contains(pluginId)) {
        Integer existingBuild = lastBuilds.get(pluginId);
        Integer curBuild = update.getUpdateId();

        if (existingBuild == null || existingBuild < curBuild) {
          lastBuilds.put(pluginId, curBuild);
        }
      }
    }

    Set<UpdateInfo> result = new HashSet<UpdateInfo>();
    for (UpdateInfo update : updates) {
      String pluginId = update.getPluginId();
      Integer updateId = update.getUpdateId();

      if (lastBuilds.containsKey(pluginId) && lastBuilds.get(pluginId).equals(updateId)) {
        result.add(update);
      }
    }

    return result;
  }
}
