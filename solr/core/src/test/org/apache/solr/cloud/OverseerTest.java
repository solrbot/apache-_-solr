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
package org.apache.solr.cloud;

import static org.apache.solr.cloud.AbstractDistribZkTestBase.verifyReplicaStatus;
import static org.apache.zookeeper.WatchedEvent.NO_ZXID;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.codahale.metrics.Snapshot;
import com.codahale.metrics.Timer;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.cloud.DistributedQueue;
import org.apache.solr.client.solrj.cloud.SolrCloudManager;
import org.apache.solr.client.solrj.impl.CloudHttp2SolrClient;
import org.apache.solr.client.solrj.impl.Http2SolrClient;
import org.apache.solr.client.solrj.impl.SolrClientCloudManager;
import org.apache.solr.client.solrj.impl.ZkClientClusterStateProvider;
import org.apache.solr.cloud.overseer.NodeMutator;
import org.apache.solr.cloud.overseer.OverseerAction;
import org.apache.solr.cloud.overseer.ZkWriteCommand;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.cloud.ClusterState;
import org.apache.solr.common.cloud.DocCollection;
import org.apache.solr.common.cloud.Replica;
import org.apache.solr.common.cloud.Slice;
import org.apache.solr.common.cloud.SolrZkClient;
import org.apache.solr.common.cloud.ZkCoreNodeProps;
import org.apache.solr.common.cloud.ZkNodeProps;
import org.apache.solr.common.cloud.ZkStateReader;
import org.apache.solr.common.params.CollectionParams;
import org.apache.solr.common.util.ExecutorUtil;
import org.apache.solr.common.util.IOUtils;
import org.apache.solr.common.util.SolrNamedThreadFactory;
import org.apache.solr.common.util.TimeSource;
import org.apache.solr.core.CloudConfig;
import org.apache.solr.core.ClusterSingletons;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.core.PluginInfo;
import org.apache.solr.core.SolrResourceLoader;
import org.apache.solr.handler.component.HttpShardHandler;
import org.apache.solr.handler.component.HttpShardHandlerFactory;
import org.apache.solr.metrics.SolrMetricManager;
import org.apache.solr.update.UpdateShardHandler;
import org.apache.solr.update.UpdateShardHandlerConfig;
import org.apache.solr.util.TimeOut;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.NoNodeException;
import org.apache.zookeeper.KeeperException.NodeExistsException;
import org.apache.zookeeper.KeeperException.SessionExpiredException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher.Event;
import org.apache.zookeeper.proto.WatcherEvent;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import org.mockito.Mockito;
import org.mockito.internal.configuration.plugins.Plugins;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.plugins.MemberAccessor;
import org.mockito.stubbing.Answer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SolrTestCaseJ4.SuppressSSL
public class OverseerTest extends SolrTestCaseJ4 {

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  static final int TIMEOUT = 30000;

  private static ZkTestServer server;

  private static SolrZkClient zkClient;

  private volatile boolean testDone = false;

  private final List<ZkController> zkControllers = Collections.synchronizedList(new ArrayList<>());
  private final List<Overseer> overseers = Collections.synchronizedList(new ArrayList<>());
  private final List<ZkStateReader> readers = Collections.synchronizedList(new ArrayList<>());
  private final List<SolrZkClient> zkClients = Collections.synchronizedList(new ArrayList<>());
  private final List<HttpShardHandlerFactory> httpShardHandlerFactorys =
      Collections.synchronizedList(new ArrayList<>());
  private final List<UpdateShardHandler> updateShardHandlers =
      Collections.synchronizedList(new ArrayList<>());
  private final List<SolrClient> solrClients = Collections.synchronizedList(new ArrayList<>());
  private static final String COLLECTION = SolrTestCaseJ4.DEFAULT_TEST_COLLECTION_NAME;

  public static class MockZKController {

    private final SolrZkClient zkClient;
    private final ZkStateReader zkStateReader;
    private final String nodeName;
    private final Map<String, ElectionContext> electionContext =
        Collections.synchronizedMap(new HashMap<String, ElectionContext>());
    private List<Overseer> overseers;

    public MockZKController(String zkAddress, String nodeName, List<Overseer> overseers)
        throws InterruptedException, IOException, KeeperException {
      this.overseers = overseers;
      this.nodeName = nodeName;
      zkClient =
          new SolrZkClient.Builder()
              .withUrl(zkAddress)
              .withTimeout(TIMEOUT, TimeUnit.MILLISECONDS)
              .build();

      ZkController.createClusterZkNodes(zkClient);

      zkStateReader = new ZkStateReader(zkClient);
      zkStateReader.createClusterStateWatchersAndUpdate();

      // live node
      final String nodePath = ZkStateReader.LIVE_NODES_ZKNODE + "/" + nodeName;
      zkClient.makePath(nodePath, CreateMode.EPHEMERAL, true);
    }

    private void deleteNode(final String path) {

      try {
        zkClient.delete(path, -1, true);
      } catch (NoNodeException e) {
        // fine
        log.warn("cancelElection did not find election node to remove");
      } catch (KeeperException e) {
        fail("Unexpected KeeperException!" + e);
      } catch (InterruptedException e) {
        fail("Unexpected InterruptedException!" + e);
      }
    }

    public void close() {
      for (ElectionContext ec : electionContext.values()) {
        try {
          ec.cancelElection();
        } catch (Exception e) {
          log.warn(String.format(Locale.ROOT, "Error cancelling election for %s", ec.id), e);
        }
      }
      deleteNode(ZkStateReader.LIVE_NODES_ZKNODE + "/" + nodeName);
      zkClient.close();
      zkStateReader.close();
    }

    /**
     * Create a collection. Note there's a similar but slightly different {@link
     * OverseerTest#createCollection(String, int)}.
     */
    public void createCollection(String collection, int numShards) throws Exception {
      // Create collection znode before having ClusterStateUpdater create state.json below, or it
      // will fail.
      zkClient.makePath(ZkStateReader.COLLECTIONS_ZKNODE + "/" + collection, true);

      ZkNodeProps m =
          new ZkNodeProps(
              Overseer.QUEUE_OPERATION,
              CollectionParams.CollectionAction.CREATE.toLower(),
              "name",
              collection,
              ZkStateReader.REPLICATION_FACTOR,
              "1",
              ZkStateReader.NUM_SHARDS_PROP,
              Integer.toString(numShards),
              "createNodeSet",
              "");
      final Overseer overseer = MiniSolrCloudCluster.getOpenOverseer(overseers);
      // This being an Overseer test, we force it to use the Overseer based cluster state update.
      // Look for "new Overseer" calls in this class.
      assertFalse(overseer.getDistributedClusterStateUpdater().isDistributedStateUpdate());
      ZkDistributedQueue q = overseer.getStateUpdateQueue();
      q.offer(m);
    }

    public String publishState(
        String collection,
        String coreName,
        String coreNodeName,
        String shard,
        Replica.State stateName,
        int numShards,
        boolean startElection,
        Overseer overseer)
        throws Exception {
      if (stateName == null) {
        ElectionContext ec = electionContext.remove(coreName);
        if (ec != null) {
          ec.cancelElection();
        }
        ZkNodeProps m =
            new ZkNodeProps(
                Overseer.QUEUE_OPERATION,
                OverseerAction.DELETECORE.toLower(),
                ZkStateReader.NODE_NAME_PROP,
                nodeName,
                ZkStateReader.BASE_URL_PROP,
                zkStateReader.getBaseUrlForNodeName(nodeName),
                ZkStateReader.CORE_NAME_PROP,
                coreName,
                ZkStateReader.CORE_NODE_NAME_PROP,
                coreNodeName,
                ZkStateReader.COLLECTION_PROP,
                collection);
        assertFalse(overseer.getDistributedClusterStateUpdater().isDistributedStateUpdate());
        ZkDistributedQueue q = overseer.getStateUpdateQueue();
        q.offer(m);
        return null;
      } else {
        ZkNodeProps m =
            new ZkNodeProps(
                Overseer.QUEUE_OPERATION,
                OverseerAction.STATE.toLower(),
                ZkStateReader.STATE_PROP,
                stateName.toString(),
                ZkStateReader.NODE_NAME_PROP,
                nodeName,
                ZkStateReader.BASE_URL_PROP,
                zkStateReader.getBaseUrlForNodeName(nodeName),
                ZkStateReader.CORE_NAME_PROP,
                coreName,
                ZkStateReader.CORE_NODE_NAME_PROP,
                coreNodeName,
                ZkStateReader.COLLECTION_PROP,
                collection,
                ZkStateReader.SHARD_ID_PROP,
                shard,
                ZkStateReader.NUM_SHARDS_PROP,
                Integer.toString(numShards));
        ZkDistributedQueue q = overseer.getStateUpdateQueue();
        q.offer(m);
      }

      if (startElection && collection.length() > 0) {
        zkStateReader.waitForState(
            collection,
            45000,
            TimeUnit.MILLISECONDS,
            (liveNodes, collectionState) -> getShardId(collectionState, coreNodeName) != null);
        String shardId = getShardId(collection, coreNodeName);
        if (shardId != null) {
          ElectionContext prevContext = electionContext.get(coreName);
          if (prevContext != null) {
            prevContext.cancelElection();
          }

          try {
            zkClient.makePath(
                "/collections/" + collection + "/leader_elect/" + shardId + "/election", true);
          } catch (NodeExistsException nee) {
          }
          ZkNodeProps props =
              new ZkNodeProps(
                  ZkStateReader.NODE_NAME_PROP,
                  nodeName,
                  ZkStateReader.BASE_URL_PROP,
                  zkStateReader.getBaseUrlForNodeName(nodeName),
                  ZkStateReader.CORE_NAME_PROP,
                  coreName,
                  ZkStateReader.SHARD_ID_PROP,
                  shardId,
                  ZkStateReader.COLLECTION_PROP,
                  collection,
                  ZkStateReader.CORE_NODE_NAME_PROP,
                  coreNodeName);
          LeaderElector elector = new LeaderElector(zkClient);
          ShardLeaderElectionContextBase ctx =
              new ShardLeaderElectionContextBase(
                  elector,
                  shardId,
                  collection,
                  nodeName + coreName,
                  props,
                  MockSolrSource.makeSimpleMock(overseer, zkStateReader, null));
          elector.setup(ctx);
          electionContext.put(coreName, ctx);
          elector.joinElection(ctx, false);
          return shardId;
        }
      }
      return null;
    }

    private String getShardId(String collection, String coreNodeName) {
      DocCollection dc = zkStateReader.getClusterState().getCollectionOrNull(collection);
      return getShardId(dc, coreNodeName);
    }

    private String getShardId(DocCollection collection, String coreNodeName) {
      if (collection == null) return null;
      Map<String, Slice> slices = collection.getSlicesMap();
      if (slices != null) {
        for (Slice slice : slices.values()) {
          for (Replica replica : slice.getReplicas()) {
            String cnn = replica.getName();
            if (coreNodeName.equals(cnn)) {
              return slice.getName();
            }
          }
        }
      }
      return null;
    }
  }

  @BeforeClass
  public static void beforeClass() throws Exception {
    assumeWorkingMockito();

    System.setProperty("solr.zkclienttimeout", "30000");

    Path zkDir = createTempDir("zkData");

    server = new ZkTestServer(zkDir);
    server.run();

    zkClient = server.getZkClient();

    initCore();
  }

  @Override
  @Before
  public void setUp() throws Exception {
    testDone = false;

    super.setUp();
  }

  @AfterClass
  public static void afterClass() throws Exception {
    if (null != zkClient) {
      zkClient.printLayoutToStream(System.out);
    }

    System.clearProperty("solr.zkclienttimeout");

    if (null != server) {
      server.shutdown();
    }

    server = null;
  }

  @Override
  @After
  public void tearDown() throws Exception {
    testDone = true;

    ExecutorService customThreadPool =
        ExecutorUtil.newMDCAwareCachedThreadPool(new SolrNamedThreadFactory("closeThreadPool"));

    for (ZkController zkController : zkControllers) {
      customThreadPool.execute(zkController::close);
    }

    for (HttpShardHandlerFactory httpShardHandlerFactory : httpShardHandlerFactorys) {
      customThreadPool.execute(httpShardHandlerFactory::close);
    }

    for (UpdateShardHandler updateShardHandler : updateShardHandlers) {
      customThreadPool.execute(updateShardHandler::close);
    }

    for (SolrClient solrClient : solrClients) {
      customThreadPool.execute(() -> IOUtils.closeQuietly(solrClient));
    }

    for (ZkStateReader reader : readers) {
      customThreadPool.execute(reader::close);
    }

    for (SolrZkClient solrZkClient : zkClients) {
      customThreadPool.execute(() -> IOUtils.closeQuietly(solrZkClient));
    }

    ExecutorUtil.shutdownAndAwaitTermination(customThreadPool);

    customThreadPool =
        ExecutorUtil.newMDCAwareCachedThreadPool(new SolrNamedThreadFactory("closeThreadPool"));

    for (Overseer overseer : overseers) {
      customThreadPool.execute(overseer::close);
    }

    ExecutorUtil.shutdownAndAwaitTermination(customThreadPool);

    overseers.clear();
    zkControllers.clear();
    httpShardHandlerFactorys.clear();
    updateShardHandlers.clear();
    solrClients.clear();
    readers.clear();
    zkClients.clear();

    server.tryCleanSolrZkNode();
    server.makeSolrZkNode();

    super.tearDown();
  }

  /**
   * This method creates a collection. It is different from {@link
   * MockZKController#createCollection(String, int)} in the way the {@link ZkDistributedQueue} is
   * obtained.
   */
  private void createCollection(String collection, int numShards) throws Exception {
    // Create collection znode before having ClusterStateUpdater create state.json below, or it
    // will fail.
    zkClient.makePath(ZkStateReader.COLLECTIONS_ZKNODE + "/" + collection, true);

    ZkNodeProps m =
        new ZkNodeProps(
            Overseer.QUEUE_OPERATION,
            CollectionParams.CollectionAction.CREATE.toLower(),
            "name",
            collection,
            ZkStateReader.REPLICATION_FACTOR,
            "1",
            ZkStateReader.NUM_SHARDS_PROP,
            Integer.toString(numShards),
            "createNodeSet",
            "");
    ZkDistributedQueue q = getOverseerZero().getStateUpdateQueue();
    q.offer(m);
  }

  @Test
  public void testShardAssignment() throws Exception {

    MockZKController mockController = null;
    SolrZkClient overseerClient = null;

    try {
      ZkController.createClusterZkNodes(zkClient);

      overseerClient = electNewOverseer(server.getZkAddress());

      try (ZkStateReader reader = new ZkStateReader(zkClient)) {
        reader.createClusterStateWatchersAndUpdate();

        mockController =
            new MockZKController(server.getZkAddress(), "127.0.0.1:8983_solr", overseers);

        final int numShards = 6; // this is not the number of shards in the collection

        createCollection(COLLECTION, 3);

        for (int i = 0; i < numShards; i++) {
          assertNotNull(
              "shard got no id?",
              mockController.publishState(
                  COLLECTION,
                  "core" + (i + 1),
                  "node" + (i + 1),
                  "shard" + ((i % 3) + 1),
                  Replica.State.ACTIVE,
                  3,
                  true,
                  overseers.get(0)));
        }

        reader.waitForState(
            COLLECTION,
            30,
            TimeUnit.SECONDS,
            MiniSolrCloudCluster.expectedShardsAndActiveReplicas(3, 6));

        final Map<String, Replica> rmap =
            reader.getClusterState().getCollection(COLLECTION).getSlice("shard1").getReplicasMap();
        assertEquals(rmap.toString(), 2, rmap.size());
        assertEquals(
            rmap.toString(),
            2,
            reader
                .getClusterState()
                .getCollection(COLLECTION)
                .getSlice("shard2")
                .getReplicasMap()
                .size());
        assertEquals(
            rmap.toString(),
            2,
            reader
                .getClusterState()
                .getCollection(COLLECTION)
                .getSlice("shard3")
                .getReplicasMap()
                .size());

        // make sure leaders are in cloud state
        assertNotNull(reader.getLeaderUrl(COLLECTION, "shard1", 15000));
        assertNotNull(reader.getLeaderUrl(COLLECTION, "shard2", 15000));
        assertNotNull(reader.getLeaderUrl(COLLECTION, "shard3", 15000));
      }
    } finally {
      if (mockController != null) {
        mockController.close();
      }
      close(overseerClient);
    }
  }

  @Test
  public void testBadQueueItem() throws Exception {

    MockZKController mockController = null;
    SolrZkClient overseerClient = null;

    try {
      ZkController.createClusterZkNodes(zkClient);

      overseerClient = electNewOverseer(server.getZkAddress());

      try (ZkStateReader reader = new ZkStateReader(zkClient)) {
        reader.createClusterStateWatchersAndUpdate();

        mockController =
            new MockZKController(server.getZkAddress(), "127.0.0.1:8983_solr", overseers);

        final int numShards = 3;
        mockController.createCollection(COLLECTION, 3);
        for (int i = 0; i < numShards; i++) {
          assertNotNull(
              "shard got no id?",
              mockController.publishState(
                  COLLECTION,
                  "core" + (i + 1),
                  "node" + (i + 1),
                  "shard" + ((i % 3) + 1),
                  Replica.State.ACTIVE,
                  3,
                  true,
                  overseers.get(0)));
        }

        reader.waitForState(
            COLLECTION,
            30,
            TimeUnit.SECONDS,
            MiniSolrCloudCluster.expectedShardsAndActiveReplicas(3, 3));

        assertEquals(
            1,
            reader
                .getClusterState()
                .getCollection(COLLECTION)
                .getSlice("shard1")
                .getReplicasMap()
                .size());
        assertEquals(
            1,
            reader
                .getClusterState()
                .getCollection(COLLECTION)
                .getSlice("shard2")
                .getReplicasMap()
                .size());
        assertEquals(
            1,
            reader
                .getClusterState()
                .getCollection(COLLECTION)
                .getSlice("shard3")
                .getReplicasMap()
                .size());

        // make sure leaders are in cloud state
        assertNotNull(reader.getLeaderUrl(COLLECTION, "shard1", 15000));
        assertNotNull(reader.getLeaderUrl(COLLECTION, "shard2", 15000));
        assertNotNull(reader.getLeaderUrl(COLLECTION, "shard3", 15000));

        // publish a bad queue item
        String emptyCollectionName = "";
        mockController.publishState(
            emptyCollectionName,
            "core0",
            "node0",
            "shard1",
            Replica.State.ACTIVE,
            1,
            true,
            overseers.get(0));
        mockController.publishState(
            emptyCollectionName, "core0", "node0", "shard1", null, 1, true, overseers.get(0));

        mockController.createCollection("collection2", 3);
        // make sure the Overseer is still processing items
        for (int i = 0; i < numShards; i++) {
          assertNotNull(
              "shard got no id?",
              mockController.publishState(
                  "collection2",
                  "core" + (i + 1),
                  "node" + (i + 1),
                  "shard" + ((i % 3) + 1),
                  Replica.State.ACTIVE,
                  3,
                  true,
                  overseers.get(0)));
        }

        reader.waitForState(
            "collection2",
            30,
            TimeUnit.SECONDS,
            MiniSolrCloudCluster.expectedShardsAndActiveReplicas(3, 3));

        assertEquals(
            1,
            reader
                .getClusterState()
                .getCollection("collection2")
                .getSlice("shard1")
                .getReplicasMap()
                .size());
        assertEquals(
            1,
            reader
                .getClusterState()
                .getCollection("collection2")
                .getSlice("shard2")
                .getReplicasMap()
                .size());
        assertEquals(
            1,
            reader
                .getClusterState()
                .getCollection("collection2")
                .getSlice("shard3")
                .getReplicasMap()
                .size());

        // make sure leaders are in cloud state
        assertNotNull(reader.getLeaderUrl("collection2", "shard1", 15000));
        assertNotNull(reader.getLeaderUrl("collection2", "shard2", 15000));
        assertNotNull(reader.getLeaderUrl("collection2", "shard3", 15000));
      }

    } finally {
      if (mockController != null) {
        mockController.close();
      }
      close(overseerClient);
    }
  }

  @Test
  @SuppressWarnings({"try"})
  public void testDownNodeFailover() throws Exception {
    MockZKController mockController = null;
    SolrZkClient overseerClient = null;

    try {

      ZkController.createClusterZkNodes(zkClient);

      overseerClient = electNewOverseer(server.getZkAddress());

      try (ZkStateReader reader = new ZkStateReader(zkClient)) {
        reader.createClusterStateWatchersAndUpdate();

        mockController =
            new MockZKController(server.getZkAddress(), "127.0.0.1:8983_solr", overseers);

        try (ZkController zkController =
            createMockZkController(server.getZkAddress(), zkClient, reader)) {

          for (int i = 0; i < 5; i++) {
            mockController.createCollection("collection" + i, 1);
            assertNotNull(
                "shard got no id?",
                mockController.publishState(
                    "collection" + i,
                    "core1",
                    "core_node1",
                    "shard1",
                    Replica.State.ACTIVE,
                    1,
                    true,
                    overseers.get(0)));
          }
        }
        ZkNodeProps m =
            new ZkNodeProps(
                Overseer.QUEUE_OPERATION,
                OverseerAction.DOWNNODE.toLower(),
                ZkStateReader.NODE_NAME_PROP,
                "127.0.0.1:8983_solr");
        List<ZkWriteCommand> commands = new NodeMutator(null).downNode(reader.getClusterState(), m);

        ZkDistributedQueue q = getOverseerZero().getStateUpdateQueue();

        q.offer(m);

        verifyReplicaStatus(
            reader, commands.get(0).name, "shard1", "core_node1", Replica.State.DOWN);
        overseerClient.close();

        overseerClient = electNewOverseer(server.getZkAddress());
        for (int i = 0; i < 5; i++) {
          verifyReplicaStatus(reader, "collection" + i, "shard1", "core_node1", Replica.State.DOWN);
        }
      }
    } finally {
      if (mockController != null) {
        mockController.close();
      }
      close(overseerClient);
    }
  }

  // wait until collections are available
  private void waitForCollections(ZkStateReader stateReader, String... collections)
      throws InterruptedException, TimeoutException {
    int maxIterations = 100;
    while (0 < maxIterations--) {

      final ClusterState state = stateReader.getClusterState();
      Set<String> availableCollections = (Set<String>) state.getCollectionNames();
      int availableCount = 0;
      for (String requiredCollection : collections) {
        stateReader.waitForState(
            requiredCollection,
            30000,
            TimeUnit.MILLISECONDS,
            (liveNodes, collectionState) -> collectionState != null);
        if (availableCollections.contains(requiredCollection)) {
          availableCount++;
        }
        if (availableCount == collections.length) return;
      }
    }
    log.warn(
        "Timeout waiting for collections: {} state: {}",
        Arrays.asList(collections),
        stateReader.getClusterState());
  }

  @Test
  public void testStateChange() throws Exception {

    ZkStateReader reader = null;
    SolrZkClient overseerClient = null;

    try {

      ZkController.createClusterZkNodes(zkClient);

      reader = new ZkStateReader(zkClient);
      reader.createClusterStateWatchersAndUpdate();

      overseerClient = electNewOverseer(server.getZkAddress());

      ZkDistributedQueue q = getOverseerZero().getStateUpdateQueue();

      createCollection(COLLECTION, 1);

      ZkNodeProps m =
          new ZkNodeProps(
              Overseer.QUEUE_OPERATION,
              OverseerAction.STATE.toLower(),
              ZkStateReader.NODE_NAME_PROP,
              "node1:8983_",
              ZkStateReader.COLLECTION_PROP,
              COLLECTION,
              ZkStateReader.SHARD_ID_PROP,
              "shard1",
              ZkStateReader.CORE_NAME_PROP,
              "core1",
              ZkStateReader.CORE_NODE_NAME_PROP,
              "core_node1",
              ZkStateReader.STATE_PROP,
              Replica.State.RECOVERING.toString());

      q.offer(m);

      waitForCollections(reader, COLLECTION);
      verifyReplicaStatus(reader, "collection1", "shard1", "core_node1", Replica.State.RECOVERING);

      // publish node state (active)
      m =
          new ZkNodeProps(
              Overseer.QUEUE_OPERATION,
              OverseerAction.STATE.toLower(),
              ZkStateReader.NODE_NAME_PROP,
              "node1:8983_",
              ZkStateReader.COLLECTION_PROP,
              COLLECTION,
              ZkStateReader.SHARD_ID_PROP,
              "shard1",
              ZkStateReader.CORE_NAME_PROP,
              "core1",
              ZkStateReader.STATE_PROP,
              Replica.State.ACTIVE.toString());

      q.offer(m);

      verifyReplicaStatus(reader, "collection1", "shard1", "core_node1", Replica.State.ACTIVE);

    } finally {

      close(overseerClient);

      close(reader);
    }
  }

  private void verifyShardLeader(
      ZkStateReader reader, String collection, String shard, String expectedCore)
      throws InterruptedException, TimeoutException {

    reader.waitForState(
        collection,
        15000,
        TimeUnit.MILLISECONDS,
        (liveNodes, collectionState) ->
            collectionState != null
                && expectedCore.equals(
                    (collectionState.getLeader(shard) != null)
                        ? collectionState.getLeader(shard).getStr(ZkStateReader.CORE_NAME_PROP)
                        : null));

    DocCollection docCollection = reader.getClusterState().getCollection(collection);
    assertEquals(
        "Unexpected shard leader coll:" + collection + " shard:" + shard,
        expectedCore,
        (docCollection.getLeader(shard) != null)
            ? docCollection.getLeader(shard).getStr(ZkStateReader.CORE_NAME_PROP)
            : null);
  }

  private Overseer getOpenOverseer() {
    Overseer overseer = MiniSolrCloudCluster.getOpenOverseer(overseers);
    assertFalse(overseer.getDistributedClusterStateUpdater().isDistributedStateUpdate());
    return overseer;
  }

  private Overseer getOverseerZero() {
    Overseer overseer = overseers.get(0);
    assertFalse(overseer.getDistributedClusterStateUpdater().isDistributedStateUpdate());
    return overseer;
  }

  @Test
  public void testOverseerFailure() throws Exception {

    SolrZkClient overseerClient = null;
    ZkStateReader reader = null;
    MockZKController mockController = null;

    try {

      final String core = "core1";
      final String core_node = "core_node1";
      final String shard = "shard1";
      final int numShards = 1;

      ZkController.createClusterZkNodes(zkClient);

      reader = new ZkStateReader(zkClient);
      reader.createClusterStateWatchersAndUpdate();

      mockController =
          new MockZKController(server.getZkAddress(), "127.0.0.1:8983_solr", overseers);

      overseerClient = electNewOverseer(server.getZkAddress());

      mockController.createCollection(COLLECTION, 1);

      mockController.publishState(
          COLLECTION,
          core,
          core_node,
          "shard1",
          Replica.State.RECOVERING,
          numShards,
          true,
          overseers.get(0));

      waitForCollections(reader, COLLECTION);
      verifyReplicaStatus(reader, COLLECTION, "shard1", "core_node1", Replica.State.RECOVERING);

      mockController.publishState(
          COLLECTION,
          core,
          core_node,
          "shard1",
          Replica.State.ACTIVE,
          numShards,
          true,
          overseers.get(0));

      verifyReplicaStatus(reader, COLLECTION, "shard1", "core_node1", Replica.State.ACTIVE);

      mockController.publishState(
          COLLECTION,
          core,
          core_node,
          "shard1",
          Replica.State.RECOVERING,
          numShards,
          true,
          overseers.get(0));

      overseerClient.close();

      overseerClient = electNewOverseer(server.getZkAddress());

      verifyReplicaStatus(reader, COLLECTION, "shard1", "core_node1", Replica.State.RECOVERING);

      assertEquals(
          "Live nodes count does not match", 1, reader.getClusterState().getLiveNodes().size());
      assertEquals(
          shard + " replica count does not match",
          1,
          reader
              .getClusterState()
              .getCollection(COLLECTION)
              .getSlice(shard)
              .getReplicasMap()
              .size());
      mockController.publishState(
          COLLECTION, core, core_node, "shard1", null, numShards, true, overseers.get(1));

      reader.waitForState(
          COLLECTION,
          5000,
          TimeUnit.MILLISECONDS,
          (liveNodes, collectionState) ->
              collectionState != null && collectionState.getReplica(core_node) == null);

      reader.forceUpdateCollection(COLLECTION);
      // as of SOLR-5209 core removal does not cascade to remove the slice and collection
      assertTrue(
          COLLECTION + " should remain after removal of the last core",
          reader.getClusterState().hasCollection(COLLECTION));
      assertNull(
          core_node + " should be gone after publishing the null state",
          reader.getClusterState().getCollection(COLLECTION).getReplica(core_node));
    } finally {
      close(mockController);
      close(overseerClient);
      close(reader);
    }
  }

  @Test
  public void testOverseerStatsReset() throws Exception {
    ZkStateReader reader = null;
    MockZKController mockController = null;

    try {

      ZkController.createClusterZkNodes(zkClient);

      reader = new ZkStateReader(zkClient);
      reader.createClusterStateWatchersAndUpdate();

      mockController =
          new MockZKController(server.getZkAddress(), "127.0.0.1:8983_solr", overseers);

      LeaderElector overseerElector = new LeaderElector(zkClient);
      if (overseers.size() > 0) {
        overseers.get(overseers.size() - 1).close();
        overseers.get(overseers.size() - 1).getZkStateReader().getZkClient().close();
      }
      ZkController zkController = createMockZkController(server.getZkAddress(), zkClient, reader);

      UpdateShardHandler updateShardHandler =
          new UpdateShardHandler(UpdateShardHandlerConfig.DEFAULT);
      updateShardHandlers.add(updateShardHandler);
      HttpShardHandlerFactory httpShardHandlerFactory = new HttpShardHandlerFactory();
      httpShardHandlerFactory.init(new PluginInfo("shardHandlerFactory", Collections.emptyMap()));
      httpShardHandlerFactorys.add(httpShardHandlerFactory);
      Overseer overseer =
          new Overseer(
              (HttpShardHandler) httpShardHandlerFactory.getShardHandler(),
              updateShardHandler,
              "/admin/cores",
              reader,
              zkController,
              new CloudConfig.CloudConfigBuilder("127.0.0.1", 8983)
                  .setUseDistributedClusterStateUpdates(false)
                  .setUseDistributedCollectionConfigSetExecution(false)
                  .build());
      overseers.add(overseer);
      ElectionContext ec =
          new OverseerElectionContext(zkClient, overseer, server.getZkAddress().replace("/", "_"));
      overseerElector.setup(ec);
      overseerElector.joinElection(ec, false);

      mockController.createCollection(COLLECTION, 1);

      mockController.publishState(
          COLLECTION,
          "core1",
          "core_node1",
          "shard1",
          Replica.State.ACTIVE,
          1,
          true,
          overseers.get(0));

      assertNotNull(overseer.getStats());
      assertTrue((overseer.getStats().getSuccessCount(OverseerAction.STATE.toLower())) > 0);

      // shut it down
      overseer.close();
      ec.cancelElection();

      // start it again
      overseerElector.setup(ec);
      overseerElector.joinElection(ec, false);
      assertNotNull(overseer.getStats());
      assertEquals(0, (overseer.getStats().getSuccessCount(OverseerAction.STATE.toLower())));

    } finally {
      close(mockController);
      close(reader);
    }
  }

  private AtomicInteger killCounter = new AtomicInteger();

  private class OverseerRestarter implements Runnable {
    SolrZkClient overseerClient = null;
    public volatile boolean run = true;
    private final String zkAddress;

    public OverseerRestarter(String zkAddress) {
      this.zkAddress = zkAddress;
    }

    @Override
    public void run() {
      try {
        overseerClient = electNewOverseer(zkAddress);
        while (run) {
          if (killCounter.get() > 0) {
            try {
              killCounter.decrementAndGet();
              log.info("Killing overseer.");
              overseerClient.close();
              overseerClient = electNewOverseer(zkAddress);
            } catch (Throwable e) {
              log.error("error killing overseer", e);
            }
          }
          try {
            Thread.sleep(100);
          } catch (Throwable e) {
            log.error("error during sleep", e);
          }
        }
      } catch (Throwable t) {
        // ignore
      } finally {
        if (overseerClient != null) {
          try {
            //    overseerClient.close();
          } catch (Throwable t) {
            // ignore
          }
        }
      }
    }
  }

  @Test
  public void testExceptionWhenFlushClusterState() throws Exception {

    SolrZkClient overseerClient = null;
    ZkStateReader reader = null;

    try {

      ZkController.createClusterZkNodes(zkClient);

      reader = new ZkStateReader(zkClient);
      reader.createClusterStateWatchersAndUpdate();

      // We did not create /collections/collection1 -> this message will cause exception when
      // Overseer tries to flush the collection state
      ZkNodeProps badMessage =
          new ZkNodeProps(
              Overseer.QUEUE_OPERATION,
              CollectionParams.CollectionAction.CREATE.toLower(),
              "name",
              "collection1",
              ZkStateReader.REPLICATION_FACTOR,
              "1",
              ZkStateReader.NUM_SHARDS_PROP,
              "1",
              "createNodeSet",
              "");
      ZkDistributedQueue workQueue = Overseer.getInternalWorkQueue(zkClient, new Stats());
      workQueue.offer(badMessage);
      overseerClient = electNewOverseer(server.getZkAddress());

      ZkDistributedQueue q = getOpenOverseer().getStateUpdateQueue();
      q.offer(badMessage);

      TimeOut timeOut = new TimeOut(10, TimeUnit.SECONDS, TimeSource.NANO_TIME);
      while (!timeOut.hasTimedOut()) {
        if (q.peek() == null) {
          break;
        }
        Thread.sleep(50);
      }

      assertNull(showQpeek(workQueue), workQueue.peek());
      assertNull(showQpeek(q), q.peek());
    } finally {
      close(overseerClient);
      close(reader);
    }
  }

  private String showQpeek(ZkDistributedQueue q) throws KeeperException, InterruptedException {
    if (q == null) {
      return "";
    }
    byte[] bytes = q.peek();
    if (bytes == null) {
      return "";
    }

    ZkNodeProps json = ZkNodeProps.load(bytes);
    return json.toString();
  }

  @Test
  public void testShardLeaderChange() throws Exception {
    ZkStateReader reader = null;
    MockZKController mockController = null;
    MockZKController mockController2 = null;
    OverseerRestarter killer = null;
    Thread killerThread = null;

    try {
      ZkController.createClusterZkNodes(zkClient);

      killer = new OverseerRestarter(server.getZkAddress());
      killerThread = new Thread(killer, "OverseerRestarter");
      killerThread.start();

      reader = new ZkStateReader(zkClient);
      reader.createClusterStateWatchersAndUpdate();

      UpdateShardHandler updateShardHandler =
          new UpdateShardHandler(UpdateShardHandlerConfig.DEFAULT);
      updateShardHandlers.add(updateShardHandler);
      HttpShardHandlerFactory httpShardHandlerFactory = new HttpShardHandlerFactory();
      httpShardHandlerFactorys.add(httpShardHandlerFactory);

      electNewOverseer(server.getZkAddress());

      // Create collection znode before repeatedly trying to enqueue the cluster state update
      // message
      zkClient.makePath(ZkStateReader.COLLECTIONS_ZKNODE + "/" + COLLECTION, true);

      for (int i = 0; i < atLeast(4); i++) {
        killCounter.incrementAndGet(); // for each round allow 1 kill

        mockController = new MockZKController(server.getZkAddress(), "node1:8983_", overseers);

        TimeOut timeout = new TimeOut(10, TimeUnit.SECONDS, TimeSource.NANO_TIME);
        while (!timeout.hasTimedOut()) {
          try {
            // We must only retry the enqueue to Overseer, not the collection znode creation (that
            // doesn't depend on Overseer)
            ZkNodeProps m =
                new ZkNodeProps(
                    Overseer.QUEUE_OPERATION,
                    CollectionParams.CollectionAction.CREATE.toLower(),
                    "name",
                    COLLECTION,
                    ZkStateReader.REPLICATION_FACTOR,
                    "1",
                    ZkStateReader.NUM_SHARDS_PROP,
                    "1",
                    "createNodeSet",
                    "");
            ZkDistributedQueue q = getOpenOverseer().getStateUpdateQueue();
            q.offer(m);
            break;
          } catch (SolrException | KeeperException | IllegalStateException e) {
            log.error("error updating state", e);
          }
        }

        timeout = new TimeOut(10, TimeUnit.SECONDS, TimeSource.NANO_TIME);
        while (!timeout.hasTimedOut()) {
          try {
            mockController.publishState(
                COLLECTION,
                "core1",
                "node1",
                "shard1",
                Replica.State.ACTIVE,
                1,
                true,
                getOpenOverseer());
            break;
          } catch (SolrException | KeeperException | IllegalStateException e) {
            log.error("error publishing state", e);
          }
        }

        if (mockController2 != null) {
          mockController2.close();
          mockController2 = null;
        }

        Thread.sleep(100);

        timeout = new TimeOut(1, TimeUnit.SECONDS, TimeSource.NANO_TIME);
        while (!timeout.hasTimedOut()) {
          try {
            mockController.publishState(
                COLLECTION,
                "core1",
                "node1",
                "shard1",
                Replica.State.RECOVERING,
                1,
                true,
                getOpenOverseer());
            break;
          } catch (SolrException | IllegalStateException e) {
            log.error("error publishing state", e);
          }
        }

        mockController2 = new MockZKController(server.getZkAddress(), "node2:8984_", overseers);

        timeout = new TimeOut(10, TimeUnit.SECONDS, TimeSource.NANO_TIME);
        while (!timeout.hasTimedOut()) {
          try {
            mockController.publishState(
                COLLECTION,
                "core1",
                "node1",
                "shard1",
                Replica.State.ACTIVE,
                1,
                true,
                getOpenOverseer());
            break;
          } catch (SolrException | IllegalStateException e) {
            log.error("error publishing state", e);
          }
        }

        verifyShardLeader(reader, COLLECTION, "shard1", "core1");

        timeout = new TimeOut(10, TimeUnit.SECONDS, TimeSource.NANO_TIME);
        while (!timeout.hasTimedOut()) {
          try {
            mockController2.publishState(
                COLLECTION,
                "core4",
                "node2",
                "shard1",
                Replica.State.ACTIVE,
                1,
                true,
                getOpenOverseer());
            break;
          } catch (SolrException | IllegalStateException e) {
            log.error("error publishing state", e);
          }
        }

        mockController.close();
        mockController = null;

        ZkController zkController = createMockZkController(server.getZkAddress(), null, reader);
        zkControllers.add(zkController);

        TimeOut timeOut = new TimeOut(10, TimeUnit.SECONDS, TimeSource.NANO_TIME);
        timeOut.waitFor(
            "Timed out waiting to see core4 as leader",
            () -> {
              ZkCoreNodeProps leaderProps;
              try {
                leaderProps = zkController.getLeaderProps(COLLECTION, "shard1", 1000, false);
              } catch (SolrException e) {
                return false;
              } catch (InterruptedException e) {
                throw new RuntimeException(e);
              } catch (SessionExpiredException e) {
                return false;
              }
              if (leaderProps.getCoreName().equals("core4")) {
                return true;
              }
              return false;
            });
      }

    } finally {
      if (killer != null) {
        killer.run = false;
        if (killerThread != null) {
          killerThread.join();
        }
      }
      close(mockController);

      close(mockController2);
      close(reader);
    }
  }

  @Test
  public void testDoubleAssignment() throws Exception {

    SolrZkClient overseerClient = null;
    ZkStateReader reader = null;
    MockZKController mockController = null;

    try {

      ZkController.createClusterZkNodes(zkClient);

      reader = new ZkStateReader(zkClient);
      reader.createClusterStateWatchersAndUpdate();

      mockController =
          new MockZKController(server.getZkAddress(), "127.0.0.1:8983_solr", overseers);

      overseerClient = electNewOverseer(server.getZkAddress());

      mockController.createCollection(COLLECTION, 1);

      mockController.publishState(
          COLLECTION,
          "core1",
          "core_node1",
          "shard1",
          Replica.State.RECOVERING,
          1,
          true,
          overseers.get(0));

      waitForCollections(reader, COLLECTION);

      verifyReplicaStatus(reader, COLLECTION, "shard1", "core_node1", Replica.State.RECOVERING);

      mockController.close();

      mockController =
          new MockZKController(server.getZkAddress(), "127.0.0.1:8983_solr", overseers);

      mockController.publishState(
          COLLECTION,
          "core1",
          "core_node1",
          "shard1",
          Replica.State.RECOVERING,
          1,
          true,
          overseers.get(0));

      reader.forceUpdateCollection(COLLECTION);
      ClusterState state = reader.getClusterState();

      long numFound =
          state
              .collectionStream()
              .map(DocCollection::getSlices)
              .flatMap(Collection::stream)
              .filter(slice -> slice.getReplicasMap().get("core_node1") != null)
              .count();

      assertEquals("Shard was found more than once in ClusterState", 1, numFound);
    } finally {
      close(overseerClient);
      close(mockController);
      close(reader);
    }
  }

  @Test
  @Ignore
  public void testPerformance() throws Exception {

    SolrZkClient overseerClient = null;
    ZkStateReader reader = null;
    MockZKController mockController = null;

    try {

      ZkController.createClusterZkNodes(zkClient);
      overseerClient = electNewOverseer(server.getZkAddress());

      reader = new ZkStateReader(zkClient);
      reader.createClusterStateWatchersAndUpdate();

      mockController =
          new MockZKController(server.getZkAddress(), "127.0.0.1:8983_solr", overseers);

      final int MAX_COLLECTIONS = 10, MAX_CORES = 10, MAX_STATE_CHANGES = 20000;

      for (int i = 0; i < MAX_COLLECTIONS; i++) {
        zkClient.makePath("/collections/perf" + i, true);
        ZkNodeProps m =
            new ZkNodeProps(
                Overseer.QUEUE_OPERATION,
                CollectionParams.CollectionAction.CREATE.toLower(),
                "name",
                "perf" + i,
                ZkStateReader.NUM_SHARDS_PROP,
                "1",
                ZkStateReader.REPLICATION_FACTOR,
                "1");
        ZkDistributedQueue q = getOverseerZero().getStateUpdateQueue();
        q.offer(m);
      }

      for (int i = 0, j = 0, k = 0; i < MAX_STATE_CHANGES; i++, j++, k++) {
        ZkNodeProps m =
            new ZkNodeProps(
                Overseer.QUEUE_OPERATION,
                OverseerAction.STATE.toLower(),
                ZkStateReader.STATE_PROP,
                Replica.State.RECOVERING.toString(),
                ZkStateReader.NODE_NAME_PROP,
                "node1",
                ZkStateReader.CORE_NAME_PROP,
                "core" + k,
                ZkStateReader.SHARD_ID_PROP,
                "shard1",
                ZkStateReader.CORE_NODE_NAME_PROP,
                "node1",
                ZkStateReader.COLLECTION_PROP,
                "perf" + j,
                ZkStateReader.NUM_SHARDS_PROP,
                "1");
        ZkDistributedQueue q = getOverseerZero().getStateUpdateQueue();
        q.offer(m);
        if (j >= MAX_COLLECTIONS - 1) j = 0;
        if (k >= MAX_CORES - 1) k = 0;
        if (i > 0 && i % 100 == 0) log.info("Published {} items", i);
      }

      // let's create a sentinel collection which we'll use to wait for overseer to complete
      // operations
      createCollection("perf_sentinel", 1);

      Timer t = new Timer();
      Timer.Context context = t.time();
      reader.waitForState(
          "perf_sentinel",
          15000,
          TimeUnit.MILLISECONDS,
          (liveNodes, collectionState) -> collectionState != null);
      context.stop();

      log.info("Overseer loop finished processing: ");
      printTimingStats(t);

      Overseer overseer = overseers.get(0);
      Stats stats = overseer.getStats();

      String[] interestingOps = {"state", "update_state", "am_i_leader", ""};
      Arrays.sort(interestingOps);
      for (Map.Entry<String, Stats.Stat> entry : stats.getStats().entrySet()) {
        String op = entry.getKey();
        if (Arrays.binarySearch(interestingOps, op) < 0) continue;
        Stats.Stat stat = entry.getValue();
        if (log.isInfoEnabled()) {
          log.info("op: {}, success: {}, failure: {}", op, stat.success.get(), stat.errors.get());
        }
        Timer timer = stat.requestTime;
        printTimingStats(timer);
      }

    } finally {
      close(overseerClient);
      close(mockController);
      close(reader);
    }
  }

  private void printTimingStats(Timer timer) {
    Snapshot snapshot = timer.getSnapshot();
    if (log.isInfoEnabled()) {
      log.info("\t avgRequestsPerSecond: {}", timer.getMeanRate());
      log.info("\t 5minRateRequestsPerSecond: {}", timer.getFiveMinuteRate()); // nowarn
      log.info("\t 15minRateRequestsPerSecond: {}", timer.getFifteenMinuteRate()); // nowarn
      log.info("\t avgTimePerRequest: {}", nsToMs(snapshot.getMean())); // nowarn
      log.info("\t medianRequestTime: {}", nsToMs(snapshot.getMedian())); // nowarn
      log.info("\t 75thPcRequestTime: {}", nsToMs(snapshot.get75thPercentile())); // nowarn
      log.info("\t 95thPcRequestTime: {}", nsToMs(snapshot.get95thPercentile())); // nowarn
      log.info("\t 99thPcRequestTime: {}", nsToMs(snapshot.get99thPercentile())); // nowarn
      log.info("\t 999thPcRequestTime: {}", nsToMs(snapshot.get999thPercentile())); // nowarn
    }
  }

  private static long nsToMs(double ns) {
    return TimeUnit.MILLISECONDS.convert((long) ns, TimeUnit.NANOSECONDS);
  }

  private void close(MockZKController mockController) {
    if (mockController != null) {
      mockController.close();
    }
  }

  @Test
  public void testReplay() throws Exception {

    SolrZkClient overseerClient = null;
    ZkStateReader reader = null;

    try {

      ZkController.createClusterZkNodes(zkClient);

      reader = new ZkStateReader(zkClient);
      reader.createClusterStateWatchersAndUpdate();
      // prepopulate work queue with some items to emulate previous overseer died before persisting
      // state
      DistributedQueue queue = Overseer.getInternalWorkQueue(zkClient, new Stats());

      zkClient.makePath(ZkStateReader.COLLECTIONS_ZKNODE + "/" + COLLECTION, true);

      ZkNodeProps m =
          new ZkNodeProps(
              Overseer.QUEUE_OPERATION,
              CollectionParams.CollectionAction.CREATE.toLower(),
              "name",
              COLLECTION,
              ZkStateReader.REPLICATION_FACTOR,
              "1",
              ZkStateReader.NUM_SHARDS_PROP,
              "1",
              "createNodeSet",
              "");
      queue.offer(m);
      m =
          new ZkNodeProps(
              Overseer.QUEUE_OPERATION,
              OverseerAction.STATE.toLower(),
              ZkStateReader.NODE_NAME_PROP,
              "127.0.0.1:8983_solr",
              ZkStateReader.SHARD_ID_PROP,
              "shard1",
              ZkStateReader.COLLECTION_PROP,
              COLLECTION,
              ZkStateReader.CORE_NAME_PROP,
              "core1",
              ZkStateReader.STATE_PROP,
              Replica.State.RECOVERING.toString());
      queue.offer(m);
      m =
          new ZkNodeProps(
              Overseer.QUEUE_OPERATION,
              OverseerAction.STATE.toLower(),
              ZkStateReader.NODE_NAME_PROP,
              "node1:8983_",
              ZkStateReader.SHARD_ID_PROP,
              "shard1",
              ZkStateReader.COLLECTION_PROP,
              COLLECTION,
              ZkStateReader.CORE_NAME_PROP,
              "core2",
              ZkStateReader.STATE_PROP,
              Replica.State.RECOVERING.toString());
      queue.offer(m);

      overseerClient = electNewOverseer(server.getZkAddress());

      // submit to proper queue
      queue = getOverseerZero().getStateUpdateQueue();
      m =
          new ZkNodeProps(
              Overseer.QUEUE_OPERATION,
              OverseerAction.STATE.toLower(),
              ZkStateReader.NODE_NAME_PROP,
              "127.0.0.1:8983_solr",
              ZkStateReader.SHARD_ID_PROP,
              "shard1",
              ZkStateReader.COLLECTION_PROP,
              COLLECTION,
              ZkStateReader.CORE_NAME_PROP,
              "core3",
              ZkStateReader.STATE_PROP,
              Replica.State.RECOVERING.toString());
      queue.offer(m);

      reader.waitForState(
          COLLECTION,
          1000,
          TimeUnit.MILLISECONDS,
          (liveNodes, collectionState) ->
              collectionState != null
                  && collectionState.getSlice("shard1") != null
                  && collectionState.getSlice("shard1").getReplicas().size() == 3);

      assertNotNull(reader.getClusterState().getCollection(COLLECTION).getSlice("shard1"));
      assertEquals(
          3,
          reader
              .getClusterState()
              .getCollection(COLLECTION)
              .getSlice("shard1")
              .getReplicasMap()
              .size());
    } finally {
      close(overseerClient);
      close(reader);
    }
  }

  @Test
  public void testExternalClusterStateChangeBehavior() throws Exception {

    ZkStateReader reader = null;
    SolrZkClient overseerClient = null;

    try {

      ZkController.createClusterZkNodes(zkClient);

      reader = new ZkStateReader(zkClient);
      reader.createClusterStateWatchersAndUpdate();

      overseerClient = electNewOverseer(server.getZkAddress());

      ZkDistributedQueue q = getOverseerZero().getStateUpdateQueue();

      createCollection("c1", 1);

      ZkNodeProps m =
          new ZkNodeProps(
              Overseer.QUEUE_OPERATION,
              OverseerAction.STATE.toLower(),
              ZkStateReader.SHARD_ID_PROP,
              "shard1",
              ZkStateReader.NODE_NAME_PROP,
              "127.0.0.1:8983_solr",
              ZkStateReader.COLLECTION_PROP,
              "c1",
              ZkStateReader.CORE_NAME_PROP,
              "core1",
              ZkStateReader.CORE_NODE_NAME_PROP,
              "core_node1",
              ZkStateReader.STATE_PROP,
              Replica.State.DOWN.toString());

      q.offer(m);

      waitForCollections(reader, "c1");
      verifyReplicaStatus(reader, "c1", "shard1", "core_node1", Replica.State.DOWN);

      m =
          new ZkNodeProps(
              Overseer.QUEUE_OPERATION,
              OverseerAction.STATE.toLower(),
              ZkStateReader.SHARD_ID_PROP,
              "shard1",
              ZkStateReader.NODE_NAME_PROP,
              "127.0.0.1:8983_solr",
              ZkStateReader.COLLECTION_PROP,
              "c1",
              ZkStateReader.CORE_NAME_PROP,
              "core1",
              ZkStateReader.STATE_PROP,
              Replica.State.RECOVERING.toString());

      q.offer(m);

      m =
          new ZkNodeProps(
              Overseer.QUEUE_OPERATION,
              OverseerAction.STATE.toLower(),
              ZkStateReader.SHARD_ID_PROP,
              "shard1",
              ZkStateReader.NODE_NAME_PROP,
              "127.0.0.1:8983_solr",
              ZkStateReader.COLLECTION_PROP,
              "c1",
              ZkStateReader.CORE_NAME_PROP,
              "core1",
              ZkStateReader.STATE_PROP,
              Replica.State.ACTIVE.toString());

      q.offer(m);

      final String testCollectionName = "test";
      zkClient.makePath(ZkStateReader.COLLECTIONS_ZKNODE + "/" + testCollectionName, true);
      m =
          new ZkNodeProps(
              Overseer.QUEUE_OPERATION,
              CollectionParams.CollectionAction.CREATE.toLower(),
              "name",
              testCollectionName,
              ZkStateReader.NUM_SHARDS_PROP,
              "1",
              ZkStateReader.REPLICATION_FACTOR,
              "1");
      q.offer(m);

      // Wait for the overseer to create state.json for the collection
      waitForCollections(reader, testCollectionName);

      final String path =
          ZkStateReader.COLLECTIONS_ZKNODE + "/" + testCollectionName + "/state.json";
      byte[] data = zkClient.getData(path, null, null, true);
      // Simulate an external modification of state.json
      zkClient.setData(path, data, true);

      m =
          new ZkNodeProps(
              Overseer.QUEUE_OPERATION,
              CollectionParams.CollectionAction.CREATESHARD.toLower(),
              "collection",
              testCollectionName,
              ZkStateReader.SHARD_ID_PROP,
              "x",
              ZkStateReader.REPLICATION_FACTOR,
              "1");
      q.offer(m);

      m =
          new ZkNodeProps(
              Overseer.QUEUE_OPERATION,
              CollectionParams.CollectionAction.ADDREPLICA.toLower(),
              "collection",
              testCollectionName,
              ZkStateReader.SHARD_ID_PROP,
              "x",
              ZkStateReader.CORE_NODE_NAME_PROP,
              "core_node1",
              ZkStateReader.NODE_NAME_PROP,
              "127.0.0.1:8983_solr",
              ZkStateReader.CORE_NAME_PROP,
              "core1",
              ZkStateReader.STATE_PROP,
              Replica.State.DOWN.toString());
      q.offer(m);

      // Verify replica creation worked ok in spite of external update of state.json (although in
      // theory such updates do not happen unless an old overseer is still updating ZK after a new
      // Overseer got elected...).
      verifyReplicaStatus(reader, testCollectionName, "x", "core_node1", Replica.State.DOWN);

      waitForCollections(reader, "c1");
      verifyReplicaStatus(reader, "c1", "shard1", "core_node1", Replica.State.ACTIVE);

    } finally {
      close(overseerClient);
      close(reader);
    }
  }

  private void close(ZkStateReader reader) {
    if (reader != null) {
      reader.close();
    }
  }

  private void close(SolrZkClient client) {
    if (client != null) {
      client.close();
    }
  }

  private SolrZkClient electNewOverseer(String address)
      throws InterruptedException,
          KeeperException,
          NoSuchFieldException,
          SecurityException,
          IllegalAccessException {
    SolrZkClient zkClient =
        new SolrZkClient.Builder()
            .withUrl(address)
            .withTimeout(TIMEOUT, TimeUnit.MILLISECONDS)
            .build();
    zkClients.add(zkClient);
    ZkStateReader reader = new ZkStateReader(zkClient);
    readers.add(reader);
    LeaderElector overseerElector = new LeaderElector(zkClient);
    if (overseers.size() > 0) {
      overseers.get(0).close();
      overseers.get(0).getZkStateReader().getZkClient().close();
    }
    UpdateShardHandler updateShardHandler =
        new UpdateShardHandler(UpdateShardHandlerConfig.DEFAULT);
    updateShardHandlers.add(updateShardHandler);
    HttpShardHandlerFactory httpShardHandlerFactory = new HttpShardHandlerFactory();
    httpShardHandlerFactory.init(new PluginInfo("shardHandlerFactory", Collections.emptyMap()));
    httpShardHandlerFactorys.add(httpShardHandlerFactory);

    ZkController zkController = createMockZkController(address, null, reader);
    zkControllers.add(zkController);
    // Create an Overseer with associated configuration to NOT USE distributed state update. Tests
    // in this class really test the Overseer.
    Overseer overseer =
        new Overseer(
            (HttpShardHandler) httpShardHandlerFactory.getShardHandler(),
            updateShardHandler,
            "/admin/cores",
            reader,
            zkController,
            new CloudConfig.CloudConfigBuilder("127.0.0.1", 8983)
                .setUseDistributedClusterStateUpdates(false)
                .build());
    overseers.add(overseer);
    ElectionContext ec = new OverseerElectionContext(zkClient, overseer, address.replace("/", "_"));
    overseerElector.setup(ec);
    overseerElector.joinElection(ec, false);
    return zkClient;
  }

  private ZkController createMockZkController(
      String zkAddress, SolrZkClient zkClient, ZkStateReader reader)
      throws InterruptedException,
          NoSuchFieldException,
          SecurityException,
          SessionExpiredException,
          IllegalAccessException {
    ZkController zkController = mock(ZkController.class);

    if (zkClient == null) {
      SolrZkClient newZkClient =
          new SolrZkClient.Builder()
              .withUrl(server.getZkAddress())
              .withTimeout(AbstractZkTestCase.TIMEOUT, TimeUnit.MILLISECONDS)
              .build();
      doAnswer(
              new Answer<Void>() {
                @Override
                public Void answer(InvocationOnMock invocation) {
                  newZkClient.close();
                  return null;
                }
              })
          .when(zkController)
          .close();
      zkClient = newZkClient;
    } else {
      doNothing().when(zkController).close();
    }

    CoreContainer mockAlwaysUpCoreContainer =
        mock(CoreContainer.class, Mockito.withSettings().defaultAnswer(Mockito.CALLS_REAL_METHODS));
    SolrMetricManager mockMetricManager = mock(SolrMetricManager.class);
    when(mockAlwaysUpCoreContainer.getMetricManager()).thenReturn(mockMetricManager);
    when(mockAlwaysUpCoreContainer.isShutDown())
        .thenReturn(testDone); // Allow retry on session expiry
    when(mockAlwaysUpCoreContainer.getResourceLoader())
        .thenReturn(new SolrResourceLoader(createTempDir()));
    ClusterSingletons singletons = new ClusterSingletons(() -> true, r -> r.run());
    // don't wait for all singletons
    singletons.setReady();
    final MemberAccessor accessor = Plugins.getMemberAccessor();
    accessor.set(
        CoreContainer.class.getDeclaredField("clusterSingletons"),
        mockAlwaysUpCoreContainer,
        singletons);
    accessor.set(ZkController.class.getDeclaredField("zkClient"), zkController, zkClient);
    accessor.set(
        ZkController.class.getDeclaredField("cc"), zkController, mockAlwaysUpCoreContainer);
    when(zkController.getCoreContainer()).thenReturn(mockAlwaysUpCoreContainer);
    when(zkController.getZkClient()).thenReturn(zkClient);
    when(zkController.getZkStateReader()).thenReturn(reader);
    // primitive support for CC.runAsync
    doAnswer(
            invocable -> {
              Runnable r = invocable.getArgument(0);
              Thread t = new Thread(r);
              t.start();
              return null;
            })
        .when(mockAlwaysUpCoreContainer)
        .runAsync(any(Runnable.class));

    when(zkController.getLeaderProps(anyString(), anyString(), anyInt())).thenCallRealMethod();
    when(zkController.getLeaderProps(anyString(), anyString(), anyInt(), anyBoolean()))
        .thenCallRealMethod();
    doReturn(getCloudDataProvider(reader)).when(zkController).getSolrCloudManager();
    return zkController;
  }

  private SolrCloudManager getCloudDataProvider(ZkStateReader zkStateReader) {
    var httpSolrClient =
        new Http2SolrClient.Builder()
            .withIdleTimeout(30000, TimeUnit.MILLISECONDS)
            .withConnectionTimeout(15000, TimeUnit.MILLISECONDS)
            .build();
    var cloudSolrClient =
        new CloudHttp2SolrClient.Builder(new ZkClientClusterStateProvider(zkStateReader))
            .withHttpClient(httpSolrClient)
            .build();
    solrClients.add(cloudSolrClient);
    solrClients.add(httpSolrClient);
    SolrClientCloudManager sccm = new SolrClientCloudManager(cloudSolrClient, null);
    sccm.getClusterStateProvider().connect();
    return sccm;
  }

  @Test
  public void testRemovalOfLastReplica() throws Exception {

    final Integer numReplicas = 1 + random().nextInt(4); // between 1 and 4 replicas
    final Integer numShards = 1 + random().nextInt(4); // between 1 and 4 shards

    ZkStateReader zkStateReader = null;
    SolrZkClient overseerClient = null;
    try {

      ZkController.createClusterZkNodes(zkClient);

      zkStateReader = new ZkStateReader(zkClient);
      zkStateReader.createClusterStateWatchersAndUpdate();

      overseerClient = electNewOverseer(server.getZkAddress());

      ZkDistributedQueue q = getOverseerZero().getStateUpdateQueue();

      // create collection
      {
        zkClient.makePath(ZkStateReader.COLLECTIONS_ZKNODE + "/" + COLLECTION, true);
        ZkNodeProps m =
            new ZkNodeProps(
                Overseer.QUEUE_OPERATION,
                CollectionParams.CollectionAction.CREATE.toLower(),
                "name",
                COLLECTION,
                ZkStateReader.NUM_SHARDS_PROP,
                numShards.toString(),
                ZkStateReader.REPLICATION_FACTOR,
                "1");
        q.offer(m);
      }
      waitForCollections(zkStateReader, COLLECTION);

      // create nodes with state recovering
      for (int rr = 1; rr <= numReplicas; ++rr) {
        for (int ss = 1; ss <= numShards; ++ss) {
          final int N = (numReplicas - rr) * numShards + ss;
          ZkNodeProps m =
              new ZkNodeProps(
                  Overseer.QUEUE_OPERATION,
                  OverseerAction.STATE.toLower(),
                  ZkStateReader.SHARD_ID_PROP,
                  "shard" + ss,
                  ZkStateReader.NODE_NAME_PROP,
                  "127.0.0.1:8983_solr",
                  ZkStateReader.COLLECTION_PROP,
                  COLLECTION,
                  ZkStateReader.CORE_NAME_PROP,
                  "core" + N,
                  ZkStateReader.CORE_NODE_NAME_PROP,
                  "core_node" + N,
                  ZkStateReader.STATE_PROP,
                  Replica.State.RECOVERING.toString());

          q.offer(m);
        }
      }
      // verify recovering
      for (int rr = 1; rr <= numReplicas; ++rr) {
        for (int ss = 1; ss <= numShards; ++ss) {
          final int N = (numReplicas - rr) * numShards + ss;
          verifyReplicaStatus(
              zkStateReader, COLLECTION, "shard" + ss, "core_node" + N, Replica.State.RECOVERING);
        }
      }

      // publish node states (active)
      for (int rr = 1; rr <= numReplicas; ++rr) {
        for (int ss = 1; ss <= numShards; ++ss) {
          final int N = (numReplicas - rr) * numShards + ss;
          ZkNodeProps m =
              new ZkNodeProps(
                  Overseer.QUEUE_OPERATION,
                  OverseerAction.STATE.toLower(),
                  ZkStateReader.SHARD_ID_PROP,
                  "shard" + ss,
                  ZkStateReader.NODE_NAME_PROP,
                  "127.0.0.1:8983_solr",
                  ZkStateReader.COLLECTION_PROP,
                  COLLECTION,
                  ZkStateReader.CORE_NAME_PROP,
                  "core" + N,
                  ZkStateReader.STATE_PROP,
                  Replica.State.ACTIVE.toString());

          q.offer(m);
        }
      }
      // verify active
      for (int rr = 1; rr <= numReplicas; ++rr) {
        for (int ss = 1; ss <= numShards; ++ss) {
          final int N = (numReplicas - rr) * numShards + ss;
          verifyReplicaStatus(
              zkStateReader, COLLECTION, "shard" + ss, "core_node" + N, Replica.State.ACTIVE);
        }
      }

      // delete node
      for (int rr = 1; rr <= numReplicas; ++rr) {
        for (int ss = 1; ss <= numShards; ++ss) {
          final int N = (numReplicas - rr) * numShards + ss;
          ZkNodeProps m =
              new ZkNodeProps(
                  Overseer.QUEUE_OPERATION,
                  OverseerAction.DELETECORE.toLower(),
                  ZkStateReader.COLLECTION_PROP,
                  COLLECTION,
                  ZkStateReader.CORE_NODE_NAME_PROP,
                  "core_node" + N);

          q.offer(m);

          {
            String shard = "shard" + ss;
            zkStateReader.waitForState(
                COLLECTION,
                15000,
                TimeUnit.MILLISECONDS,
                (liveNodes, collectionState) ->
                    collectionState != null
                        && (collectionState.getSlice(shard) == null
                            || collectionState.getSlice(shard).getReplicasMap().get("core_node" + N)
                                == null));
          }

          final DocCollection docCollection =
              zkStateReader.getClusterState().getCollection(COLLECTION);
          assertTrue("found no " + COLLECTION, (null != docCollection));

          final Slice slice = docCollection.getSlice("shard" + ss);
          assertTrue(
              "found no "
                  + COLLECTION
                  + " shard"
                  + ss
                  + " slice after removal of replica "
                  + rr
                  + " of "
                  + numReplicas,
              (null != slice));

          final Collection<Replica> replicas = slice.getReplicas();
          assertEquals(
              "wrong number of "
                  + COLLECTION
                  + " shard"
                  + ss
                  + " replicas left, replicas="
                  + replicas,
              numReplicas - rr,
              replicas.size());
        }
      }

    } finally {

      close(overseerClient);
      close(zkStateReader);
    }
  }

  @Test
  public void testLatchWatcher() throws InterruptedException {
    OverseerTaskQueue.LatchWatcher latch1 = new OverseerTaskQueue.LatchWatcher();
    long before = System.nanoTime();
    latch1.await(100);
    long after = System.nanoTime();
    assertTrue(TimeUnit.NANOSECONDS.toMillis(after - before) > 50);
    // Mostly to make sure the millis->nanos->millis is not broken
    assertTrue(TimeUnit.NANOSECONDS.toMillis(after - before) < 500);
    latch1.process(new WatchedEvent(new WatcherEvent(1, 1, "/foo/bar"), NO_ZXID));
    before = System.nanoTime();
    latch1.await(10000); // Expecting no wait
    after = System.nanoTime();
    assertTrue(TimeUnit.NANOSECONDS.toMillis(after - before) < 1000);

    final AtomicBoolean expectedEventProcessed = new AtomicBoolean(false);
    final AtomicBoolean doneWaiting = new AtomicBoolean(false);
    final OverseerTaskQueue.LatchWatcher latch2 =
        new OverseerTaskQueue.LatchWatcher(Event.EventType.NodeCreated);
    Thread t =
        new Thread(
            () -> {
              // Process an event of a different type first, this shouldn't release the latch
              latch2.process(
                  new WatchedEvent(
                      new WatcherEvent(Event.EventType.NodeDeleted.getIntValue(), 1, "/foo/bar"),
                      NO_ZXID));

              assertFalse("Latch shouldn't have been released", doneWaiting.get());
              // Now process the correct type of event
              expectedEventProcessed.set(true);
              latch2.process(
                  new WatchedEvent(
                      new WatcherEvent(Event.EventType.NodeCreated.getIntValue(), 1, "/foo/bar"),
                      NO_ZXID));
            });
    t.start();
    before = System.nanoTime();
    latch2.await(10000); // It shouldn't wait this long, t should notify the lock
    after = System.nanoTime();
    doneWaiting.set(true);
    assertTrue(expectedEventProcessed.get());
    assertTrue(TimeUnit.NANOSECONDS.toMillis(after - before) < 1000);
  }
}
