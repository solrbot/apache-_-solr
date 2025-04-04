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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.io.file.PathUtils;
import org.apache.solr.cli.CommonCLIOptions.DefaultValues;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.CloudSolrClient;
import org.apache.solr.client.solrj.impl.Http2SolrClient;
import org.apache.solr.client.solrj.impl.JsonMapResponseParser;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.request.CoreAdminRequest;
import org.apache.solr.client.solrj.request.GenericSolrRequest;
import org.apache.solr.client.solrj.response.CoreAdminResponse;
import org.apache.solr.cloud.ZkConfigSetService;
import org.apache.solr.common.cloud.ZkStateReader;
import org.apache.solr.common.params.CollectionAdminParams;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.core.ConfigSetService;
import org.noggit.CharArr;
import org.noggit.JSONWriter;

/** Supports create command in the bin/solr script. */
public class CreateTool extends ToolBase {

  private static final Option COLLECTION_NAME_OPTION =
      Option.builder("c")
          .longOpt("name")
          .hasArg()
          .argName("NAME")
          .required()
          .desc("Name of collection or core to create.")
          .build();

  private static final Option SHARDS_OPTION =
      Option.builder("sh")
          .longOpt("shards")
          .hasArg()
          .argName("#")
          .type(Integer.class)
          .desc("Number of shards; default is 1.")
          .build();

  private static final Option REPLICATION_FACTOR_OPTION =
      Option.builder("rf")
          .longOpt("replication-factor")
          .hasArg()
          .argName("#")
          .type(Integer.class)
          .desc(
              "Number of copies of each document across the collection (replicas per shard); default is 1.")
          .build();

  private static final Option CONF_DIR_OPTION =
      Option.builder("d")
          .longOpt("conf-dir")
          .hasArg()
          .argName("DIR")
          .desc(
              "Configuration directory to copy when creating the new collection; default is "
                  + DefaultValues.DEFAULT_CONFIG_SET
                  + '.')
          .build();

  private static final Option CONF_NAME_OPTION =
      Option.builder("n")
          .longOpt("conf-name")
          .hasArg()
          .argName("NAME")
          .desc("Configuration name; default is the collection name.")
          .build();

  public CreateTool(ToolRuntime runtime) {
    super(runtime);
  }

  @Override
  public String getName() {
    return "create";
  }

  @Override
  public String getHeader() {
    return "Creates a core or collection depending on whether Solr is running in standalone (core) or SolrCloud mode (collection).\n"
        + "If you are using standalone mode you must run this command on the Solr server itself.\n"
        + "\n"
        + "List of options:";
  }

  @Override
  public Options getOptions() {
    Options opts =
        super.getOptions()
            .addOption(COLLECTION_NAME_OPTION)
            .addOption(SHARDS_OPTION)
            .addOption(REPLICATION_FACTOR_OPTION)
            .addOption(CONF_DIR_OPTION)
            .addOption(CONF_NAME_OPTION)
            .addOption(CommonCLIOptions.CREDENTIALS_OPTION)
            .addOptionGroup(getConnectionOptions());

    return opts;
  }

  @Override
  public void runImpl(CommandLine cli) throws Exception {
    try (var solrClient = CLIUtils.getSolrClient(cli)) {
      if (CLIUtils.isCloudMode(solrClient)) {
        createCollection(cli);
      } else {
        createCore(cli, solrClient);
      }
    }
  }

  protected void createCore(CommandLine cli, SolrClient solrClient) throws Exception {
    String coreName = cli.getOptionValue(COLLECTION_NAME_OPTION);
    String solrUrl =
        cli.getOptionValue(CommonCLIOptions.SOLR_URL_OPTION, CLIUtils.getDefaultSolrUrl());

    final String solrInstallDir = System.getProperty("solr.install.dir");
    final String confDirName =
        cli.getOptionValue(CONF_DIR_OPTION, DefaultValues.DEFAULT_CONFIG_SET);

    // we allow them to pass a directory instead of a configset name
    Path configsetDir = Path.of(confDirName);
    Path solrInstallDirPath = Path.of(solrInstallDir);

    if (!Files.isDirectory(configsetDir)) {
      ensureConfDirExists(solrInstallDirPath, configsetDir);
    }
    printDefaultConfigsetWarningIfNecessary(cli);

    String coreRootDirectory; // usually same as solr home, but not always

    NamedList<?> systemInfo =
        solrClient.request(
            new GenericSolrRequest(SolrRequest.METHOD.GET, CommonParams.SYSTEM_INFO_PATH));

    // convert raw JSON into user-friendly output
    coreRootDirectory = (String) systemInfo.get("core_root");

    if (CLIUtils.safeCheckCoreExists(
        solrUrl, coreName, cli.getOptionValue(CommonCLIOptions.CREDENTIALS_OPTION))) {
      throw new IllegalArgumentException(
          "\nCore '"
              + coreName
              + "' already exists!\nChecked core existence using Core API command");
    }

    Path coreInstanceDir = Path.of(coreRootDirectory, coreName);
    Path confDir = getFullConfDir(solrInstallDirPath, configsetDir).resolve("conf");
    if (!Files.isDirectory(coreInstanceDir)) {
      Files.createDirectories(coreInstanceDir);
      if (!Files.isDirectory(coreInstanceDir)) {
        throw new IOException(
            "Failed to create new core instance directory: " + coreInstanceDir.toAbsolutePath());
      }

      PathUtils.copyDirectory(confDir, coreInstanceDir, StandardCopyOption.COPY_ATTRIBUTES);

      echoIfVerbose(
          "\nCopying configuration to new core instance directory:\n"
              + coreInstanceDir.toAbsolutePath());
    }

    echoIfVerbose("\nCreating new core '" + coreName + "' using CoreAdminRequest");

    try {
      CoreAdminResponse res = CoreAdminRequest.createCore(coreName, coreName, solrClient);
      if (isVerbose()) {
        echo(res.jsonStr());
        echo("\n");
      }
      echo(String.format(Locale.ROOT, "\nCreated new core '%s'", coreName));

    } catch (Exception e) {
      /* create-core failed, cleanup the copied configset before propagating the error. */
      PathUtils.deleteDirectory(coreInstanceDir);
      throw e;
    }
  }

  protected void createCollection(CommandLine cli) throws Exception {
    Http2SolrClient.Builder builder =
        new Http2SolrClient.Builder()
            .withIdleTimeout(30, TimeUnit.SECONDS)
            .withConnectionTimeout(15, TimeUnit.SECONDS)
            .withKeyStoreReloadInterval(-1, TimeUnit.SECONDS)
            .withOptionalBasicAuthCredentials(
                cli.getOptionValue(CommonCLIOptions.CREDENTIALS_OPTION));
    String zkHost = CLIUtils.getZkHost(cli);
    echoIfVerbose("Connecting to ZooKeeper at " + zkHost);
    try (CloudSolrClient cloudSolrClient = CLIUtils.getCloudHttp2SolrClient(zkHost, builder)) {
      cloudSolrClient.connect();
      createCollection(cloudSolrClient, cli);
    }
  }

  protected void createCollection(CloudSolrClient cloudSolrClient, CommandLine cli)
      throws Exception {

    String collectionName = cli.getOptionValue(COLLECTION_NAME_OPTION);
    final String solrInstallDir = System.getProperty("solr.install.dir");
    String confName = cli.getOptionValue(CONF_NAME_OPTION);
    String confDir = cli.getOptionValue(CONF_DIR_OPTION, DefaultValues.DEFAULT_CONFIG_SET);
    Path solrInstallDirPath = Path.of(solrInstallDir);
    Path confDirPath = Path.of(confDir);
    ensureConfDirExists(solrInstallDirPath, confDirPath);
    printDefaultConfigsetWarningIfNecessary(cli);

    Set<String> liveNodes = cloudSolrClient.getClusterState().getLiveNodes();
    if (liveNodes.isEmpty())
      throw new IllegalStateException(
          "No live nodes found! Cannot create a collection until "
              + "there is at least 1 live node in the cluster.");

    String solrUrl = cli.getOptionValue(CommonCLIOptions.SOLR_URL_OPTION);
    if (solrUrl == null) {
      String firstLiveNode = liveNodes.iterator().next();
      solrUrl = ZkStateReader.from(cloudSolrClient).getBaseUrlForNodeName(firstLiveNode);
    }

    // build a URL to create the collection
    int numShards = cli.getParsedOptionValue(SHARDS_OPTION, 1);
    int replicationFactor = cli.getParsedOptionValue(REPLICATION_FACTOR_OPTION, 1);

    boolean configExistsInZk =
        confName != null
            && !confName.trim().isEmpty()
            && ZkStateReader.from(cloudSolrClient)
                .getZkClient()
                .exists("/configs/" + confName, true);

    if (CollectionAdminParams.SYSTEM_COLL.equals(collectionName)) {
      // do nothing
    } else if (configExistsInZk) {
      echo("Re-using existing configuration directory " + confName);
    } else { // if (confdir != null && !confdir.trim().isEmpty()) {
      if (confName == null || confName.trim().isEmpty()) {
        confName = collectionName;
      }

      // TODO: This should be done using the configSet API
      final Path configsetsDirPath = CLIUtils.getConfigSetsDir(solrInstallDirPath);
      ConfigSetService configSetService =
          new ZkConfigSetService(ZkStateReader.from(cloudSolrClient).getZkClient());
      Path confPath = ConfigSetService.getConfigsetPath(confDir, configsetsDirPath.toString());

      echoIfVerbose(
          "Uploading "
              + confPath.toAbsolutePath()
              + " for config "
              + confName
              + " to ZooKeeper at "
              + cloudSolrClient.getClusterStateProvider().getQuorumHosts());
      // We will trust the config since we have the Zookeeper Address
      configSetService.uploadConfig(confName, confPath);
    }

    // since creating a collection is a heavy-weight operation, check for existence first
    if (CLIUtils.safeCheckCollectionExists(
        solrUrl, collectionName, cli.getOptionValue(CommonCLIOptions.CREDENTIALS_OPTION))) {
      throw new IllegalStateException(
          "\nCollection '"
              + collectionName
              + "' already exists!\nChecked collection existence using CollectionAdminRequest");
    }

    // doesn't seem to exist ... try to create
    echoIfVerbose(
        "\nCreating new collection '" + collectionName + "' using CollectionAdminRequest");

    NamedList<Object> response;
    try {
      var req =
          CollectionAdminRequest.createCollection(
              collectionName, confName, numShards, replicationFactor);
      req.setResponseParser(new JsonMapResponseParser());
      response = cloudSolrClient.request(req);
    } catch (SolrServerException sse) {
      throw new Exception(
          "Failed to create collection '" + collectionName + "' due to: " + sse.getMessage());
    }

    if (isVerbose()) {
      // pretty-print the response to stdout
      CharArr arr = new CharArr();
      new JSONWriter(arr, 2).write(response.asMap(10));
      echo(arr.toString());
    }
    String endMessage =
        String.format(
            Locale.ROOT,
            "Created collection '%s' with %d shard(s), %d replica(s)",
            collectionName,
            numShards,
            replicationFactor);
    if (confName != null && !confName.trim().isEmpty()) {
      endMessage += String.format(Locale.ROOT, " with config-set '%s'", confName);
    }

    echo(endMessage);
  }

  private Path getFullConfDir(Path solrInstallDir, Path confDirName) {
    return CLIUtils.getConfigSetsDir(solrInstallDir).resolve(confDirName);
  }

  private void ensureConfDirExists(Path solrInstallDir, Path confDirName) {
    if (!Files.isDirectory(confDirName)) {

      Path fullConfDir = getFullConfDir(solrInstallDir, confDirName);
      if (!Files.isDirectory(fullConfDir)) {
        echo("Specified configuration directory " + confDirName + " not found!");
        runtime.exit(1);
      }
    }
  }

  private void printDefaultConfigsetWarningIfNecessary(CommandLine cli) {
    final String confDirectoryName =
        cli.getOptionValue(CONF_DIR_OPTION, DefaultValues.DEFAULT_CONFIG_SET);
    final String confName = cli.getOptionValue(CONF_NAME_OPTION, "");

    if (confDirectoryName.equals("_default")
        && (confName.equals("") || confName.equals("_default"))) {
      final String collectionName = cli.getOptionValue(COLLECTION_NAME_OPTION);
      final String solrUrl =
          cli.getOptionValue(CommonCLIOptions.SOLR_URL_OPTION, CLIUtils.getDefaultSolrUrl());
      final String curlCommand =
          String.format(
              Locale.ROOT,
              "curl %s/solr/%s/config -d "
                  + "'{\"set-user-property\": {\"update.autoCreateFields\":\"false\"}}'",
              solrUrl,
              collectionName);
      final String configCommand =
          String.format(
              Locale.ROOT,
              "bin/solr config -c %s -s %s --action set-user-property --property update.autoCreateFields --value false",
              collectionName,
              solrUrl);
      echo(
          "WARNING: Using _default configset. Data driven schema functionality is enabled by default, which is");
      echo("         NOT RECOMMENDED for production use.");
      echo("");
      echo("         To turn it off:");
      echo("            " + curlCommand);
      echo("         Or:");
      echo("            " + configCommand);
    }
  }
}
