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
package org.apache.solr.prometheus.scraper;

import static org.apache.solr.common.params.CommonParams.ADMIN_PATHS;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.prometheus.client.Collector;
import io.prometheus.client.Counter;
import java.io.Closeable;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.thisptr.jackson.jq.JsonQuery;
import net.thisptr.jackson.jq.exception.JsonQueryException;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrRequest.METHOD;
import org.apache.solr.client.solrj.SolrRequest.SolrRequestType;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.CloudSolrClient;
import org.apache.solr.client.solrj.impl.Http2SolrClient;
import org.apache.solr.client.solrj.request.GenericSolrRequest;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.prometheus.collector.MetricSamples;
import org.apache.solr.prometheus.exporter.MetricsQuery;
import org.apache.solr.prometheus.exporter.SolrExporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class SolrScraper implements Closeable {

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  protected static final String ZK_HOST_LABEL = "zk_host";
  protected static final String BASE_URL_LABEL = "base_url";
  protected static final String CLUSTER_ID_LABEL = "cluster_id";

  private static final Counter scrapeErrorTotal =
      Counter.build()
          .name("solr_exporter_scrape_error_total")
          .help("Number of scrape error.")
          .labelNames(ZK_HOST_LABEL, BASE_URL_LABEL, CLUSTER_ID_LABEL)
          .register(SolrExporter.defaultRegistry);

  protected static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  protected final String clusterId;

  protected final ExecutorService executor;

  public abstract Map<String, MetricSamples> metricsForAllHosts(MetricsQuery query)
      throws IOException;

  public abstract Map<String, MetricSamples> pingAllCores(MetricsQuery query) throws IOException;

  public abstract Map<String, MetricSamples> pingAllCollections(MetricsQuery query)
      throws IOException;

  public abstract MetricSamples search(MetricsQuery query) throws IOException;

  public abstract MetricSamples collections(MetricsQuery metricsQuery) throws IOException;

  public SolrScraper(ExecutorService executor, String clusterId) {
    this.executor = executor;
    this.clusterId = clusterId;
  }

  protected Map<String, MetricSamples> sendRequestsInParallel(
      Collection<String> items, Function<String, MetricSamples> samplesCallable)
      throws IOException {

    Map<String, MetricSamples> result = new HashMap<>(); // sync on this when adding to it below

    try {
      // invoke each samplesCallable with each item and putting the results in the above "result"
      // map.
      executor.invokeAll(
          items.stream()
              .map(
                  item ->
                      (Callable<MetricSamples>)
                          () -> {
                            try {
                              final MetricSamples samples = samplesCallable.apply(item);
                              synchronized (result) {
                                result.put(item, samples);
                              }
                            } catch (Exception e) {
                              // do NOT totally fail; just log and move on
                              log.warn("Error occurred during metrics collection", e);
                            }
                            return null; // not used
                          })
              .collect(Collectors.toList()));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    }

    return result;
  }

  protected MetricSamples request(SolrClient client, MetricsQuery query) throws IOException {
    MetricSamples samples = new MetricSamples();

    String baseUrlLabelValue = "";
    String zkHostLabelValue = "";
    if (client instanceof Http2SolrClient) {
      baseUrlLabelValue = ((Http2SolrClient) client).getBaseURL();
    } else if (client instanceof CloudSolrClient) {
      zkHostLabelValue = ((CloudSolrClient) client).getClusterStateProvider().getQuorumHosts();
    }

    GenericSolrRequest request = null;
    if (ADMIN_PATHS.contains(query.getPath())) {
      request =
          new GenericSolrRequest(
              METHOD.GET, query.getPath(), SolrRequestType.ADMIN, query.getParameters());
    } else {
      request =
          new GenericSolrRequest(
              METHOD.GET, query.getPath(), SolrRequestType.ADMIN, query.getParameters());
      request.setRequiresCollection(true);
    }

    NamedList<Object> response;
    try {
      if (query.getCollection().isEmpty() && query.getCore().isEmpty()) {
        response = client.request(request);
      } else if (query.getCore().isPresent()) {
        response = client.request(request, query.getCore().get());
      } else if (query.getCollection().isPresent()) {
        response = client.request(request, query.getCollection().get());
      } else {
        throw new AssertionError("Invalid configuration");
      }
      if (response == null) { // ideally we'd make this impossible
        throw new RuntimeException("no response from server");
      }
    } catch (SolrServerException | IOException e) {
      log.error("failed to request: {}", request.getPath(), e);
      return samples;
    }

    JsonNode jsonNode = OBJECT_MAPPER.readTree((String) response.get("response"));

    for (JsonQuery jsonQuery : query.getJsonQueries()) {
      try {
        List<JsonNode> results = jsonQuery.apply(jsonNode);
        for (JsonNode result : results) {
          String type = result.get("type").textValue();
          String name = result.get("name").textValue();
          String help = result.get("help").textValue();
          double value = result.get("value").doubleValue();

          List<String> labelNames = new ArrayList<>();
          List<String> labelValues = new ArrayList<>();

          /* Labels in response */
          for (JsonNode item : result.get("label_names")) {
            labelNames.add(item.textValue());
          }

          for (JsonNode item : result.get("label_values")) {
            labelValues.add(item.textValue());
          }

          /* Labels due to client */
          if (!baseUrlLabelValue.isEmpty()) {
            labelNames.add(BASE_URL_LABEL);
            labelValues.add(baseUrlLabelValue);
          } else if (!zkHostLabelValue.isEmpty()) {
            labelNames.add(ZK_HOST_LABEL);
            labelValues.add(zkHostLabelValue);
          }

          // Add the unique cluster ID, either as specified on cmdline --cluster-id or
          // baseUrl/zkHost
          labelNames.add(CLUSTER_ID_LABEL);
          labelValues.add(clusterId);

          // Deduce core if not there
          if (labelNames.indexOf("core") < 0
              && labelNames.indexOf("collection") >= 0
              && labelNames.indexOf("shard") >= 0
              && labelNames.indexOf("replica") >= 0) {
            labelNames.add("core");

            String collection = labelValues.get(labelNames.indexOf("collection"));
            String shard = labelValues.get(labelNames.indexOf("shard"));
            String replica = labelValues.get(labelNames.indexOf("replica"));

            labelValues.add(collection + "_" + shard + "_" + replica);
          }

          samples.addSamplesIfNotPresent(
              name,
              new Collector.MetricFamilySamples(
                  name, Collector.Type.valueOf(type), help, new ArrayList<>()));

          samples.addSampleIfMetricExists(
              name, new Collector.MetricFamilySamples.Sample(name, labelNames, labelValues, value));
        }
      } catch (JsonQueryException e) {
        log.error("Error apply JSON query={} to result", jsonQuery, e);
        scrapeErrorTotal.labels(zkHostLabelValue, baseUrlLabelValue, clusterId).inc();
      }
    }

    return samples;
  }
}
