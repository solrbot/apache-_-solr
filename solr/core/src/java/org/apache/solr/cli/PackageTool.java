/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.cli;

import static org.apache.solr.cli.SolrCLI.printGreen;
import static org.apache.solr.cli.SolrCLI.printRed;
import static org.apache.solr.packagemanager.PackageUtils.format;
import static org.apache.solr.packagemanager.PackageUtils.formatGreen;

import java.lang.invoke.MethodHandles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.lucene.util.SuppressForbidden;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrException.ErrorCode;
import org.apache.solr.common.util.Pair;
import org.apache.solr.packagemanager.PackageManager;
import org.apache.solr.packagemanager.PackageUtils;
import org.apache.solr.packagemanager.RepositoryManager;
import org.apache.solr.packagemanager.SolrPackage;
import org.apache.solr.packagemanager.SolrPackage.SolrPackageRelease;
import org.apache.solr.packagemanager.SolrPackageInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Supports package command in the bin/solr script. */
public class PackageTool extends ToolBase {

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private static final Option COLLECTIONS_OPTION =
      Option.builder()
          .longOpt("collections")
          .hasArg()
          .argName("COLLECTIONS")
          .desc(
              "Specifies that this action should affect plugins for the given collections only, excluding cluster level plugins.")
          .build();

  private static final Option CLUSTER_OPTION =
      Option.builder()
          .longOpt("cluster")
          .desc("Specifies that this action should affect cluster-level plugins only.")
          .build();

  private static final Option PARAM_OPTION =
      Option.builder()
          .longOpt("param")
          .hasArgs()
          .argName("PARAMS")
          .desc("List of parameters to be used with deploy command.")
          .build();

  private static final Option UPDATE_OPTION =
      Option.builder()
          .longOpt("update")
          .desc("If a deployment is an update over a previous deployment.")
          .build();

  private static final Option COLLECTION_OPTION =
      Option.builder("c")
          .longOpt("collection")
          .hasArg()
          .argName("COLLECTION")
          .desc("The collection to apply the package to, not required.")
          .build();

  private static final Option NO_PROMPT_OPTION =
      Option.builder("y")
          .longOpt("no-prompt")
          .desc("Don't prompt for input; accept all default choices, defaults to false.")
          .build();

  public PackageTool(ToolRuntime runtime) {
    super(runtime);
  }

  @Override
  public String getName() {
    return "package";
  }

  public PackageManager packageManager;
  public RepositoryManager repositoryManager;

  @Override
  @SuppressForbidden(
      reason =
          "We really need to print the stacktrace here, otherwise "
              + "there shall be little else information to debug problems. Other SolrCLI tools "
              + "don't print stack traces, hence special treatment is needed here."
              + "Need to turn off logging, and SLF4J doesn't seem to provide for a way.")
  public void runImpl(CommandLine cli) throws Exception {

    // Need a logging free, clean output going through to the user.
    Level oldLevel = LoggerContext.getContext(false).getRootLogger().getLevel();
    Configurator.setRootLevel(Level.OFF);

    try {
      String solrUrl = CLIUtils.normalizeSolrUrl(cli);
      String zkHost = CLIUtils.getZkHost(cli);
      if (zkHost == null) {
        throw new SolrException(ErrorCode.INVALID_STATE, "Package manager runs only in SolrCloud");
      }

      log.info("ZK: {}", zkHost);

      String cmd = cli.getArgs()[0];

      try (SolrClient solrClient = CLIUtils.getSolrClient(cli, true)) {
        packageManager = new PackageManager(runtime, solrClient, solrUrl, zkHost);
        try {
          repositoryManager = new RepositoryManager(solrClient, packageManager);

          switch (cmd) {
            case "add-repo":
              String repoName = cli.getArgs()[1];
              String repoUrl = cli.getArgs()[2];
              repositoryManager.addRepository(repoName, repoUrl);
              printGreen("Added repository: " + repoName);
              break;
            case "add-key":
              String keyFilename = cli.getArgs()[1];
              Path path = Path.of(keyFilename);
              repositoryManager.addKey(Files.readAllBytes(path), path.getFileName().toString());
              break;
            case "list-installed":
              printGreen("Installed packages:\n-----");
              for (SolrPackageInstance pkg : packageManager.fetchInstalledPackageInstances()) {
                printGreen(pkg);
              }
              break;
            case "list-available":
              printGreen("Available packages:\n-----");
              for (SolrPackage pkg : repositoryManager.getPackages()) {
                printGreen(pkg.name + " \t\t" + pkg.description);
                for (SolrPackageRelease version : pkg.versions) {
                  printGreen("\tVersion: " + version.version);
                }
              }
              break;
            case "list-deployed":
              if (cli.hasOption(COLLECTION_OPTION)) {
                String collection = cli.getOptionValue(COLLECTION_OPTION);
                Map<String, SolrPackageInstance> packages =
                    packageManager.getPackagesDeployed(collection);
                printGreen("Packages deployed on " + collection + ":");
                for (String packageName : packages.keySet()) {
                  printGreen("\t" + packages.get(packageName));
                }
              } else {
                // nuance that we use an arg here instead of requiring a --package parameter with a
                // value
                // in this code path
                String packageName = cli.getArgs()[1];
                Map<String, String> deployedCollections =
                    packageManager.getDeployedCollections(packageName);
                if (!deployedCollections.isEmpty()) {
                  printGreen("Collections on which package " + packageName + " was deployed:");
                  for (String collection : deployedCollections.keySet()) {
                    printGreen(
                        "\t"
                            + collection
                            + "("
                            + packageName
                            + ":"
                            + deployedCollections.get(collection)
                            + ")");
                  }
                } else {
                  printGreen("Package " + packageName + " not deployed on any collection.");
                }
              }
              break;
            case "install":
              {
                Pair<String, String> parsedVersion = parsePackageVersion(cli.getArgList().get(1));
                String packageName = parsedVersion.first();
                String version = parsedVersion.second();
                boolean success = repositoryManager.install(packageName, version);
                if (success) {
                  printGreen(packageName + " installed.");
                } else {
                  printRed(packageName + " installation failed.");
                }
                break;
              }
            case "deploy":
              {
                if (cli.hasOption(CLUSTER_OPTION) || cli.hasOption(COLLECTIONS_OPTION)) {
                  Pair<String, String> parsedVersion = parsePackageVersion(cli.getArgList().get(1));
                  String packageName = parsedVersion.first();
                  String version = parsedVersion.second();
                  boolean noPrompt = cli.hasOption(NO_PROMPT_OPTION);
                  boolean isUpdate = cli.hasOption(UPDATE_OPTION);
                  String[] collections =
                      cli.hasOption(COLLECTIONS_OPTION)
                          ? PackageUtils.validateCollections(
                              cli.getOptionValue(COLLECTIONS_OPTION).split(","))
                          : new String[] {};
                  String[] parameters = cli.getOptionValues(PARAM_OPTION);
                  packageManager.deploy(
                      packageName,
                      version,
                      collections,
                      cli.hasOption(CLUSTER_OPTION),
                      parameters,
                      isUpdate,
                      noPrompt);
                } else {
                  printRed(
                      "Either specify --cluster to deploy cluster level plugins or --collections <list-of-collections> to deploy collection level plugins");
                }
                break;
              }
            case "undeploy":
              {
                if (cli.hasOption(CLUSTER_OPTION) || cli.hasOption(COLLECTIONS_OPTION)) {
                  Pair<String, String> parsedVersion = parsePackageVersion(cli.getArgList().get(1));
                  if (parsedVersion.second() != null) {
                    throw new SolrException(
                        ErrorCode.BAD_REQUEST,
                        "Only package name expected, without a version. Actual: "
                            + cli.getArgList().get(1));
                  }
                  String packageName = parsedVersion.first();
                  String[] collections =
                      cli.hasOption(COLLECTIONS_OPTION)
                          ? PackageUtils.validateCollections(
                              cli.getOptionValue(COLLECTIONS_OPTION).split(","))
                          : new String[] {};
                  packageManager.undeploy(packageName, collections, cli.hasOption(CLUSTER_OPTION));
                } else {
                  printRed(
                      "Either specify --cluster to undeploy cluster level plugins or -collections <list-of-collections> to undeploy collection level plugins");
                }
                break;
              }
            case "uninstall":
              {
                Pair<String, String> parsedVersion = parsePackageVersion(cli.getArgList().get(1));
                if (parsedVersion.second() == null) {
                  throw new SolrException(
                      ErrorCode.BAD_REQUEST,
                      "Package name and version are both required. Actual: "
                          + cli.getArgList().get(1));
                }
                String packageName = parsedVersion.first();
                String version = parsedVersion.second();
                packageManager.uninstall(packageName, version);
                break;
              }
            default:
              throw new RuntimeException("Unrecognized command: " + cmd);
          }
        } finally {
          packageManager.close();
        }
      }
      log.info("Finished: {}", cmd);

    } catch (Exception ex) {
      // We need to print this since SolrCLI drops the stack trace in favour
      // of brevity. Package tool should surely print the full stacktrace!
      ex.printStackTrace();
      throw ex;
    } finally {
      // Restore the old logging level
      Configurator.setRootLevel(oldLevel);
    }
  }

  @Override
  public String getHeader() {
    StringBuilder sb = new StringBuilder();
    format(sb, "Package Manager\n---------------");
    formatGreen(sb, "bin/solr package add-repo <repository-name> <repository-url>");
    format(sb, "Add a repository to Solr.");
    format(sb, "");
    formatGreen(sb, "bin/solr package add-key <file-containing-trusted-key>");
    format(sb, "Add a trusted key to Solr.");
    format(sb, "");
    formatGreen(sb, "bin/solr package install <package-name>[:<version>] ");
    format(
        sb,
        "Install a package into Solr. This copies over the artifacts from the repository into Solr's internal package store and sets up classloader for this package to be used.");
    format(sb, "");
    formatGreen(
        sb,
        "bin/solr package deploy <package-name>[:<version>] [-y] [--update] --collections <comma-separated-collections> [-p <param1>=<val1> -p <param2>=<val2> ...] ");
    format(
        sb,
        "Bootstraps a previously installed package into the specified collections. It the package accepts parameters for its setup commands, they can be specified (as per package documentation).");
    format(sb, "");
    formatGreen(sb, "bin/solr package list-installed");
    format(sb, "Print a list of packages installed in Solr.");
    format(sb, "");
    formatGreen(sb, "bin/solr package list-available");
    format(sb, "Print a list of packages available in the repositories.");
    format(sb, "");
    formatGreen(sb, "bin/solr package list-deployed -c <collection>");
    format(sb, "Print a list of packages deployed on a given collection.");
    format(sb, "");
    formatGreen(sb, "bin/solr package list-deployed <package-name>");
    format(sb, "Print a list of collections on which a given package has been deployed.");
    format(sb, "");
    formatGreen(
        sb, "bin/solr package undeploy <package-name> --collections <comma-separated-collections>");
    format(sb, "Undeploy a package from specified collection(s)");
    format(sb, "");
    formatGreen(sb, "bin/solr package uninstall <package-name>:<version>");
    format(
        sb,
        "Uninstall an unused package with specified version from Solr. Both package name and version are required.");
    format(sb, "\n");
    format(
        sb,
        "Note: (a) Please add '--solr-url http://host:port' parameter if needed (usually on Windows).");
    format(
        sb,
        "      (b) Please make sure that all Solr nodes are started with '-Denable.packages=true' parameter.");
    format(sb, "\n");
    format(sb, "List of options:");
    return sb.toString();
  }

  /**
   * Parses package name and version in the format "name:version" or "name"
   *
   * @return A pair of package name (first) and version (second)
   */
  private Pair<String, String> parsePackageVersion(String arg) {
    String[] splits = arg.split(":");
    if (splits.length > 2) {
      throw new SolrException(
          ErrorCode.BAD_REQUEST,
          "Invalid package name: "
              + arg
              + ". Didn't match the pattern: <packagename>:<version> or <packagename>");
    }

    String packageName = splits[0];
    String version = splits.length == 2 ? splits[1] : null;
    return new Pair<>(packageName, version);
  }

  @Override
  public Options getOptions() {
    return super.getOptions()
        .addOption(COLLECTIONS_OPTION)
        .addOption(CLUSTER_OPTION)
        .addOption(PARAM_OPTION)
        .addOption(UPDATE_OPTION)
        .addOption(COLLECTION_OPTION)
        .addOption(NO_PROMPT_OPTION)
        .addOption(CommonCLIOptions.CREDENTIALS_OPTION)
        .addOptionGroup(getConnectionOptions());
  }
}
