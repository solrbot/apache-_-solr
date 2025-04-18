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
import java.util.concurrent.TimeUnit;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.solr.client.solrj.impl.SolrZkClientTimeout;
import org.apache.solr.cloud.ZkController;
import org.apache.solr.common.cloud.ClusterProperties;
import org.apache.solr.common.cloud.SolrZkClient;

/**
 * Supports cluster command in the bin/solr script.
 *
 * <p>Set cluster properties by directly manipulating ZooKeeper.
 */
public class ClusterTool extends ToolBase {
  // It is a shame this tool doesn't more closely mimic how the ConfigTool works.

  private static final Option PROPERTY_OPTION =
      Option.builder()
          .longOpt("property")
          .hasArg()
          .argName("PROPERTY")
          .required()
          .desc("Name of the Cluster property to apply the action to, such as: 'urlScheme'.")
          .build();

  private static final Option VALUE_OPTION =
      Option.builder()
          .longOpt("value")
          .hasArg()
          .argName("VALUE")
          .desc("Set the property to this value.")
          .build();

  public ClusterTool(ToolRuntime runtime) {
    super(runtime);
  }

  @Override
  public String getName() {
    return "cluster";
  }

  @Override
  public Options getOptions() {
    return super.getOptions()
        .addOption(PROPERTY_OPTION)
        .addOption(VALUE_OPTION)
        .addOption(CommonCLIOptions.ZK_HOST_OPTION);
  }

  @Override
  public void runImpl(CommandLine cli) throws Exception {

    String propertyName = cli.getOptionValue(PROPERTY_OPTION);
    String propertyValue = cli.getOptionValue(VALUE_OPTION);
    String zkHost = CLIUtils.getZkHost(cli);

    if (!ZkController.checkChrootPath(zkHost, true)) {
      throw new IllegalStateException(
          "A chroot was specified in zkHost but the znode doesn't exist.");
    }

    try (SolrZkClient zkClient =
        new SolrZkClient.Builder()
            .withUrl(zkHost)
            .withTimeout(SolrZkClientTimeout.DEFAULT_ZK_CLIENT_TIMEOUT, TimeUnit.MILLISECONDS)
            .build()) {

      ClusterProperties props = new ClusterProperties(zkClient);
      try {
        props.setClusterProperty(propertyName, propertyValue);
      } catch (IOException ex) {
        throw new Exception(
            "Unable to set the cluster property due to following error : "
                + ex.getLocalizedMessage());
      }
    }
  }
}
