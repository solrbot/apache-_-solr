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

import static org.apache.solr.common.cloud.ZkStateReader.COLLECTION_PROP;
import static org.apache.solr.common.cloud.ZkStateReader.CORE_NAME_PROP;
import static org.apache.solr.common.cloud.ZkStateReader.CORE_NODE_NAME_PROP;
import static org.apache.solr.common.cloud.ZkStateReader.REJOIN_AT_HEAD_PROP;
import static org.apache.solr.common.cloud.ZkStateReader.UNSUPPORTED_SOLR_XML;
import static org.apache.solr.common.params.CollectionParams.CollectionAction.ADDROLE;
import static org.apache.zookeeper.ZooDefs.Ids.OPEN_ACL_UNSAFE;

import java.io.Closeable;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.apache.curator.framework.api.ACLProvider;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.cloud.SolrCloudManager;
import org.apache.solr.client.solrj.impl.CloudHttp2SolrClient;
import org.apache.solr.client.solrj.impl.CloudSolrClient;
import org.apache.solr.client.solrj.impl.Http2SolrClient;
import org.apache.solr.client.solrj.impl.HttpSolrClient.Builder;
import org.apache.solr.client.solrj.impl.SolrClientCloudManager;
import org.apache.solr.client.solrj.impl.SolrZkClientTimeout;
import org.apache.solr.client.solrj.impl.ZkClientClusterStateProvider;
import org.apache.solr.client.solrj.request.CoreAdminRequest.WaitForState;
import org.apache.solr.cloud.overseer.ClusterStateMutator;
import org.apache.solr.cloud.overseer.OverseerAction;
import org.apache.solr.cloud.overseer.SliceMutator;
import org.apache.solr.common.AlreadyClosedException;
import org.apache.solr.common.MapWriter;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrException.ErrorCode;
import org.apache.solr.common.cloud.ClusterState;
import org.apache.solr.common.cloud.DefaultZkACLProvider;
import org.apache.solr.common.cloud.DefaultZkCredentialsInjector;
import org.apache.solr.common.cloud.DefaultZkCredentialsProvider;
import org.apache.solr.common.cloud.DocCollection;
import org.apache.solr.common.cloud.DocCollectionWatcher;
import org.apache.solr.common.cloud.LiveNodesListener;
import org.apache.solr.common.cloud.NodesSysPropsCacher;
import org.apache.solr.common.cloud.OnDisconnect;
import org.apache.solr.common.cloud.OnReconnect;
import org.apache.solr.common.cloud.PerReplicaStates;
import org.apache.solr.common.cloud.PerReplicaStatesOps;
import org.apache.solr.common.cloud.Replica;
import org.apache.solr.common.cloud.SecurityAwareZkACLProvider;
import org.apache.solr.common.cloud.Slice;
import org.apache.solr.common.cloud.SolrZkClient;
import org.apache.solr.common.cloud.ZkACLProvider;
import org.apache.solr.common.cloud.ZkCoreNodeProps;
import org.apache.solr.common.cloud.ZkCredentialsInjector;
import org.apache.solr.common.cloud.ZkCredentialsProvider;
import org.apache.solr.common.cloud.ZkMaintenanceUtils;
import org.apache.solr.common.cloud.ZkNodeProps;
import org.apache.solr.common.cloud.ZkStateReader;
import org.apache.solr.common.cloud.ZooKeeperException;
import org.apache.solr.common.params.CollectionAdminParams;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.Compressor;
import org.apache.solr.common.util.ExecutorUtil;
import org.apache.solr.common.util.IOUtils;
import org.apache.solr.common.util.ObjectReleaseTracker;
import org.apache.solr.common.util.SolrNamedThreadFactory;
import org.apache.solr.common.util.StrUtils;
import org.apache.solr.common.util.URLUtil;
import org.apache.solr.common.util.Utils;
import org.apache.solr.common.util.ZLibCompressor;
import org.apache.solr.core.CloseHook;
import org.apache.solr.core.CloudConfig;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.core.CoreDescriptor;
import org.apache.solr.core.NodeRoles;
import org.apache.solr.core.SolrCore;
import org.apache.solr.core.SolrCoreInitializationException;
import org.apache.solr.handler.component.HttpShardHandler;
import org.apache.solr.handler.component.HttpShardHandlerFactory;
import org.apache.solr.logging.MDCLoggingContext;
import org.apache.solr.search.SolrIndexSearcher;
import org.apache.solr.update.UpdateLog;
import org.apache.solr.util.AddressUtils;
import org.apache.solr.util.RTimer;
import org.apache.solr.util.RefCounted;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.NoNodeException;
import org.apache.zookeeper.KeeperException.SessionExpiredException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handle ZooKeeper interactions.
 *
 * <p>notes: loads everything on init, creates what's not there - further updates are prompted with
 * Watches.
 *
 * <p>TODO: exceptions during close on attempts to update cloud state
 */
public class ZkController implements Closeable {

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  static final int WAIT_DOWN_STATES_TIMEOUT_SECONDS = 60;

  private final boolean SKIP_AUTO_RECOVERY = Boolean.getBoolean("solrcloud.skip.autorecovery");

  private final ZkDistributedQueue overseerJobQueue;
  private final OverseerTaskQueue overseerCollectionQueue;
  private final OverseerTaskQueue overseerConfigSetQueue;

  private final DistributedMap overseerRunningMap;
  private final DistributedMap overseerCompletedMap;
  private final DistributedMap overseerFailureMap;
  private final DistributedMap asyncIdsMap;

  public static final String COLLECTION_PARAM_PREFIX = "collection.";
  public static final String CONFIGNAME_PROP = "configName";

  public static final byte[] TOUCHED_ZNODE_DATA = "{}".getBytes(StandardCharsets.UTF_8);

  static class ContextKey {

    private final String collection;
    private final String coreNodeName;

    public ContextKey(String collection, String coreNodeName) {
      this.collection = collection;
      this.coreNodeName = coreNodeName;
    }

    @Override
    public int hashCode() {
      final int prime = 31;
      int result = 1;
      result = prime * result + ((collection == null) ? 0 : collection.hashCode());
      result = prime * result + ((coreNodeName == null) ? 0 : coreNodeName.hashCode());
      return result;
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj) return true;
      if (!(obj instanceof ContextKey other)) return false;
      return Objects.equals(collection, other.collection)
          && Objects.equals(coreNodeName, other.coreNodeName);
    }

    @Override
    public String toString() {
      return collection + ':' + coreNodeName;
    }
  }

  private final Map<ContextKey, ElectionContext> electionContexts =
      Collections.synchronizedMap(new HashMap<>());

  private final SolrZkClient zkClient;
  public final ZkStateReader zkStateReader;
  private SolrCloudManager cloudManager;

  private CloudHttp2SolrClient cloudSolrClient;

  private final ExecutorService zkConnectionListenerCallbackExecutor =
      ExecutorUtil.newMDCAwareSingleThreadExecutor(
          new SolrNamedThreadFactory("zkConnectionListenerCallback"));
  private final OnReconnect onReconnect = this::onReconnect;
  private final OnDisconnect onDisconnect = this::onDisconnect;

  private final String zkServerAddress; // example: 127.0.0.1:54062/solr

  private final int localHostPort; // example: 54065
  private final String hostName; // example: 127.0.0.1
  private final String nodeName; // example: 127.0.0.1:54065_solr
  private String baseURL; // example: http://127.0.0.1:54065/solr

  private final CloudConfig cloudConfig;
  private final NodesSysPropsCacher sysPropsCacher;

  private final DistributedClusterStateUpdater distributedClusterStateUpdater;

  private LeaderElector overseerElector;

  private Map<String, ReplicateFromLeader> replicateFromLeaders = new ConcurrentHashMap<>();
  private final Map<String, ZkCollectionTerms> collectionToTerms = new HashMap<>();

  // for now, this can be null in tests, in which case recovery will be inactive, and other features
  // may accept defaults or use mocks rather than pulling things from a CoreContainer
  private CoreContainer cc;

  protected volatile Overseer overseer;

  private int leaderVoteWait;
  private int leaderConflictResolveWait;

  private boolean genericCoreNodeNames;

  private int clientTimeout;

  private volatile boolean isClosed;

  private final ConcurrentHashMap<String, Throwable> replicasMetTragicEvent =
      new ConcurrentHashMap<>();

  @Deprecated
  // keeps track of replicas that have been asked to recover by leaders running on this node
  private final Map<String, String> replicasInLeaderInitiatedRecovery = new HashMap<>();

  // This is an expert and unsupported development mode that does not create
  // an Overseer or register a /live node. This let's you monitor the cluster
  // and interact with zookeeper via the Solr admin UI on a node outside the cluster,
  // and so one that will not be killed or stopped when testing. See developer cloud-scripts.
  private boolean zkRunOnly = Boolean.getBoolean("zkRunOnly"); // expert

  // keeps track of a list of objects that need to know a new ZooKeeper session was created after
  // expiration occurred ref is held as a HashSet since we clone the set before notifying to avoid
  // synchronizing too long
  private final HashSet<OnReconnect> reconnectListeners = new HashSet<>();

  private class RegisterCoreAsync implements Callable<Object> {

    CoreDescriptor descriptor;
    boolean recoverReloadedCores;
    boolean afterExpiration;

    RegisterCoreAsync(
        CoreDescriptor descriptor, boolean recoverReloadedCores, boolean afterExpiration) {
      this.descriptor = descriptor;
      this.recoverReloadedCores = recoverReloadedCores;
      this.afterExpiration = afterExpiration;
    }

    @Override
    public Object call() throws Exception {
      if (log.isInfoEnabled()) {
        log.info("Registering core {} afterExpiration? {}", descriptor.getName(), afterExpiration);
      }
      register(descriptor.getName(), descriptor, recoverReloadedCores, afterExpiration, false);
      return descriptor;
    }
  }

  /**
   * @param cc Core container associated with this controller. cannot be null.
   * @param zkServerAddress where to connect to the zk server
   * @param zkClientConnectTimeout timeout in ms
   * @param cloudConfig configuration for this controller. TODO: possibly redundant with
   *     CoreContainer
   */
  public ZkController(
      final CoreContainer cc,
      String zkServerAddress,
      int zkClientConnectTimeout,
      CloudConfig cloudConfig)
      throws InterruptedException, TimeoutException, IOException {

    if (cc == null) throw new IllegalArgumentException("CoreContainer cannot be null.");
    this.cc = cc;

    this.cloudConfig = cloudConfig;

    // Use the configured way to do cluster state update (Overseer queue vs distributed)
    distributedClusterStateUpdater =
        new DistributedClusterStateUpdater(cloudConfig.getDistributedClusterStateUpdates());

    this.genericCoreNodeNames = cloudConfig.getGenericCoreNodeNames();

    this.zkServerAddress = zkServerAddress;
    this.localHostPort = cloudConfig.getSolrHostPort();
    this.hostName = normalizeHostName(cloudConfig.getHost());
    this.nodeName = generateNodeName(this.hostName, Integer.toString(this.localHostPort));
    MDCLoggingContext.setNode(nodeName);
    this.leaderVoteWait = cloudConfig.getLeaderVoteWait();
    this.leaderConflictResolveWait = cloudConfig.getLeaderConflictResolveWait();

    String zkCredentialsInjectorClass = cloudConfig.getZkCredentialsInjectorClass();
    ZkCredentialsInjector zkCredentialsInjector =
        StrUtils.isNullOrEmpty(zkCredentialsInjectorClass)
            ? new DefaultZkCredentialsInjector()
            : cc.getResourceLoader()
                .newInstance(zkCredentialsInjectorClass, ZkCredentialsInjector.class);

    this.clientTimeout = cloudConfig.getZkClientTimeout();

    String zkACLProviderClass = cloudConfig.getZkACLProviderClass();
    ZkACLProvider zkACLProvider =
        StrUtils.isNullOrEmpty(zkACLProviderClass)
            ? new DefaultZkACLProvider()
            : cc.getResourceLoader().newInstance(zkACLProviderClass, ZkACLProvider.class);
    zkACLProvider.setZkCredentialsInjector(zkCredentialsInjector);

    String zkCredentialsProviderClass = cloudConfig.getZkCredentialsProviderClass();
    ZkCredentialsProvider zkCredentialsProvider =
        StrUtils.isNullOrEmpty(zkCredentialsProviderClass)
            ? new DefaultZkCredentialsProvider()
            : cc.getResourceLoader()
                .newInstance(zkCredentialsProviderClass, ZkCredentialsProvider.class);

    zkCredentialsProvider.setZkCredentialsInjector(zkCredentialsInjector);
    addOnReconnectListener(getConfigDirListener());

    String stateCompressionProviderClass = cloudConfig.getStateCompressorClass();
    Compressor compressor =
        StrUtils.isNullOrEmpty(stateCompressionProviderClass)
            ? new ZLibCompressor()
            : cc.getResourceLoader().newInstance(stateCompressionProviderClass, Compressor.class);

    zkClient =
        new SolrZkClient.Builder()
            .withUrl(zkServerAddress)
            .withTimeout(clientTimeout, TimeUnit.MILLISECONDS)
            .withConnTimeOut(zkClientConnectTimeout, TimeUnit.MILLISECONDS)
            .withAclProvider(zkACLProvider)
            .withClosedCheck(cc::isShutDown)
            .withCompressor(compressor)
            .build();

    zkClient
        .getCuratorFramework()
        .getConnectionStateListenable()
        .addListener(onReconnect, zkConnectionListenerCallbackExecutor);
    zkClient
        .getCuratorFramework()
        .getConnectionStateListenable()
        .addListener(onDisconnect, zkConnectionListenerCallbackExecutor);
    // Refuse to start if ZK has a non empty /clusterstate.json or a /solr.xml file
    checkNoOldClusterstate(zkClient);

    this.overseerRunningMap = Overseer.getRunningMap(zkClient);
    this.overseerCompletedMap = Overseer.getCompletedMap(zkClient);
    this.overseerFailureMap = Overseer.getFailureMap(zkClient);
    this.asyncIdsMap = Overseer.getAsyncIdsMap(zkClient);

    zkStateReader =
        new ZkStateReader(
            zkClient,
            () -> {
              if (cc != null) cc.securityNodeChanged();
            });

    init();

    if (distributedClusterStateUpdater.isDistributedStateUpdate()) {
      this.overseerJobQueue = null;
    } else {
      this.overseerJobQueue = overseer.getStateUpdateQueue();
    }
    this.overseerCollectionQueue = overseer.getCollectionQueue(zkClient);
    this.overseerConfigSetQueue = overseer.getConfigSetQueue(zkClient);
    final var client =
        (Http2SolrClient)
            ((HttpShardHandlerFactory) getCoreContainer().getShardHandlerFactory()).getClient();
    this.sysPropsCacher = new NodesSysPropsCacher(client, zkStateReader);
    assert ObjectReleaseTracker.track(this);
  }

  private void onDisconnect(boolean sessionExpired) {
    try {
      overseer.close();
    } catch (Exception e) {
      log.error("Error trying to stop any Overseer threads", e);
    }

    // Close outstanding leader elections
    List<CoreDescriptor> descriptors = cc.getCoreDescriptors();
    for (CoreDescriptor descriptor : descriptors) {
      closeExistingElectionContext(descriptor, sessionExpired);
    }

    // Mark all cores as not leader
    for (CoreDescriptor descriptor : descriptors) {
      descriptor.getCloudDescriptor().setLeader(false);
      descriptor.getCloudDescriptor().setHasRegistered(false);
    }
  }

  private void onReconnect() {
    // on reconnect, reload cloud info
    log.info("ZooKeeper session re-connected ... refreshing core states after session expiration.");
    clearZkCollectionTerms();
    try {
      // Remove the live node in case it is still there
      removeEphemeralLiveNode();
      // recreate our watchers first so that they exist even on any problems below
      zkStateReader.createClusterStateWatchersAndUpdate();

      // this is troublesome - we don't want to kill anything the old
      // leader accepted
      // though I guess sync will likely get those updates back? But
      // only if
      // he is involved in the sync, and he certainly may not be
      // ExecutorUtil.shutdownAndAwaitTermination(cc.getCmdDistribExecutor());
      // we need to create all of our lost watches

      // seems we don't need to do this again...
      // Overseer.createClientNodes(zkClient, getNodeName());

      // start the overseer first as following code may need it's processing
      if (!zkRunOnly) {
        ElectionContext context = new OverseerElectionContext(zkClient, overseer, getNodeName());

        ElectionContext prevContext = overseerElector.getContext();
        if (prevContext != null) {
          prevContext.cancelElection();
          prevContext.close();
        }

        overseerElector.setup(context);

        if (cc.nodeRoles.isOverseerAllowedOrPreferred()) {
          overseerElector.joinElection(context, true);
        }
      }

      cc.cancelCoreRecoveries();

      try {
        registerAllCoresAsDown(false);
      } catch (SessionExpiredException e) {
        // zk has to reconnect and this will all be tried again
        throw e;
      } catch (Exception e) {
        // this is really best effort - in case of races or failure cases where we now
        // need to be the leader, if anything fails, just continue
        log.warn("Exception while trying to register all cores as DOWN", e);
      }

      // we have to register as live first to pick up docs in the buffer
      createEphemeralLiveNode();

      List<CoreDescriptor> descriptors = cc.getCoreDescriptors();
      // re register all descriptors
      ExecutorService executorService = (cc != null) ? cc.getCoreZkRegisterExecutorService() : null;
      for (CoreDescriptor descriptor : descriptors) {
        // TODO: we need to think carefully about what happens when it was a leader
        // that was expired - as well as what to do about leaders/overseers with
        // connection loss
        try {
          // unload solr cores that have been 'failed over'
          throwErrorIfReplicaReplaced(descriptor);

          if (executorService != null) {
            executorService.submit(new RegisterCoreAsync(descriptor, true, true));
          } else {
            register(descriptor.getName(), descriptor, true, true, false);
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw e;
        } catch (Exception e) {
          log.error("Error registering SolrCore", e);
        }
      }

      // notify any other objects that need to know when the session was re-connected
      HashSet<OnReconnect> clonedListeners;
      synchronized (reconnectListeners) {
        clonedListeners = new HashSet<>(reconnectListeners);
      }
      // the OnReconnect operation can be expensive per listener, so do that async in
      // the background
      for (OnReconnect listener : clonedListeners) {
        try {
          if (executorService != null) {
            executorService.execute(
                () -> {
                  try {
                    listener.onReconnect();
                  } catch (Throwable exc) {
                    // not much we can do here other than warn in the log
                    log.warn(
                        "Error when notifying OnReconnect listener {} after session re-connected.",
                        listener,
                        exc);
                  }
                });
          } else {
            listener.onReconnect();
          }
        } catch (Throwable exc) {
          // not much we can do here other than warn in the log
          log.warn(
              "Error when notifying OnReconnect listener {} after session re-connected.",
              listener,
              exc);
        }
      }
    } catch (InterruptedException e) {
      // Restore the interrupted status
      Thread.currentThread().interrupt();
      // This means that we are closing down and the executor is shut down.
      // There is no need to throw an additional error here, as the executor will
      // not accept further listener commands.
    } catch (Exception e) {
      log.error("Exception during reconnect", e);
      throw new ZooKeeperException(ErrorCode.SERVER_ERROR, "", e);
    }
  }

  /**
   * Verifies if /clusterstate.json exists in Zookeepeer, and if it does and is not empty, refuses
   * to start and outputs a helpful message regarding collection migration. Also aborts if /solr.xml
   * exists in zookeeper.
   *
   * <p>If /clusterstate.json exists and is empty, it is removed.
   */
  private void checkNoOldClusterstate(final SolrZkClient zkClient) throws InterruptedException {
    try {
      if (zkClient.exists(UNSUPPORTED_SOLR_XML, true)) {
        String message =
            "solr.xml found in ZooKeeper. Loading solr.xml from ZooKeeper is no longer supported since Solr 10. "
                + "Cannot start Solr. The file can be removed with command bin/solr zk rm /solr.xml -z host:port";
        log.error(message);
        throw new SolrException(ErrorCode.INVALID_STATE, message);
      }
      if (!zkClient.exists(ZkStateReader.UNSUPPORTED_CLUSTER_STATE, true)) {
        return;
      }
      final byte[] data =
          zkClient.getData(ZkStateReader.UNSUPPORTED_CLUSTER_STATE, null, null, true);

      if (Arrays.equals("{}".getBytes(StandardCharsets.UTF_8), data)) {
        // Empty json. This log will only occur once.
        log.warn(
            "{} no longer supported starting with Solr 9. Found empty file on Zookeeper, deleting it.",
            ZkStateReader.UNSUPPORTED_CLUSTER_STATE);
        zkClient.delete(ZkStateReader.UNSUPPORTED_CLUSTER_STATE, -1, true);
      } else {
        // /clusterstate.json not empty: refuse to start but do not automatically delete. A bit of a
        // pain but user shouldn't have older collections at this stage anyway.
        String message =
            ZkStateReader.UNSUPPORTED_CLUSTER_STATE
                + " no longer supported starting with Solr 9. "
                + "It is present and not empty. Cannot start Solr. Please first migrate collections to stateFormat=2 using an "
                + "older version of Solr or if you don't care about the data then delete the file from "
                + "Zookeeper using a command line tool, for example: bin/solr zk rm /clusterstate.json -z host:port";
        log.error(message);
        throw new SolrException(SolrException.ErrorCode.INVALID_STATE, message);
      }
    } catch (KeeperException.NoNodeException e) {
      // N instances starting at the same time could attempt to delete the file, resulting in N-1
      // NoNodeExceptions.
      // If we get to this point, then it's OK to suppress the exception and continue assuming
      // success.
      log.debug(
          "NoNodeException attempting to delete {}. Another instance must have deleted it already",
          ZkStateReader.UNSUPPORTED_CLUSTER_STATE,
          e);
    } catch (KeeperException e) {
      // Convert checked exception to one acceptable by the caller (see also init() further down)
      log.error("", e);
      throw new ZooKeeperException(SolrException.ErrorCode.SERVER_ERROR, "", e);
    }
  }

  public CloudSolrClient getSolrClient() {
    return getSolrCloudManager().getSolrClient();
  }

  public int getLeaderVoteWait() {
    return leaderVoteWait;
  }

  public int getLeaderConflictResolveWait() {
    return leaderConflictResolveWait;
  }

  private void registerAllCoresAsDown(boolean updateLastPublished) throws SessionExpiredException {
    List<CoreDescriptor> descriptors = cc.getCoreDescriptors();
    if (isClosed) return;

    // before registering as live, make sure everyone is in a
    // down state
    publishNodeAsDown(getNodeName());
    for (CoreDescriptor descriptor : descriptors) {
      // if it looks like we are going to be the leader, we don't
      // want to wait for the following stuff
      CloudDescriptor cloudDesc = descriptor.getCloudDescriptor();
      String collection = cloudDesc.getCollectionName();
      String slice = cloudDesc.getShardId();
      try {

        int children =
            zkStateReader
                .getZkClient()
                .getChildren(
                    ZkStateReader.COLLECTIONS_ZKNODE
                        + "/"
                        + collection
                        + "/leader_elect/"
                        + slice
                        + "/election",
                    null,
                    true)
                .size();
        if (children == 0) {
          log.debug(
              "looks like we are going to be the leader for collection {} shard {}",
              collection,
              slice);
          continue;
        }

      } catch (NoNodeException e) {
        log.debug(
            "looks like we are going to be the leader for collection {} shard {}",
            collection,
            slice);
        continue;
      } catch (InterruptedException e2) {
        Thread.currentThread().interrupt();
      } catch (SessionExpiredException e) {
        // zk has to reconnect
        throw e;
      } catch (KeeperException e) {
        log.warn("", e);
        Thread.currentThread().interrupt();
      }

      final String coreZkNodeName = descriptor.getCloudDescriptor().getCoreNodeName();
      try {
        log.debug(
            "calling waitForLeaderToSeeDownState for coreZkNodeName={} collection={} shard={}",
            coreZkNodeName,
            collection,
            slice);
        waitForLeaderToSeeDownState(descriptor, coreZkNodeName);
      } catch (Exception e) {
        log.warn(
            "There was a problem while making a best effort to ensure the leader has seen us as down, this is not unexpected as Zookeeper has just reconnected after a session expiration",
            e);
        if (isClosed) {
          return;
        }
      }
    }
  }

  public NodesSysPropsCacher getSysPropsCacher() {
    return sysPropsCacher;
  }

  private ContextKey closeExistingElectionContext(CoreDescriptor cd, boolean sessionExpired) {
    // look for old context - if we find it, cancel it
    String collection = cd.getCloudDescriptor().getCollectionName();
    final String coreNodeName = cd.getCloudDescriptor().getCoreNodeName();

    ContextKey contextKey = new ContextKey(collection, coreNodeName);
    ElectionContext prevContext = electionContexts.get(contextKey);

    if (prevContext != null) {
      prevContext.close();
      // Only remove the election contexts if the session expired, otherwise the ephemeral nodes
      // will still exist
      if (sessionExpired) {
        electionContexts.remove(contextKey);
      }
    }

    return contextKey;
  }

  public void preClose() {
    this.isClosed = true;
    try {
      // We do not want to react to connection state changes after we have started to close
      zkClient.getCuratorFramework().getConnectionStateListenable().removeListener(onReconnect);
      zkClient.getCuratorFramework().getConnectionStateListenable().removeListener(onDisconnect);
      ExecutorUtil.shutdownNowAndAwaitTermination(zkConnectionListenerCallbackExecutor);
    } catch (Exception e) {
      log.warn(
          "Error stopping and shutting down zkConnectionListenerCallbackExecutor. Continue closing the ZkController",
          e);
    }

    try {
      if (getZkClient().isConnected()) {
        this.removeEphemeralLiveNode();
      }
    } catch (IllegalStateException
        | SessionExpiredException
        | KeeperException.ConnectionLossException e) {

    } catch (Exception e) {
      log.warn("Error removing live node. Continuing to close CoreContainer", e);
    }

    try {
      if (getZkClient().isConnected()) {
        log.info("Publish this node as DOWN...");
        publishNodeAsDown(getNodeName());
      }
    } catch (Exception e) {
      log.warn("Error publishing nodes as down. Continuing to close CoreContainer", e);
    }

    ExecutorService customThreadPool =
        ExecutorUtil.newMDCAwareCachedThreadPool(new SolrNamedThreadFactory("preCloseThreadPool"));

    try {
      synchronized (collectionToTerms) {
        collectionToTerms
            .values()
            .forEach(zkCollectionTerms -> customThreadPool.execute(zkCollectionTerms::close));
      }

      replicateFromLeaders
          .values()
          .forEach(
              replicateFromLeader ->
                  customThreadPool.execute(replicateFromLeader::stopReplication));
    } finally {
      ExecutorUtil.shutdownAndAwaitTermination(customThreadPool);
    }
  }

  /** Closes the underlying ZooKeeper client. */
  @Override
  public void close() {
    if (!this.isClosed) preClose();

    ExecutorService customThreadPool =
        ExecutorUtil.newMDCAwareCachedThreadPool(new SolrNamedThreadFactory("closeThreadPool"));

    customThreadPool.execute(() -> IOUtils.closeQuietly(overseerElector.getContext()));

    customThreadPool.execute(() -> IOUtils.closeQuietly(overseer));

    try {
      customThreadPool.execute(
          () -> {
            Collection<ElectionContext> values = electionContexts.values();
            synchronized (electionContexts) {
              values.forEach(IOUtils::closeQuietly);
            }
          });

    } finally {

      sysPropsCacher.close();
      customThreadPool.execute(() -> IOUtils.closeQuietly(cloudManager));
      customThreadPool.execute(() -> IOUtils.closeQuietly(cloudSolrClient));

      try {
        try {
          zkStateReader.close();
        } catch (Exception e) {
          log.error("Error closing zkStateReader", e);
        }
      } finally {
        try {
          zkClient.close();
        } catch (Exception e) {
          log.error("Error closing zkClient", e);
        } finally {

          // just in case the OverseerElectionContext managed to start another Overseer
          IOUtils.closeQuietly(overseer);

          ExecutorUtil.shutdownAndAwaitTermination(customThreadPool);
        }
      }
    }
    assert ObjectReleaseTracker.release(this);
  }

  /**
   * Best effort to give up the leadership of a shard in a core after hitting a tragic exception
   *
   * @param cd The current core descriptor
   */
  public void giveupLeadership(CoreDescriptor cd) {
    assert cd != null;

    String collection = cd.getCollectionName();
    if (collection == null) return;

    DocCollection dc = getClusterState().getCollectionOrNull(collection);
    if (dc == null) return;

    Slice shard = dc.getSlice(cd.getCloudDescriptor().getShardId());
    if (shard == null) return;

    // if this replica is not a leader, it will be put in recovery state by the leader
    String leader = cd.getCloudDescriptor().getCoreNodeName();
    if (!Objects.equals(shard.getReplica(leader), shard.getLeader())) return;

    Set<String> liveNodes = getClusterState().getLiveNodes();
    int numActiveReplicas =
        shard
            .getReplicas(
                rep ->
                    rep.getState() == Replica.State.ACTIVE
                        && rep.getType().leaderEligible
                        && liveNodes.contains(rep.getNodeName()))
            .size();

    // at least the leader still be able to search, we should give up leadership if other replicas
    // can take over
    if (numActiveReplicas >= 2) {
      ContextKey key = new ContextKey(collection, leader);
      ElectionContext context = electionContexts.get(key);
      if (context instanceof ShardLeaderElectionContextBase) {
        LeaderElector elector = ((ShardLeaderElectionContextBase) context).getLeaderElector();
        try {
          log.warn("Leader {} met tragic exception, give up its leadership", key);
          elector.retryElection(context, false);
        } catch (KeeperException | InterruptedException e) {
          SolrZkClient.checkInterrupted(e);
          log.error("Met exception on give up leadership for {}", key, e);
        }
      } else {
        // The node is probably already gone
        log.warn("Could not get election context {} to give up leadership", key);
      }
    }
  }

  /**
   * @return information about the cluster from ZooKeeper
   */
  public ClusterState getClusterState() {
    return zkStateReader.getClusterState();
  }

  public DistributedClusterStateUpdater getDistributedClusterStateUpdater() {
    return distributedClusterStateUpdater;
  }

  public SolrCloudManager getSolrCloudManager() {
    if (cloudManager != null) {
      return cloudManager;
    }
    synchronized (this) {
      if (cloudManager != null) {
        return cloudManager;
      }
      cloudSolrClient =
          new CloudHttp2SolrClient.Builder(new ZkClientClusterStateProvider(zkStateReader))
              .withHttpClient(cc.getDefaultHttpSolrClient())
              .build();
      cloudManager = new SolrClientCloudManager(cloudSolrClient, cc.getObjectCache());
    }
    return cloudManager;
  }

  // normalize host removing any url scheme.
  // input can be null, host, or url_prefix://host
  private String normalizeHostName(String host) {
    if (host == null || host.length() == 0) {
      host = AddressUtils.getHostToAdvertise();
    } else {
      if (URLUtil.hasScheme(host)) {
        host = URLUtil.removeScheme(host);
      }
    }

    return host;
  }

  public String getHostName() {
    return hostName;
  }

  public int getHostPort() {
    return localHostPort;
  }

  public SolrZkClient getZkClient() {
    return zkClient;
  }

  /**
   * @return zookeeper server address
   */
  public String getZkServerAddress() {
    return zkServerAddress;
  }

  boolean isClosed() {
    return isClosed;
  }

  /**
   * Create the zknodes necessary for a cluster to operate
   *
   * @param zkClient a SolrZkClient
   * @throws KeeperException if there is a Zookeeper error
   * @throws InterruptedException on interrupt
   */
  public static void createClusterZkNodes(SolrZkClient zkClient)
      throws KeeperException, InterruptedException, IOException {
    ZkMaintenanceUtils.ensureExists(ZkStateReader.LIVE_NODES_ZKNODE, zkClient);
    ZkMaintenanceUtils.ensureExists(ZkStateReader.NODE_ROLES, zkClient);
    for (NodeRoles.Role role : NodeRoles.Role.values()) {
      ZkMaintenanceUtils.ensureExists(NodeRoles.getZNodeForRole(role), zkClient);
      for (String mode : role.supportedModes()) {
        ZkMaintenanceUtils.ensureExists(NodeRoles.getZNodeForRoleMode(role, mode), zkClient);
      }
    }

    ZkMaintenanceUtils.ensureExists(ZkStateReader.COLLECTIONS_ZKNODE, zkClient);
    ZkMaintenanceUtils.ensureExists(ZkStateReader.ALIASES, zkClient);
    byte[] emptyJson = "{}".getBytes(StandardCharsets.UTF_8);
    ZkMaintenanceUtils.ensureExists(ZkStateReader.SOLR_SECURITY_CONF_PATH, emptyJson, zkClient);
    repairSecurityJson(zkClient);
  }

  private static void repairSecurityJson(SolrZkClient zkClient)
      throws KeeperException, InterruptedException {
    List<ACL> securityConfAcl = zkClient.getACL(ZkStateReader.SOLR_SECURITY_CONF_PATH, null, true);
    ACLProvider aclProvider = zkClient.getZkACLProvider();

    boolean tryUpdate = false;

    if (OPEN_ACL_UNSAFE.equals(securityConfAcl)) {
      List<ACL> aclToAdd = aclProvider.getAclForPath(ZkStateReader.SOLR_SECURITY_CONF_PATH);
      if (OPEN_ACL_UNSAFE.equals(aclToAdd)) {
        log.warn(
            "Contents of zookeeper /security.json are world-readable;"
                + " consider setting up ACLs as described in https://solr.apache.org/guide/solr/latest/deployment-guide/zookeeper-access-control.html");
      } else {
        tryUpdate = true;
      }
    } else if (aclProvider instanceof SecurityAwareZkACLProvider) {
      // Use Set to explicitly ignore order
      Set<ACL> nonSecureACL = new HashSet<>(aclProvider.getDefaultAcl());
      // case where security.json was not treated as a secure path
      if (nonSecureACL.equals(new HashSet<>(securityConfAcl))) {
        tryUpdate = true;
      }
    }

    if (tryUpdate) {
      if (Boolean.getBoolean("solr.security.aclautorepair.disable")) {
        log.warn(
            "Detected inconsistent ACLs for zookeeper /security.json, but self-repair is disabled.");
      } else {
        log.info("Detected inconsistent ACLs for zookeeper /security.json, attempting to repair.");
        zkClient.updateACLs(ZkStateReader.SOLR_SECURITY_CONF_PATH);
      }
    }
  }

  private void init() {
    try {
      createClusterZkNodes(zkClient);
      zkStateReader.createClusterStateWatchersAndUpdate();

      // note: Can't read cluster properties until createClusterState ^ is called
      final String urlSchemeFromClusterProp =
          zkStateReader.getClusterProperty(ZkStateReader.URL_SCHEME, ZkStateReader.HTTP);

      // this must happen after zkStateReader has initialized the cluster props
      this.baseURL = Utils.getBaseUrlForNodeName(this.nodeName, urlSchemeFromClusterProp);

      checkForExistingEphemeralNode();
      registerLiveNodesListener();

      // start the overseer first as following code may need it's processing
      if (!zkRunOnly) {
        overseerElector = new LeaderElector(zkClient);
        this.overseer =
            new Overseer(
                (HttpShardHandler) cc.getShardHandlerFactory().getShardHandler(),
                cc.getUpdateShardHandler(),
                CommonParams.CORES_HANDLER_PATH,
                zkStateReader,
                this,
                cloudConfig);
        ElectionContext context = new OverseerElectionContext(zkClient, overseer, getNodeName());
        overseerElector.setup(context);
        if (cc.nodeRoles.isOverseerAllowedOrPreferred()) {
          overseerElector.joinElection(context, false);
        }
      }

      Stat stat = zkClient.exists(ZkStateReader.LIVE_NODES_ZKNODE, null, true);
      if (stat != null && stat.getNumChildren() > 0) {
        publishAndWaitForDownStates();
      }

      // Do this last to signal we're up.
      createEphemeralLiveNode();
    } catch (IOException e) {
      log.error("", e);
      throw new SolrException(
          SolrException.ErrorCode.SERVER_ERROR, "Can't create ZooKeeperController", e);
    } catch (InterruptedException e) {
      // Restore the interrupted status
      Thread.currentThread().interrupt();
      log.error("", e);
      throw new ZooKeeperException(SolrException.ErrorCode.SERVER_ERROR, "", e);
    } catch (KeeperException e) {
      log.error("", e);
      throw new ZooKeeperException(SolrException.ErrorCode.SERVER_ERROR, "", e);
    }
  }

  private void checkForExistingEphemeralNode() throws KeeperException, InterruptedException {
    if (zkRunOnly) {
      return;
    }
    String nodeName = getNodeName();
    String nodePath = ZkStateReader.LIVE_NODES_ZKNODE + "/" + nodeName;

    if (!zkClient.exists(nodePath, true)) {
      return;
    }

    final CountDownLatch deletedLatch = new CountDownLatch(1);
    Stat stat =
        zkClient.exists(
            nodePath,
            event -> {
              if (Watcher.Event.EventType.None.equals(event.getType())) {
                return;
              }
              if (Watcher.Event.EventType.NodeDeleted.equals(event.getType())) {
                deletedLatch.countDown();
              }
            },
            true);

    if (stat == null) {
      // znode suddenly disappeared but that's okay
      return;
    }

    boolean deleted =
        deletedLatch.await(zkClient.getZkSessionTimeout() * 2L, TimeUnit.MILLISECONDS);
    if (!deleted) {
      throw new SolrException(
          ErrorCode.SERVER_ERROR,
          "A previous ephemeral live node still exists. "
              + "Solr cannot continue. Please ensure that no other Solr process using the same port is running already.");
    }
  }

  private void registerLiveNodesListener() {
    // this listener is used for generating nodeLost events, so we check only if
    // some nodes went missing compared to last state
    LiveNodesListener listener =
        (oldNodes, newNodes) -> {
          oldNodes.removeAll(newNodes);
          if (oldNodes.isEmpty()) { // only added nodes
            return false;
          }
          if (isClosed) {
            return true;
          }
          // if this node is in the top three then attempt to create nodeLost message
          int i = 0;
          for (String n : newNodes) {
            if (n.equals(getNodeName())) {
              break;
            }
            if (i > 2) {
              return false; // this node is not in the top three
            }
            i++;
          }
          return false;
        };
    zkStateReader.registerLiveNodesListener(listener);
  }

  public void publishAndWaitForDownStates() throws KeeperException, InterruptedException {
    publishAndWaitForDownStates(WAIT_DOWN_STATES_TIMEOUT_SECONDS);
  }

  public void publishAndWaitForDownStates(int timeoutSeconds) throws InterruptedException {
    final String nodeName = getNodeName();

    Collection<String> collectionsWithLocalReplica = publishNodeAsDown(nodeName);
    Map<String, Boolean> collectionsAlreadyVerified =
        new ConcurrentHashMap<>(collectionsWithLocalReplica.size());

    CountDownLatch latch = new CountDownLatch(collectionsWithLocalReplica.size());
    for (String collectionWithLocalReplica : collectionsWithLocalReplica) {
      zkStateReader.registerDocCollectionWatcher(
          collectionWithLocalReplica,
          (collectionState) -> {
            if (collectionState == null) return false;
            boolean allStatesCorrect =
                Optional.ofNullable(collectionState.getReplicasOnNode(nodeName)).stream()
                    .flatMap(List::stream)
                    .allMatch(replica -> replica.getState() == Replica.State.DOWN);

            if (allStatesCorrect
                && collectionsAlreadyVerified.putIfAbsent(collectionWithLocalReplica, true)
                    == null) {
              latch.countDown();
            }
            return allStatesCorrect;
          });
    }

    boolean allPublishedDown = latch.await(timeoutSeconds, TimeUnit.SECONDS);
    if (!allPublishedDown) {
      log.warn("Timed out waiting to see all nodes published as DOWN in our cluster state.");
    }
  }

  /**
   * Validates if the chroot exists in zk (or if it is successfully created). Optionally, if create
   * is set to true this method will create the path in case it doesn't exist
   *
   * @return true if the path exists or is created false if the path doesn't exist and 'create' =
   *     false
   */
  public static boolean checkChrootPath(String zkHost, boolean create)
      throws KeeperException, InterruptedException {
    if (!SolrZkClient.containsChroot(zkHost)) {
      return true;
    }
    log.trace("zkHost includes chroot");
    String chrootPath = zkHost.substring(zkHost.indexOf('/'), zkHost.length());

    SolrZkClient tmpClient =
        new SolrZkClient.Builder()
            .withUrl(zkHost.substring(0, zkHost.indexOf('/')))
            .withTimeout(SolrZkClientTimeout.DEFAULT_ZK_CLIENT_TIMEOUT, TimeUnit.MILLISECONDS)
            .withConnTimeOut(SolrZkClientTimeout.DEFAULT_ZK_CONNECT_TIMEOUT, TimeUnit.MILLISECONDS)
            .build();
    boolean exists = tmpClient.exists(chrootPath, true);
    if (!exists && create) {
      log.info("creating chroot {}", chrootPath);
      tmpClient.makePath(chrootPath, false, true);
      exists = true;
    }
    tmpClient.close();
    return exists;
  }

  public boolean isConnected() {
    return zkClient.isConnected();
  }

  private void createEphemeralLiveNode() throws KeeperException, InterruptedException {
    if (zkRunOnly) {
      return;
    }

    String nodeName = getNodeName();
    String nodePath = ZkStateReader.LIVE_NODES_ZKNODE + "/" + nodeName;
    log.info("Register node as live in ZooKeeper:{}", nodePath);
    Map<NodeRoles.Role, String> roles = cc.nodeRoles.getRoles();
    List<SolrZkClient.CuratorOpBuilder> ops = new ArrayList<>(roles.size() + 1);
    ops.add(op -> op.create().withMode(CreateMode.EPHEMERAL).forPath(nodePath));

    // Create the roles node as well
    roles.forEach(
        (role, mode) ->
            ops.add(
                op ->
                    op.create()
                        .withMode(CreateMode.EPHEMERAL)
                        .forPath(NodeRoles.getZNodeForRoleMode(role, mode) + "/" + nodeName)));

    zkClient.multi(ops);
  }

  public void removeEphemeralLiveNode() throws KeeperException, InterruptedException {
    if (zkRunOnly) {
      return;
    }
    String nodeName = getNodeName();
    String nodePath = ZkStateReader.LIVE_NODES_ZKNODE + "/" + nodeName;
    log.info("Remove node as live in ZooKeeper:{}", nodePath);
    try {
      zkClient.delete(nodePath, -1, true);
    } catch (NoNodeException e) {

    }
    cc.nodeRoles
        .getRoles()
        .forEach(
            (role, mode) -> {
              try {
                zkClient.delete(
                    NodeRoles.getZNodeForRoleMode(role, mode) + "/" + nodeName, -1, true);
              } catch (KeeperException e) {

              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            });
  }

  public String getNodeName() {
    return nodeName;
  }

  /** Returns true if the path exists */
  public boolean pathExists(String path) throws KeeperException, InterruptedException {
    return zkClient.exists(path, true);
  }

  /**
   * Register shard with ZooKeeper.
   *
   * @return the shardId for the SolrCore
   */
  public String register(String coreName, final CoreDescriptor desc, boolean skipRecovery)
      throws Exception {
    return register(coreName, desc, false, false, skipRecovery);
  }

  /**
   * Register shard with ZooKeeper.
   *
   * @return the shardId for the SolrCore
   */
  public String register(
      String coreName,
      final CoreDescriptor desc,
      boolean recoverReloadedCores,
      boolean afterExpiration,
      boolean skipRecovery)
      throws Exception {
    MDCLoggingContext.setCoreDescriptor(cc, desc);
    try {
      // pre register has published our down state
      final String baseUrl = getBaseUrl();
      final CloudDescriptor cloudDesc = desc.getCloudDescriptor();
      final String collection = cloudDesc.getCollectionName();
      final String shardId = cloudDesc.getShardId();
      final String coreZkNodeName = cloudDesc.getCoreNodeName();
      assert coreZkNodeName != null : "we should have a coreNodeName by now";

      // check replica's existence in clusterstate first
      try {
        zkStateReader.waitForState(
            collection,
            100,
            TimeUnit.MILLISECONDS,
            (collectionState) ->
                getReplicaOrNull(collectionState, shardId, coreZkNodeName) != null);
      } catch (TimeoutException e) {
        throw new SolrException(
            ErrorCode.SERVER_ERROR,
            "Error registering SolrCore, timeout waiting for replica present in clusterstate");
      }
      Replica replica =
          getReplicaOrNull(
              zkStateReader.getClusterState().getCollectionOrNull(collection),
              shardId,
              coreZkNodeName);
      if (replica == null) {
        throw new SolrException(
            ErrorCode.SERVER_ERROR,
            "Error registering SolrCore, replica is removed from clusterstate");
      }

      if (replica.getType().leaderEligible) {
        getCollectionTerms(collection).register(cloudDesc.getShardId(), coreZkNodeName);
      }

      ZkShardTerms shardTerms = getShardTerms(collection, cloudDesc.getShardId());

      log.debug(
          "Register replica - core:{} address:{} collection:{} shard:{}",
          coreName,
          baseUrl,
          collection,
          shardId);

      try {
        // If we're a preferred leader, insert ourselves at the head of the queue
        boolean joinAtHead = replica.getBool(SliceMutator.PREFERRED_LEADER_PROP, false);
        if (replica.getType().leaderEligible) {
          joinElection(desc, afterExpiration, joinAtHead);
        } else {
          if (joinAtHead) {
            log.warn(
                "Replica {} was designated as preferred leader but its type is {}, It won't join election",
                coreZkNodeName,
                replica.getType());
          }
          if (log.isDebugEnabled()) {
            log.debug(
                "Replica {} skipping election because its type is {}",
                coreZkNodeName,
                replica.getType());
          }
        }
      } catch (InterruptedException e) {
        // Restore the interrupted status
        Thread.currentThread().interrupt();
        throw new ZooKeeperException(SolrException.ErrorCode.SERVER_ERROR, "", e);
      } catch (KeeperException | IOException e) {
        throw new ZooKeeperException(SolrException.ErrorCode.SERVER_ERROR, "", e);
      }

      final String ourUrl = ZkCoreNodeProps.getCoreUrl(baseUrl, coreName);

      // Check if we are the (new) leader before deciding if/what type of recovery to do
      boolean isLeader = false;
      if (replica.getType().leaderEligible) {
        // if are eligible to be a leader, then we might currently be participating in leader
        // election.

        // in this case, we want to wait for the leader as long as the leader might
        // wait for a vote, at least - but also long enough that a large cluster has
        // time to get its act together
        String leaderUrl = getLeader(cloudDesc, leaderVoteWait + 600000);
        log.debug("We are {} and leader is {}", ourUrl, leaderUrl);
        isLeader = leaderUrl.equals(ourUrl);
      }

      try (SolrCore core = cc.getCore(desc.getName())) {

        // recover from local transaction log and wait for it to complete before
        // going active
        // TODO: should this be moved to another thread? To recoveryStrat?
        // TODO: should this actually be done earlier, before (or as part of)
        // leader election perhaps?

        if (core == null) {
          throw new SolrException(
              ErrorCode.SERVICE_UNAVAILABLE, "SolrCore is no longer available to register");
        }

        UpdateLog ulog = core.getUpdateHandler().getUpdateLog();
        boolean isTlogReplicaAndNotLeader = replica.getType() == Replica.Type.TLOG && !isLeader;
        if (isTlogReplicaAndNotLeader) {
          String commitVersion = ReplicateFromLeader.getCommitVersion(core);
          if (commitVersion != null) {
            ulog.copyOverOldUpdates(Long.parseLong(commitVersion));
          }
        }
        // we will call register again after zk expiration and on reload
        if (!afterExpiration && !core.isReloaded() && ulog != null && !isTlogReplicaAndNotLeader) {
          // disable recovery in case shard is in construction state (for shard splits)
          Slice slice = getClusterState().getCollection(collection).getSlice(shardId);
          if (slice.getState() != Slice.State.CONSTRUCTION || !isLeader) {
            Future<UpdateLog.RecoveryInfo> recoveryFuture =
                core.getUpdateHandler().getUpdateLog().recoverFromLog();
            if (recoveryFuture != null) {
              log.info(
                  "Replaying tlog for {} during startup... NOTE: This can take a while.", ourUrl);
              recoveryFuture.get(); // NOTE: this could potentially block for
              // minutes or more!
              // TODO: public as recovering in the mean time?
              // TODO: in the future we could do peersync in parallel with recoverFromLog
            } else {
              if (log.isDebugEnabled()) {
                log.debug("No LogReplay needed for core={} baseURL={}", core.getName(), baseUrl);
              }
            }
          }
        }

        // If we don't already have a reason to skipRecovery, check if we should skip
        // due to replica property
        if (!skipRecovery) {
          skipRecovery = checkSkipRecoveryReplicaProp(core, replica);
        }

        boolean didRecovery =
            checkRecovery(
                recoverReloadedCores,
                isLeader,
                skipRecovery,
                collection,
                coreZkNodeName,
                shardId,
                core,
                cc,
                afterExpiration);
        if (!didRecovery) {
          if (replica.getType().replicateFromLeader && !isLeader) {
            startReplicationFromLeader(coreName, replica.getType().requireTransactionLog);
          }
          publish(desc, Replica.State.ACTIVE);
        }

        if (replica.getType().leaderEligible) {
          // the watcher is added to a set so multiple calls of this method will left only one
          // watcher
          shardTerms.addListener(
              new RecoveringCoreTermWatcher(core.getCoreDescriptor(), getCoreContainer()));
        }
        core.getCoreDescriptor().getCloudDescriptor().setHasRegistered(true);
      } catch (Exception e) {
        unregister(coreName, desc, false);
        throw e;
      }

      // make sure we have an update cluster state right away
      zkStateReader.forceUpdateCollection(collection);
      // the watcher is added to a set so multiple calls of this method will left only one watcher
      zkStateReader.registerDocCollectionWatcher(
          cloudDesc.getCollectionName(),
          new UnloadCoreOnDeletedWatcher(coreZkNodeName, shardId, desc.getName()));
      return shardId;
    } finally {
      MDCLoggingContext.clear();
    }
  }

  static final String SKIP_LEADER_RECOVERY_PROP = "skipLeaderRecovery";

  /**
   * Note: internally, property names are always lowercase
   *
   * @see #SKIP_LEADER_RECOVERY_PROP
   */
  static final String SKIP_LEADER_RECOVERY_PROP_KEY =
      CollectionAdminParams.PROPERTY_PREFIX + SKIP_LEADER_RECOVERY_PROP.toLowerCase(Locale.ROOT);

  /**
   * Returns true if and only if this replica has a replica property indicating that leader recovery
   * should be skipped <em>AND</em> the replica meets the neccessary criteria to respect that
   * property.
   *
   * @see #SKIP_LEADER_RECOVERY_PROP_KEY
   */
  private boolean checkSkipRecoveryReplicaProp(final SolrCore core, final Replica replica) {

    if (!replica.getBool(SKIP_LEADER_RECOVERY_PROP_KEY, false)) {
      // Property is not set (or set to false) so we are definitely not skipping recovery
      return false;
    }

    // else: Sanity check if we should respect the property ...

    if (replica.getType().requireTransactionLog) {
      if (log.isWarnEnabled()) {
        log.warn(
            "Ignoring {} replica property for replica {} because replica type {} requires transaction logs",
            SKIP_LEADER_RECOVERY_PROP,
            replica.getName(),
            replica.getType());
      }
      return false;
    }

    if (null == ReplicateFromLeader.getCommitVersion(core)) {
      if (log.isWarnEnabled()) {
        log.warn(
            "Ignoring {} replica property for replica {} because there is no local index commit",
            SKIP_LEADER_RECOVERY_PROP,
            replica.getName());
      }
      return false;
    }

    if (log.isInfoEnabled()) {
      log.info(
          "Skipping recovery from leader for replica {} due to {} replica property",
          replica.getName(),
          SKIP_LEADER_RECOVERY_PROP);
    }
    return true;
  }

  private Replica getReplicaOrNull(DocCollection docCollection, String shard, String coreNodeName) {
    if (docCollection == null) return null;

    Slice slice = docCollection.getSlice(shard);
    if (slice == null) return null;

    Replica replica = slice.getReplica(coreNodeName);
    if (replica == null) return null;
    if (!getNodeName().equals(replica.getNodeName())) return null;

    return replica;
  }

  public void startReplicationFromLeader(String coreName, boolean switchTransactionLog) {
    log.info("{} starting background replication from leader", coreName);
    ReplicateFromLeader replicateFromLeader = new ReplicateFromLeader(cc, coreName);
    synchronized (
        replicateFromLeader) { // synchronize to prevent any stop before we finish the start
      if (replicateFromLeaders.putIfAbsent(coreName, replicateFromLeader) == null) {
        replicateFromLeader.startReplication(switchTransactionLog);
      } else {
        log.warn("A replicate from leader instance already exists for core {}", coreName);
      }
    }
  }

  public void stopReplicationFromLeader(String coreName) {
    log.info("{} stopping background replication from leader", coreName);
    ReplicateFromLeader replicateFromLeader = replicateFromLeaders.remove(coreName);
    if (replicateFromLeader != null) {
      synchronized (replicateFromLeader) {
        replicateFromLeader.stopReplication();
      }
    }
  }

  // timeoutms is the timeout for the first call to get the leader - there is then
  // a longer wait to make sure that leader matches our local state
  private String getLeader(final CloudDescriptor cloudDesc, int timeoutms) {

    String collection = cloudDesc.getCollectionName();
    String shardId = cloudDesc.getShardId();
    // rather than look in the cluster state file, we go straight to the zknodes
    // here, because on cluster restart there could be stale leader info in the
    // cluster state node that won't be updated for a moment
    String leaderUrl;
    try {
      leaderUrl = getLeaderProps(collection, cloudDesc.getShardId(), timeoutms).getCoreUrl();

      // now wait until our currently cloud state contains the latest leader since we found it in
      // zk, we are willing to wait a while to find it in state
      String clusterStateLeaderUrl = zkStateReader.getLeaderUrl(collection, shardId, timeoutms * 2);
      int tries = 0;
      final int msInSec = 1000;
      int maxTries = leaderConflictResolveWait / msInSec;
      while (!leaderUrl.equals(clusterStateLeaderUrl)) {
        if (cc.isShutDown()) throw new AlreadyClosedException();
        if (tries > maxTries) {
          throw new SolrException(
              ErrorCode.SERVER_ERROR,
              "There is conflicting information about the leader of shard: "
                  + cloudDesc.getShardId()
                  + " our state says:"
                  + clusterStateLeaderUrl
                  + " but zookeeper says:"
                  + leaderUrl);
        }
        tries++;
        if (tries % 30 == 0) {
          String warnMsg =
              String.format(
                  Locale.ENGLISH,
                  "Still seeing conflicting information about the leader "
                      + "of shard %s for collection %s after %d seconds; our state says %s, but ZooKeeper says %s",
                  cloudDesc.getShardId(),
                  collection,
                  tries,
                  clusterStateLeaderUrl,
                  leaderUrl);
          log.warn(warnMsg);
        }
        Thread.sleep(msInSec);
        clusterStateLeaderUrl = zkStateReader.getLeaderUrl(collection, shardId, timeoutms);
        leaderUrl = getLeaderProps(collection, cloudDesc.getShardId(), timeoutms).getCoreUrl();
      }

    } catch (IllegalStateException e) {
      throw e;
    } catch (Exception e) {
      log.error("Error getting leader from zk", e);
      throw new SolrException(
          SolrException.ErrorCode.SERVER_ERROR,
          "Error getting leader from zk for shard " + shardId,
          e);
    }
    return leaderUrl;
  }

  /**
   * Get leader props directly from zk nodes.
   *
   * @throws SessionExpiredException on zk session expiration.
   */
  public ZkCoreNodeProps getLeaderProps(final String collection, final String slice, int timeoutms)
      throws InterruptedException, SessionExpiredException {
    return getLeaderProps(collection, slice, timeoutms, true);
  }

  /**
   * Get leader props directly from zk nodes.
   *
   * @return leader props
   * @throws SessionExpiredException on zk session expiration.
   */
  public ZkCoreNodeProps getLeaderProps(
      final String collection,
      final String slice,
      int timeoutms,
      boolean failImmediatelyOnExpiration)
      throws InterruptedException, SessionExpiredException {
    int iterCount = timeoutms / 1000;
    Exception exp = null;
    while (iterCount-- > 0) {
      try {
        byte[] data =
            zkClient.getData(
                ZkStateReader.getShardLeadersPath(collection, slice), null, null, true);
        ZkCoreNodeProps leaderProps = new ZkCoreNodeProps(ZkNodeProps.load(data));
        return leaderProps;
      } catch (InterruptedException e) {
        throw e;
      } catch (SessionExpiredException e) {
        if (failImmediatelyOnExpiration) {
          throw e;
        }
        exp = e;
        Thread.sleep(1000);
      } catch (Exception e) {
        exp = e;
        Thread.sleep(1000);
      }
      if (cc.isShutDown()) {
        throw new AlreadyClosedException();
      }
    }
    throw new SolrException(ErrorCode.SERVICE_UNAVAILABLE, "Could not get leader props", exp);
  }

  private void joinElection(CoreDescriptor cd, boolean afterExpiration, boolean joinAtHead)
      throws InterruptedException, KeeperException, IOException {
    // look for old context - if we find it, cancel it
    String collection = cd.getCloudDescriptor().getCollectionName();
    final String coreNodeName = cd.getCloudDescriptor().getCoreNodeName();

    ContextKey contextKey = new ContextKey(collection, coreNodeName);

    ElectionContext prevContext = electionContexts.get(contextKey);

    if (prevContext != null) {
      prevContext.cancelElection();
    }

    String shardId = cd.getCloudDescriptor().getShardId();

    Map<String, Object> props = new HashMap<>();
    // we only put a subset of props into the leader node
    props.put(ZkStateReader.CORE_NAME_PROP, cd.getName());
    props.put(ZkStateReader.NODE_NAME_PROP, getNodeName());
    props.put(ZkStateReader.BASE_URL_PROP, zkStateReader.getBaseUrlForNodeName(getNodeName()));
    props.put(ZkStateReader.CORE_NODE_NAME_PROP, coreNodeName);

    ZkNodeProps ourProps = new ZkNodeProps(props);

    LeaderElector leaderElector = new LeaderElector(zkClient, contextKey, electionContexts);
    ElectionContext context =
        new ShardLeaderElectionContext(
            leaderElector, shardId, collection, coreNodeName, ourProps, this, cc);

    leaderElector.setup(context);
    electionContexts.put(contextKey, context);
    leaderElector.joinElection(context, false, joinAtHead);
  }

  /** Returns whether or not a recovery was started */
  private boolean checkRecovery(
      boolean recoverReloadedCores,
      final boolean isLeader,
      boolean skipRecovery,
      final String collection,
      String coreZkNodeName,
      String shardId,
      SolrCore core,
      CoreContainer cc,
      boolean afterExpiration) {
    if (SKIP_AUTO_RECOVERY) {
      log.warn("Skipping recovery according to sys prop solrcloud.skip.autorecovery");
      return false;
    }
    boolean doRecovery = true;
    if (!isLeader) {

      if (skipRecovery || (!afterExpiration && core.isReloaded() && !recoverReloadedCores)) {
        doRecovery = false;
      }

      if (doRecovery) {
        if (log.isInfoEnabled()) {
          log.info("Core needs to recover:{}", core.getName());
        }
        core.getUpdateHandler().getSolrCoreState().doRecovery(cc, core.getCoreDescriptor());
        return true;
      }

      ZkShardTerms zkShardTerms = getShardTerms(collection, shardId);
      if (zkShardTerms.registered(coreZkNodeName)
          && !zkShardTerms.canBecomeLeader(coreZkNodeName)) {
        if (log.isInfoEnabled()) {
          log.info("Leader's term larger than core {}; starting recovery process", core.getName());
        }
        core.getUpdateHandler().getSolrCoreState().doRecovery(cc, core.getCoreDescriptor());
        return true;
      }
    } else {
      log.info("I am the leader, no recovery necessary");
    }

    return false;
  }

  public String getBaseUrl() {
    return baseURL;
  }

  public void publish(final CoreDescriptor cd, final Replica.State state)
      throws KeeperException, InterruptedException {
    publish(cd, state, true, false);
  }

  /** Publish core state to overseer. */
  public void publish(
      final CoreDescriptor cd,
      final Replica.State state,
      boolean updateLastState,
      boolean forcePublish)
      throws KeeperException, InterruptedException {
    if (!forcePublish) {
      try (SolrCore core = cc.getCore(cd.getName())) {
        if (core == null || core.isClosed()) {
          return;
        }
      }
    }
    MDCLoggingContext.setCoreDescriptor(cc, cd);
    try {
      String collection = cd.getCloudDescriptor().getCollectionName();

      log.debug("publishing state={}", state);
      // System.out.println(Thread.currentThread().getStackTrace()[3]);

      assert collection != null && collection.length() > 0;

      String shardId = cd.getCloudDescriptor().getShardId();

      String coreNodeName = cd.getCloudDescriptor().getCoreNodeName();

      MapWriter m =
          props -> {
            props.put(Overseer.QUEUE_OPERATION, OverseerAction.STATE.toLower());
            props.put(ZkStateReader.STATE_PROP, state.toString());
            props.put(ZkStateReader.CORE_NAME_PROP, cd.getName());
            props.put(ZkStateReader.NODE_NAME_PROP, getNodeName());
            props.put(
                ZkStateReader.BASE_URL_PROP, zkStateReader.getBaseUrlForNodeName(getNodeName()));
            props.put(ZkStateReader.SHARD_ID_PROP, cd.getCloudDescriptor().getShardId());
            props.put(ZkStateReader.COLLECTION_PROP, collection);
            props.put(
                ZkStateReader.REPLICA_TYPE, cd.getCloudDescriptor().getReplicaType().toString());
            props.put(ZkStateReader.FORCE_SET_STATE_PROP, "false");
            props.putIfNotNull(ZkStateReader.CORE_NODE_NAME_PROP, coreNodeName);
          };

      try (SolrCore core = cc.getCore(cd.getName())) {
        if (core != null && state == Replica.State.ACTIVE) {
          ensureRegisteredSearcher(core);
        }
        if (core != null && core.getDirectoryFactory().isSharedStorage()) {
          if (core.getDirectoryFactory().isSharedStorage()) {
            // append additional entries to 'm'
            MapWriter original = m;
            m =
                props -> {
                  original.writeMap(props);
                  props.put(ZkStateReader.SHARED_STORAGE_PROP, "true");
                  props.put("dataDir", core.getDataDir());
                  UpdateLog ulog = core.getUpdateHandler().getUpdateLog();
                  if (ulog != null) {
                    props.put("ulogDir", ulog.getUlogDir());
                  }
                };
          }
        }
      } catch (SolrCoreInitializationException ex) {
        // The core had failed to initialize (in a previous request, not this one), hence nothing to
        // do here.
        if (log.isInfoEnabled()) {
          log.info("The core '{}' had failed to initialize before.", cd.getName());
        }
      }

      // pull replicas are excluded because their terms are not considered
      if (state == Replica.State.RECOVERING
          && cd.getCloudDescriptor().getReplicaType().leaderEligible) {
        // state is used by client, state of replica can change from RECOVERING to DOWN without
        // needed to finish recovery by calling this we will know that a replica actually finished
        // recovery or not
        getShardTerms(collection, shardId).startRecovering(coreNodeName);
      }
      if (state == Replica.State.ACTIVE
          && cd.getCloudDescriptor().getReplicaType().leaderEligible) {
        getShardTerms(collection, shardId).doneRecovering(coreNodeName);
      }

      if (updateLastState) {
        cd.getCloudDescriptor().setLastPublished(state);
      }
      DocCollection coll = zkStateReader.getCollection(collection);
      // extra handling for PRS, we need to write the PRS entries from this node directly,
      // as overseer does not and should not handle those entries
      if (coll != null && coll.isPerReplicaState() && coreNodeName != null) {
        PerReplicaStates perReplicaStates =
            PerReplicaStatesOps.fetch(coll.getZNode(), zkClient, coll.getPerReplicaStates());
        PerReplicaStatesOps.flipState(coreNodeName, state, perReplicaStates)
            .persist(coll.getZNode(), zkClient);
      }
      if (forcePublish || updateStateDotJson(coll, coreNodeName)) {
        if (distributedClusterStateUpdater.isDistributedStateUpdate()) {
          distributedClusterStateUpdater.doSingleStateUpdate(
              DistributedClusterStateUpdater.MutatingCommand.ReplicaSetState,
              new ZkNodeProps(m),
              getSolrCloudManager(),
              zkStateReader);
        } else {
          overseerJobQueue.offer(m);
        }
      }
    } finally {
      MDCLoggingContext.clear();
    }
  }

  /**
   * Returns {@code true} if a message needs to be sent to overseer (or done in a distributed way)
   * to update state.json for the collection
   */
  static boolean updateStateDotJson(DocCollection coll, String replicaName) {
    if (coll == null) return true;
    if (!coll.isPerReplicaState()) return true;
    Replica r = coll.getReplica(replicaName);
    if (r == null) return true;
    Slice shard = coll.getSlice(r.shard);
    if (shard == null) return true; // very unlikely
    if (shard.getParent() != null) return true;
    for (Slice slice : coll.getSlices()) {
      if (Objects.equals(shard.getName(), slice.getParent())) return true;
    }
    return false;
  }

  public ZkShardTerms getShardTerms(String collection, String shardId) {
    return getCollectionTerms(collection).getShard(shardId);
  }

  private ZkCollectionTerms getCollectionTerms(String collection) {
    synchronized (collectionToTerms) {
      return collectionToTerms.computeIfAbsent(
          collection, col -> new ZkCollectionTerms(col, zkClient));
    }
  }

  public void clearZkCollectionTerms() {
    synchronized (collectionToTerms) {
      collectionToTerms.values().forEach(ZkCollectionTerms::close);
      collectionToTerms.clear();
    }
  }

  public void unregister(String coreName, CoreDescriptor cd) throws Exception {
    unregister(coreName, cd, true);
  }

  public void unregister(String coreName, CoreDescriptor cd, boolean removeCoreFromZk)
      throws Exception {
    final String coreNodeName = cd.getCloudDescriptor().getCoreNodeName();
    final String collection = cd.getCloudDescriptor().getCollectionName();
    getCollectionTerms(collection).remove(cd.getCloudDescriptor().getShardId(), cd);
    replicasMetTragicEvent.remove(collection + ":" + coreNodeName);

    if (StrUtils.isNullOrEmpty(collection)) {
      log.error("No collection was specified.");
      assert false : "No collection was specified [" + collection + "]";
      return;
    }
    final DocCollection docCollection =
        zkStateReader.getClusterState().getCollectionOrNull(collection);
    Replica replica = (docCollection == null) ? null : docCollection.getReplica(coreNodeName);

    if (replica == null || replica.getType().leaderEligible) {
      ElectionContext context = electionContexts.remove(new ContextKey(collection, coreNodeName));

      if (context != null) {
        context.cancelElection();
      }
    }
    CloudDescriptor cloudDescriptor = cd.getCloudDescriptor();
    if (removeCoreFromZk) {
      // extra handling for PRS, we need to write the PRS entries from this node directly,
      // as overseer does not and should not handle those entries
      if (docCollection != null && docCollection.isPerReplicaState() && coreNodeName != null) {
        if (log.isDebugEnabled()) {
          log.debug(
              "Unregistering core with coreNodeName {} of collection {} - deleting the PRS entries from ZK",
              coreNodeName,
              docCollection.getName());
        }
        PerReplicaStates perReplicaStates =
            PerReplicaStatesOps.fetch(
                docCollection.getZNode(), zkClient, docCollection.getPerReplicaStates());
        PerReplicaStatesOps.deleteReplica(coreNodeName, perReplicaStates)
            .persist(docCollection.getZNode(), zkClient);
      }
      MapWriter m =
          ew ->
              ew.put(Overseer.QUEUE_OPERATION, OverseerAction.DELETECORE.toLower())
                  .put(ZkStateReader.CORE_NAME_PROP, coreName)
                  .put(ZkStateReader.NODE_NAME_PROP, getNodeName())
                  .put(
                      ZkStateReader.BASE_URL_PROP,
                      zkStateReader.getBaseUrlForNodeName(getNodeName()))
                  .put(ZkStateReader.COLLECTION_PROP, cloudDescriptor.getCollectionName())
                  .put(ZkStateReader.CORE_NODE_NAME_PROP, coreNodeName);
      if (distributedClusterStateUpdater.isDistributedStateUpdate()) {
        distributedClusterStateUpdater.doSingleStateUpdate(
            DistributedClusterStateUpdater.MutatingCommand.SliceRemoveReplica,
            new ZkNodeProps(m),
            getSolrCloudManager(),
            zkStateReader);
      } else {
        overseerJobQueue.offer(m);
      }
    }
  }

  public ZkStateReader getZkStateReader() {
    return zkStateReader;
  }

  private void doGetShardIdAndNodeNameProcess(CoreDescriptor cd) {
    final String coreNodeName = cd.getCloudDescriptor().getCoreNodeName();

    if (coreNodeName != null) {
      waitForShardId(cd);
    } else {
      // if no explicit coreNodeName, we want to match by base url and core name
      waitForCoreNodeName(cd);
      waitForShardId(cd);
    }
  }

  private void waitForCoreNodeName(CoreDescriptor descriptor) {
    log.debug("waitForCoreNodeName >>> look for our core node name");
    try {
      DocCollection collection =
          zkStateReader.waitForState(
              descriptor.getCollectionName(),
              320L,
              TimeUnit.SECONDS,
              c ->
                  ClusterStateMutator.getAssignedCoreNodeName(
                          c, getNodeName(), descriptor.getName())
                      != null);
      // Read outside of the predicate to avoid multiple potential writes
      String name =
          ClusterStateMutator.getAssignedCoreNodeName(
              collection, getNodeName(), descriptor.getName());
      descriptor.getCloudDescriptor().setCoreNodeName(name);
    } catch (TimeoutException | InterruptedException e) {
      SolrZkClient.checkInterrupted(e);
      throw new SolrException(ErrorCode.SERVER_ERROR, "Failed waiting for collection state", e);
    }
    getCoreContainer().getCoresLocator().persist(getCoreContainer(), descriptor);
  }

  private void waitForShardId(final CoreDescriptor cd) {
    if (log.isDebugEnabled()) {
      log.debug("waiting to find shard id in clusterstate for {}", cd.getName());
    }
    try {
      DocCollection collection =
          zkStateReader.waitForState(
              cd.getCollectionName(),
              320,
              TimeUnit.SECONDS,
              c -> c != null && c.getShardId(getNodeName(), cd.getName()) != null);
      // Read outside of the predicate to avoid multiple potential writes
      cd.getCloudDescriptor().setShardId(collection.getShardId(getNodeName(), cd.getName()));
    } catch (TimeoutException | InterruptedException e) {
      SolrZkClient.checkInterrupted(e);
      throw new SolrException(
          ErrorCode.SERVER_ERROR, "Failed getting shard id for core: " + cd.getName(), e);
    }
  }

  public String getCoreNodeName(CoreDescriptor descriptor) {
    String coreNodeName = descriptor.getCloudDescriptor().getCoreNodeName();
    if (coreNodeName == null && !genericCoreNodeNames) {
      // it's the default
      return getNodeName() + "_" + descriptor.getName();
    }

    return coreNodeName;
  }

  public void preRegister(CoreDescriptor cd, boolean publishState) {

    String coreNodeName = getCoreNodeName(cd);

    // before becoming available, make sure we are not live and active
    // this also gets us our assigned shard id if it was not specified
    try {
      checkStateInZk(cd, null);

      CloudDescriptor cloudDesc = cd.getCloudDescriptor();

      // make sure the node name is set on the descriptor
      if (cloudDesc.getCoreNodeName() == null) {
        cloudDesc.setCoreNodeName(coreNodeName);
      }

      // publishState == false on startup
      if (publishState || isPublishAsDownOnStartup(cloudDesc)) {
        publish(cd, Replica.State.DOWN, false, true);
      }
      String collectionName = cd.getCloudDescriptor().getCollectionName();
      DocCollection collection =
          zkStateReader.getClusterState().getCollectionOrNull(collectionName);
      if (log.isDebugEnabled()) {
        log.debug(
            collection == null
                ? "Collection {} not visible yet, but flagging it so a watch is registered when it becomes visible"
                : "Registering watch for collection {}",
            collectionName);
      }
    } catch (KeeperException e) {
      log.error("", e);
      throw new ZooKeeperException(SolrException.ErrorCode.SERVER_ERROR, "", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.error("", e);
      throw new ZooKeeperException(SolrException.ErrorCode.SERVER_ERROR, "", e);
    } catch (NotInClusterStateException e) {
      // make the stack trace less verbose
      throw e;
    } catch (Exception e) {
      log.error("", e);
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "", e);
    }

    doGetShardIdAndNodeNameProcess(cd);
  }

  /**
   * On startup, the node already published all of its replicas as DOWN, we can skip publish the
   * replica as down
   *
   * @return Should publish the replica as down on startup
   */
  private boolean isPublishAsDownOnStartup(CloudDescriptor cloudDesc) {
    Replica replica =
        zkStateReader
            .getClusterState()
            .getCollection(cloudDesc.getCollectionName())
            .getSlice(cloudDesc.getShardId())
            .getReplica(cloudDesc.getCoreNodeName());
    return !replica.getNodeName().equals(getNodeName());
  }

  private void checkStateInZk(CoreDescriptor cd, Replica.State state)
      throws InterruptedException, NotInClusterStateException {
    CloudDescriptor cloudDesc = cd.getCloudDescriptor();
    String nodeName = cloudDesc.getCoreNodeName();
    if (nodeName == null) {
      throw new SolrException(ErrorCode.SERVER_ERROR, "No coreNodeName for " + cd);
    }
    final String coreNodeName = nodeName;

    if (cloudDesc.getShardId() == null) {
      throw new SolrException(ErrorCode.SERVER_ERROR, "No shard id for " + cd);
    }

    AtomicReference<String> errorMessage = new AtomicReference<>();
    try {
      zkStateReader.waitForState(
          cd.getCollectionName(),
          10,
          TimeUnit.SECONDS,
          (c) -> {
            if (c == null) return false;
            Slice slice = c.getSlice(cloudDesc.getShardId());
            if (slice == null) {
              errorMessage.set("Invalid shard: " + cloudDesc.getShardId());
              return false;
            }
            Replica replica = slice.getReplica(coreNodeName);
            if (replica == null) {
              errorMessage.set(
                  "coreNodeName "
                      + coreNodeName
                      + " does not exist in shard "
                      + cloudDesc.getShardId()
                      + ", ignore the exception if the replica was deleted");
              return false;
            }
            if (state != null && !state.equals(replica.getState())) {
              errorMessage.set(
                  "coreNodeName "
                      + coreNodeName
                      + " does not have the expected state "
                      + state
                      + ", found state was: "
                      + replica.getState());
              return false;
            }
            return true;
          });
    } catch (TimeoutException e) {
      String error = errorMessage.get();
      if (error == null)
        error =
            "coreNodeName "
                + coreNodeName
                + " does not exist in shard "
                + cloudDesc.getShardId()
                + ", ignore the exception if the replica was deleted";
      throw new NotInClusterStateException(ErrorCode.SERVER_ERROR, error);
    }
  }

  /** Attempts to cancel all leader elections. This method should be called on node shutdown. */
  public void tryCancelAllElections() {
    if (!zkClient.isConnected()) {
      log.warn("Skipping leader election node cleanup since we're disconnected from ZooKeeper.");
      return;
    }
    Collection<ElectionContext> values = electionContexts.values();
    synchronized (electionContexts) {
      values.forEach(
          context -> {
            try {
              context.cancelElection();
              context.close();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            } catch (KeeperException e) {
              log.warn("Error on cancelling elections of {}", context.leaderPath, e);
            }
          });
    }
  }

  private ZkCoreNodeProps waitForLeaderToSeeDownState(
      CoreDescriptor descriptor, final String coreZkNodeName) throws SessionExpiredException {
    // try not to wait too long here - if we are waiting too long, we should probably
    // move along and join the election

    CloudDescriptor cloudDesc = descriptor.getCloudDescriptor();
    String collection = cloudDesc.getCollectionName();
    String shard = cloudDesc.getShardId();
    ZkCoreNodeProps leaderProps = null;

    int retries = 2;
    for (int i = 0; i < retries; i++) {
      try {
        if (isClosed) {
          throw new SolrException(ErrorCode.SERVICE_UNAVAILABLE, "We have been closed");
        }

        // go straight to zk, not the cloud state - we want current info
        leaderProps = getLeaderProps(collection, shard, 5000);
        break;
      } catch (SessionExpiredException e) {
        throw e;
      } catch (Exception e) {
        log.info("Did not find the leader in Zookeeper", e);
        try {
          Thread.sleep(2000);
        } catch (InterruptedException e1) {
          Thread.currentThread().interrupt();
        }
        if (i == retries - 1) {
          throw new SolrException(
              ErrorCode.SERVER_ERROR, "There was a problem finding the leader in zk");
        }
      }
    }

    String leaderBaseUrl = leaderProps.getBaseUrl();
    String leaderCoreName = leaderProps.getCoreName();

    String myCoreNodeName = cloudDesc.getCoreNodeName();
    String myCoreName = descriptor.getName();
    String ourUrl = ZkCoreNodeProps.getCoreUrl(getBaseUrl(), myCoreName);

    boolean isLeader = leaderProps.getCoreUrl().equals(ourUrl);
    if (!isLeader && !SKIP_AUTO_RECOVERY) {
      if (!getShardTerms(collection, shard).canBecomeLeader(myCoreNodeName)) {
        log.debug(
            "Term of replica {} is already less than leader, so not waiting for leader to see down state."
                + " Instead, make sure we can see the down state in Zookeeper.",
            myCoreNodeName);
        try {
          checkStateInZk(descriptor, Replica.State.DOWN);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      } else {
        if (log.isInfoEnabled()) {
          log.info(
              "replica={} is making a best effort attempt to wait for leader={} to see it's DOWN state.",
              myCoreNodeName,
              leaderProps.getCoreUrl());
        }

        // short timeouts, we may be in a storm and this is best effort, and maybe we should be the
        // leader now
        try (SolrClient client =
            new Builder(leaderBaseUrl)
                .withConnectionTimeout(8000, TimeUnit.MILLISECONDS)
                .withSocketTimeout(30000, TimeUnit.MILLISECONDS)
                .build()) {
          WaitForState prepCmd = new WaitForState();
          prepCmd.setCoreName(leaderCoreName);
          prepCmd.setNodeName(getNodeName());
          prepCmd.setCoreNodeName(coreZkNodeName);
          prepCmd.setState(Replica.State.DOWN);

          // lets give it another chance, but without taking too long
          retries = 3;
          for (int i = 0; i < retries; i++) {
            if (isClosed) {
              throw new SolrException(ErrorCode.SERVICE_UNAVAILABLE, "We have been closed");
            }
            try {
              client.request(prepCmd);
              break;
            } catch (Exception e) {

              // if the core container is shutdown, don't wait
              if (cc.isShutDown()) {
                throw new SolrException(
                    ErrorCode.SERVICE_UNAVAILABLE, "Core container is shutdown.");
              }

              Throwable rootCause = SolrException.getRootCause(e);
              if (rootCause instanceof IOException) {
                // if there was a communication error talking to the leader, see if the leader is
                // even alive
                if (!zkStateReader.getClusterState().liveNodesContain(leaderProps.getNodeName())) {
                  throw new SolrException(
                      ErrorCode.SERVICE_UNAVAILABLE,
                      "Node "
                          + leaderProps.getNodeName()
                          + " hosting leader for "
                          + shard
                          + " in "
                          + collection
                          + " is not live!");
                }
              }

              log.error("There was a problem making a request to the leader", e);
              try {
                Thread.sleep(2000);
              } catch (InterruptedException e1) {
                Thread.currentThread().interrupt();
              }
              if (i == retries - 1) {
                throw new SolrException(
                    ErrorCode.SERVER_ERROR, "There was a problem making a request to the leader");
              }
            }
          }
        } catch (IOException e) {
          log.error("Error closing HttpSolrClient", e);
        }
      }
    }
    return leaderProps;
  }

  public static void linkConfSet(SolrZkClient zkClient, String collection, String confSetName)
      throws KeeperException, InterruptedException {
    String path = ZkStateReader.COLLECTIONS_ZKNODE + "/" + collection;
    log.debug("Load collection config from:{}", path);
    byte[] data;
    try {
      data = zkClient.getData(path, null, null, true);
    } catch (NoNodeException e) {
      // if there is no node, we will try and create it
      // first try to make in case we are pre configuring
      ZkNodeProps props = new ZkNodeProps(CONFIGNAME_PROP, confSetName);
      try {

        zkClient.makePath(path, Utils.toJSON(props), CreateMode.PERSISTENT, null, true);
      } catch (KeeperException e2) {
        // it's okay if the node already exists
        if (e2.code() != KeeperException.Code.NODEEXISTS) {
          throw e;
        }
        // if we fail creating, setdata
        // TODO: we should consider using version
        zkClient.setData(path, Utils.toJSON(props), true);
      }
      return;
    }
    // we found existing data, let's update it
    ZkNodeProps props = null;
    if (data != null) {
      props = ZkNodeProps.load(data);
      Map<String, Object> newProps = new HashMap<>(props.getProperties());
      newProps.put(CONFIGNAME_PROP, confSetName);
      props = new ZkNodeProps(newProps);
    } else {
      props = new ZkNodeProps(CONFIGNAME_PROP, confSetName);
    }

    // TODO: we should consider using version
    zkClient.setData(path, Utils.toJSON(props), true);
  }

  public ZkDistributedQueue getOverseerJobQueue() {
    if (distributedClusterStateUpdater.isDistributedStateUpdate()) {
      throw new IllegalStateException(
          "Cluster is configured with distributed state update, not expecting the queue to be retrieved");
    }
    return overseerJobQueue;
  }

  public OverseerTaskQueue getOverseerCollectionQueue() {
    return overseerCollectionQueue;
  }

  public OverseerTaskQueue getOverseerConfigSetQueue() {
    return overseerConfigSetQueue;
  }

  public DistributedMap getOverseerRunningMap() {
    return overseerRunningMap;
  }

  public DistributedMap getOverseerCompletedMap() {
    return overseerCompletedMap;
  }

  public DistributedMap getOverseerFailureMap() {
    return overseerFailureMap;
  }

  /**
   * When an operation needs to be performed in an asynchronous mode, the asyncId needs to be
   * claimed by calling this method to make sure it's not duplicate (hasn't been claimed by other
   * request). If this method returns true, the asyncId in the parameter has been reserved for the
   * operation, meaning that no other thread/operation can claim it. If for whatever reason, the
   * operation is not scheduled, the asuncId needs to be cleared using {@link
   * #clearAsyncId(String)}. If this method returns false, no reservation has been made, and this
   * asyncId can't be used, since it's being used by another operation (currently or in the past)
   *
   * @param asyncId A string representing the asyncId of an operation. Can't be null.
   * @return True if the reservation succeeds. False if this ID is already in use.
   */
  public boolean claimAsyncId(String asyncId) throws KeeperException {
    try {
      return asyncIdsMap.putIfAbsent(asyncId, new byte[0]);
    } catch (InterruptedException e) {
      log.error("Could not claim asyncId={}", asyncId, e);
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    }
  }

  /**
   * Clears an asyncId previously claimed by calling {@link #claimAsyncId(String)}
   *
   * @param asyncId A string representing the asyncId of an operation. Can't be null.
   * @return True if the asyncId existed and was cleared. False if the asyncId didn't exist before.
   */
  public boolean clearAsyncId(String asyncId) throws KeeperException {
    try {
      return asyncIdsMap.remove(asyncId);
    } catch (InterruptedException e) {
      log.error("Could not release asyncId={}", asyncId, e);
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    }
  }

  public Overseer getOverseer() {
    return overseer;
  }

  public LeaderElector getOverseerElector() {
    return overseerElector;
  }

  /**
   * Returns the nodeName that should be used based on the specified properties.
   *
   * @param hostName - must not be null or the empty string
   * @param hostPort - must consist only of digits, must not be null or the empty string
   * @lucene.experimental
   * @see ZkStateReader#getBaseUrlForNodeName
   */
  static String generateNodeName(final String hostName, final String hostPort) {
    return hostName + ':' + hostPort + '_' + "solr";
  }

  public void rejoinOverseerElection(String electionNode, boolean joinAtHead) {
    try {
      final ElectionContext context = overseerElector.getContext();
      if (electionNode != null) {
        // Check whether we came to this node by mistake
        if (context != null
            && context.leaderSeqPath != null
            && !context.leaderSeqPath.endsWith(electionNode)) {
          log.warn(
              "Asked to rejoin with wrong election node : {}, current node is {}",
              electionNode,
              context.leaderSeqPath);
          // however delete it . This is possible when the last attempt at deleting the election
          // node failed.
          if (electionNode.startsWith(getNodeName())) {
            try {
              zkClient.delete(
                  Overseer.OVERSEER_ELECT + LeaderElector.ELECTION_NODE + "/" + electionNode,
                  -1,
                  true);
            } catch (NoNodeException e) {
              // no problem
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            } catch (Exception e) {
              log.warn("Old election node exists , could not be removed ", e);
            }
          }
        } else { // We're in the right place, now attempt to rejoin
          overseerElector.retryElection(
              new OverseerElectionContext(zkClient, overseer, getNodeName()), joinAtHead);
          return;
        }
      } else {
        overseerElector.retryElection(context, joinAtHead);
      }
    } catch (Exception e) {
      throw new SolrException(ErrorCode.SERVER_ERROR, "Unable to rejoin election", e);
    }
  }

  public void rejoinShardLeaderElection(SolrParams params) {

    String collectionName = params.get(COLLECTION_PROP);
    String coreNodeName = params.get(CORE_NODE_NAME_PROP);
    String coreName = params.get(CORE_NAME_PROP);
    boolean rejoinAtHead = params.getBool(REJOIN_AT_HEAD_PROP, false);

    try {
      MDCLoggingContext.setCoreDescriptor(cc, cc.getCoreDescriptor(coreName));

      log.info("Rejoin the shard leader election.");

      ContextKey contextKey = new ContextKey(collectionName, coreNodeName);

      ElectionContext prevContext = electionContexts.get(contextKey);

      String baseUrl = zkStateReader.getBaseUrlForNodeName(getNodeName());
      String ourUrl = ZkCoreNodeProps.getCoreUrl(baseUrl, coreName);

      LeaderElector elect = ((ShardLeaderElectionContextBase) prevContext).getLeaderElector();

      elect.retryElection(prevContext, rejoinAtHead);

      try (SolrCore core = cc.getCore(coreName)) {
        Replica.Type replicaType = core.getCoreDescriptor().getCloudDescriptor().getReplicaType();
        if (replicaType.replicateFromLeader) {
          String leaderUrl =
              getLeader(
                  core.getCoreDescriptor().getCloudDescriptor(), cloudConfig.getLeaderVoteWait());
          if (!leaderUrl.equals(ourUrl)) {
            // restart the replication thread to ensure the replication is running in each new
            // replica especially if previous role is "leader" (i.e., no replication thread)
            stopReplicationFromLeader(coreName);
            startReplicationFromLeader(coreName, replicaType.requireTransactionLog);
          }
        }
      }
    } catch (Exception e) {
      throw new SolrException(ErrorCode.SERVER_ERROR, "Unable to rejoin election", e);
    } finally {
      MDCLoggingContext.clear();
    }
  }

  public void checkOverseerDesignate() {
    try {
      byte[] data = zkClient.getData(ZkStateReader.ROLES, null, new Stat(), true);
      if (data == null) return;
      Map<?, ?> roles = (Map<?, ?>) Utils.fromJSON(data);
      if (roles == null) return;
      List<?> nodeList = (List<?>) roles.get("overseer");
      if (nodeList == null) return;
      if (nodeList.contains(getNodeName())) {
        setPreferredOverseer();
      }
    } catch (NoNodeException nne) {
      return;
    } catch (Exception e) {
      log.warn("could not read the overseer designate ", e);
    }
  }

  public void setPreferredOverseer() throws KeeperException, InterruptedException {
    MapWriter props =
        ew ->
            ew.put(Overseer.QUEUE_OPERATION, ADDROLE.toString().toLowerCase(Locale.ROOT))
                .put(getNodeName(), getNodeName())
                .put("role", "overseer")
                .put("persist", "false");
    log.warn(
        "Going to add role {}. It is deprecated to use ADDROLE and consider using Node Roles instead.",
        props.jsonStr());
    getOverseerCollectionQueue().offer(props);
  }

  public CoreContainer getCoreContainer() {
    return cc;
  }

  public void throwErrorIfReplicaReplaced(CoreDescriptor desc) {
    ClusterState clusterState = getZkStateReader().getClusterState();
    if (clusterState != null) {
      DocCollection collection =
          clusterState.getCollectionOrNull(desc.getCloudDescriptor().getCollectionName());
      if (collection != null) {
        CloudUtil.checkSharedFSFailoverReplaced(cc, desc);
      }
    }
  }

  /**
   * Add a listener to be notified once there is a new session created after a ZooKeeper session
   * expiration occurs; in most cases, listeners will be components that have watchers that need to
   * be re-created.
   */
  public void addOnReconnectListener(OnReconnect listener) {
    if (listener != null) {
      synchronized (reconnectListeners) {
        reconnectListeners.add(listener);
        log.debug("Added new OnReconnect listener {}", listener);
      }
    }
  }

  /**
   * Removed a previously registered OnReconnect listener, such as when a core is removed or
   * reloaded.
   */
  public void removeOnReconnectListener(OnReconnect listener) {
    if (listener != null) {
      boolean wasRemoved;
      synchronized (reconnectListeners) {
        wasRemoved = reconnectListeners.remove(listener);
      }
      if (wasRemoved) {
        log.debug("Removed OnReconnect listener {}", listener);
      } else {
        log.warn(
            "Was asked to remove OnReconnect listener {}, but remove operation "
                + "did not find it in the list of registered listeners.",
            listener);
      }
    }
  }

  @SuppressWarnings({"unchecked"})
  Set<OnReconnect> getCurrentOnReconnectListeners() {
    HashSet<OnReconnect> clonedListeners;
    synchronized (reconnectListeners) {
      clonedListeners = (HashSet<OnReconnect>) reconnectListeners.clone();
    }
    return clonedListeners;
  }

  /**
   * Persists a config file to ZooKeeper using optimistic concurrency.
   *
   * @return true on success
   */
  public static int persistConfigResourceToZooKeeper(
      ZkSolrResourceLoader zkLoader,
      int znodeVersion,
      String resourceName,
      byte[] content,
      boolean createIfNotExists) {
    int latestVersion = znodeVersion;
    final ZkController zkController = zkLoader.getZkController();
    final SolrZkClient zkClient = zkController.getZkClient();
    final String resourceLocation = zkLoader.getConfigSetZkPath() + "/" + resourceName;
    String errMsg = "Failed to persist resource at {0} - old {1}";
    try {
      try {
        Stat stat = zkClient.setData(resourceLocation, content, znodeVersion, true);
        // if the set succeeded, it should have incremented the version by one always
        latestVersion = stat.getVersion();
        log.info("Persisted config data to node {} ", resourceLocation);
        touchConfDir(zkLoader);
      } catch (NoNodeException e) {
        if (createIfNotExists) {
          try {
            zkClient.makePath(resourceLocation, content, CreateMode.PERSISTENT, true);
            latestVersion = 0; // just created so version must be zero
            touchConfDir(zkLoader);
          } catch (KeeperException.NodeExistsException nee) {
            try {
              Stat stat = zkClient.exists(resourceLocation, null, true);
              if (log.isDebugEnabled()) {
                log.debug(
                    "failed to set data version in zk is {} and expected version is {} ",
                    stat.getVersion(),
                    znodeVersion);
              }
            } catch (Exception e1) {
              log.warn("could not get stat");
            }

            if (log.isInfoEnabled()) {
              log.info(StrUtils.formatString(errMsg, resourceLocation, znodeVersion));
            }
            throw new ResourceModifiedInZkException(
                ErrorCode.CONFLICT,
                StrUtils.formatString(errMsg, resourceLocation, znodeVersion) + ", retry.");
          }
        }
      }

    } catch (KeeperException.BadVersionException bve) {
      try {
        zkClient.exists(resourceLocation, null, true);
      } catch (Exception e) {
        log.error("Exception during ZooKeeper node checking ", e);
      }
      if (log.isInfoEnabled()) {
        log.info(
            StrUtils.formatString(
                "%s zkVersion= %d %s %d", errMsg, resourceLocation, znodeVersion));
      }
      throw new ResourceModifiedInZkException(
          ErrorCode.CONFLICT,
          StrUtils.formatString(errMsg, resourceLocation, znodeVersion) + ", retry.");
    } catch (ResourceModifiedInZkException e) {
      throw e;
    } catch (Exception e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt(); // Restore the interrupted status
      }
      final String msg = "Error persisting resource at " + resourceLocation;
      log.error(msg, e);
      throw new SolrException(ErrorCode.SERVER_ERROR, msg, e);
    }
    return latestVersion;
  }

  public static void touchConfDir(ZkSolrResourceLoader zkLoader) {
    SolrZkClient zkClient = zkLoader.getZkController().getZkClient();
    String configSetZkPath = zkLoader.getConfigSetZkPath();
    try {
      // Ensure that version gets updated by replacing data with itself.
      // If there is no existing data then set it to byte[] {0}.
      // This should trigger any watchers if necessary as well.
      zkClient.atomicUpdate(configSetZkPath, bytes -> bytes == null ? TOUCHED_ZNODE_DATA : bytes);
    } catch (Exception e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt(); // Restore the interrupted status
      }
      final String msg = "Error 'touching' conf location " + configSetZkPath;
      log.error(msg, e);
      throw new SolrException(ErrorCode.SERVER_ERROR, msg, e);
    }
  }

  public static class ResourceModifiedInZkException extends SolrException {
    public ResourceModifiedInZkException(ErrorCode code, String msg) {
      super(code, msg);
    }
  }

  private void unregisterConfListener(String confDir, Runnable listener) {
    synchronized (confDirectoryListeners) {
      final Set<Runnable> listeners = confDirectoryListeners.get(confDir);
      if (listeners == null) {
        log.warn(
            "{} has no more registered listeners, but a live one attempted to unregister!",
            confDir);
        return;
      }
      if (listeners.remove(listener)) {
        log.debug("removed listener for config directory [{}]", confDir);
      }
      if (listeners.isEmpty()) {
        // no more listeners for this confDir, remove it from the map
        log.debug("No more listeners for config directory [{}]", confDir);
        confDirectoryListeners.remove(confDir);
      }
    }
  }

  /**
   * This will give a callback to the listener whenever a child is modified in the conf directory.
   * It is the responsibility of the listener to check if the individual item of interest has been
   * modified. When the last core which was interested in this conf directory is gone the listeners
   * will be removed automatically.
   */
  public void registerConfListenerForCore(
      final String confDir, SolrCore core, final Runnable listener) {
    if (listener == null) {
      throw new NullPointerException("listener cannot be null");
    }
    synchronized (confDirectoryListeners) {
      final Set<Runnable> confDirListeners = getConfDirListeners(confDir);
      confDirListeners.add(listener);
      core.addCloseHook(
          new CloseHook() {
            @Override
            public void preClose(SolrCore core) {
              unregisterConfListener(confDir, listener);
            }
          });
    }
  }

  // this method is called in a protected confDirListeners block
  private Set<Runnable> getConfDirListeners(final String confDir) {
    assert Thread.holdsLock(confDirectoryListeners) : "confDirListeners lock not held by thread";
    Set<Runnable> confDirListeners = confDirectoryListeners.get(confDir);
    if (confDirListeners == null) {
      log.debug("watch zkdir {}", confDir);
      confDirListeners = new HashSet<>();
      confDirectoryListeners.put(confDir, confDirListeners);
      setConfWatcher(confDir, new WatcherImpl(confDir), null);
    }
    return confDirListeners;
  }

  private final Map<String, Set<Runnable>> confDirectoryListeners = new HashMap<>();

  private class WatcherImpl implements Watcher {
    private final String zkDir;

    private WatcherImpl(String dir) {
      this.zkDir = dir;
    }

    @Override
    public void process(WatchedEvent event) {
      // session events are not change events, and do not remove the watcher
      if (Event.EventType.None.equals(event.getType())) {
        return;
      }

      Stat stat = null;
      try {
        stat = zkClient.exists(zkDir, null, true);
      } catch (KeeperException e) {
        // ignore , it is not a big deal
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }

      boolean resetWatcher = false;
      try {
        resetWatcher = fireEventListeners(zkDir);
      } finally {
        if (Event.EventType.None.equals(event.getType())) {
          log.debug("A node got unwatched for {}", zkDir);
        } else {
          if (resetWatcher) setConfWatcher(zkDir, this, stat);
          else log.debug("A node got unwatched for {}", zkDir);
        }
      }
    }
  }

  private boolean fireEventListeners(String zkDir) {
    if (isClosed || cc.isShutDown()) {
      return false;
    }
    synchronized (confDirectoryListeners) {
      // if this is not among directories to be watched then don't set the watcher anymore
      if (!confDirectoryListeners.containsKey(zkDir)) {
        log.debug("Watcher on {} is removed ", zkDir);
        return false;
      }
      final Set<Runnable> listeners = confDirectoryListeners.get(zkDir);
      if (listeners != null && !listeners.isEmpty()) {
        final Set<Runnable> listenersCopy = new HashSet<>(listeners);
        // run these in a separate thread because this can be long-running
        Runnable work =
            () -> {
              log.debug("Running listeners for {}", zkDir);
              for (final Runnable listener : listenersCopy) {
                try {
                  listener.run();
                } catch (RuntimeException e) {
                  log.warn("listener throws error", e);
                }
              }
            };
        cc.getCoreZkRegisterExecutorService().execute(work);
      }
    }
    return true;
  }

  private void setConfWatcher(String zkDir, Watcher watcher, Stat stat) {
    try {
      Stat newStat = zkClient.exists(zkDir, watcher, true);
      if (stat != null && newStat.getVersion() > stat.getVersion()) {
        // a race condition where we missed an event fired
        // so fire the event listeners
        fireEventListeners(zkDir);
      }
    } catch (KeeperException e) {
      log.error("failed to set watcher for conf dir {} ", zkDir);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.error("failed to set watcher for conf dir {} ", zkDir);
    }
  }

  public OnReconnect getConfigDirListener() {
    return () -> {
      synchronized (confDirectoryListeners) {
        for (String s : confDirectoryListeners.keySet()) {
          setConfWatcher(s, new WatcherImpl(s), null);
          fireEventListeners(s);
        }
      }
    };
  }

  /**
   * @lucene.internal
   */
  class UnloadCoreOnDeletedWatcher implements DocCollectionWatcher {
    String coreNodeName;
    String shard;
    String coreName;

    public UnloadCoreOnDeletedWatcher(String coreNodeName, String shard, String coreName) {
      this.coreNodeName = coreNodeName;
      this.shard = shard;
      this.coreName = coreName;
    }

    @Override
    // synchronized due to SOLR-11535
    // TODO: can we remove `synchronized`, now that SOLR-11535 is fixed?
    public synchronized boolean onStateChanged(DocCollection collectionState) {
      if (getCoreContainer().getCoreDescriptor(coreName) == null) return true;

      boolean replicaRemoved = getReplicaOrNull(collectionState, shard, coreNodeName) == null;
      if (replicaRemoved) {
        try {
          log.info("Replica {} removed from clusterstate, remove it.", coreName);
          getCoreContainer().unload(coreName, true, true, true);
        } catch (SolrException e) {
          if (!e.getMessage().contains("Cannot unload non-existent core")) {
            // no need to log if the core was already unloaded
            log.warn("Failed to unregister core:{}", coreName, e);
          }
        } catch (Exception e) {
          log.warn("Failed to unregister core:{}", coreName, e);
        }
      }
      return replicaRemoved;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof UnloadCoreOnDeletedWatcher that)) return false;
      return Objects.equals(coreNodeName, that.coreNodeName)
          && Objects.equals(shard, that.shard)
          && Objects.equals(coreName, that.coreName);
    }

    @Override
    public int hashCode() {

      return Objects.hash(coreNodeName, shard, coreName);
    }
  }

  /** Thrown during pre register process if the replica is not present in clusterstate */
  public static class NotInClusterStateException extends SolrException {
    public NotInClusterStateException(ErrorCode code, String msg) {
      super(code, msg);
    }
  }

  public boolean checkIfCoreNodeNameAlreadyExists(CoreDescriptor dcore) {
    DocCollection collection =
        zkStateReader.getClusterState().getCollectionOrNull(dcore.getCollectionName());
    if (collection != null) {
      Collection<Slice> slices = collection.getSlices();

      for (Slice slice : slices) {
        Replica r = slice.getReplica(dcore.getCloudDescriptor().getCoreNodeName());
        if (r != null) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Best effort to set DOWN state for all replicas on node.
   *
   * @param nodeName to operate on
   * @return the names of the collections that have replicas on the given node
   */
  public Collection<String> publishNodeAsDown(String nodeName) {
    log.info("Publish node={} as DOWN", nodeName);

    ClusterState clusterState = getClusterState();
    Map<String, List<Replica>> replicasPerCollectionOnNode =
        clusterState.getReplicaNamesPerCollectionOnNode(nodeName);
    if (distributedClusterStateUpdater.isDistributedStateUpdate()) {
      // Note that with the current implementation, when distributed cluster state updates are
      // enabled, we mark the node down synchronously from this thread, whereas the Overseer cluster
      // state update frees this thread right away and the Overseer will async mark the node down
      // but updating all affected collections. If this is an issue (i.e. takes too long), then the
      // call below should be executed from another thread so that the calling thread can
      // immediately return.
      distributedClusterStateUpdater.executeNodeDownStateUpdate(nodeName, zkStateReader);
    } else {
      try {
        for (String collName : replicasPerCollectionOnNode.keySet()) {
          DocCollection coll;
          if (collName != null
              && (coll = zkStateReader.getCollection(collName)) != null
              && coll.isPerReplicaState()) {
            PerReplicaStatesOps.downReplicas(
                    replicasPerCollectionOnNode.get(collName).stream()
                        .map(Replica::getName)
                        .collect(Collectors.toList()),
                    PerReplicaStatesOps.fetch(
                        coll.getZNode(), zkClient, coll.getPerReplicaStates()))
                .persist(coll.getZNode(), zkClient);
          }
        }

        // We always send a down node event to overseer to be safe, but overseer will not need to do
        // anything for PRS collections
        overseer
            .getStateUpdateQueue()
            .offer(
                m ->
                    m.put(Overseer.QUEUE_OPERATION, OverseerAction.DOWNNODE.toLower())
                        .put(ZkStateReader.NODE_NAME_PROP, nodeName));
      } catch (IllegalStateException e) {
        log.info(
            "Not publishing node as DOWN because a resource required to do so is already closed.");
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        log.debug("Publish node as down was interrupted.");
      } catch (KeeperException e) {
        log.warn("Could not publish node as down: ", e);
      }
    }
    return replicasPerCollectionOnNode.keySet();
  }

  /**
   * Ensures that a searcher is registered for the given core and if not, waits until one is
   * registered
   */
  private static void ensureRegisteredSearcher(SolrCore core) throws InterruptedException {
    if (!core.getSolrConfig().useColdSearcher) {
      RefCounted<SolrIndexSearcher> registeredSearcher = core.getRegisteredSearcher();
      if (registeredSearcher != null) {
        if (log.isDebugEnabled()) {
          log.debug("Found a registered searcher: {} for core: {}", registeredSearcher.get(), core);
        }
        registeredSearcher.decref();
      } else {
        @SuppressWarnings("unchecked")
        Future<Void>[] waitSearcher = (Future<Void>[]) Array.newInstance(Future.class, 1);
        if (log.isInfoEnabled()) {
          log.info(
              "No registered searcher found for core: {}, waiting until a searcher is registered before publishing as active",
              core.getName());
        }
        final RTimer timer = new RTimer();
        RefCounted<SolrIndexSearcher> searcher = null;
        try {
          searcher = core.getSearcher(false, true, waitSearcher, true);
          boolean success = true;
          if (waitSearcher[0] != null) {
            if (log.isDebugEnabled()) {
              log.debug(
                  "Waiting for first searcher of core {}, id: {} to be registered",
                  core.getName(),
                  core);
            }
            try {
              waitSearcher[0].get();
            } catch (ExecutionException e) {
              log.warn(
                  "Wait for a searcher to be registered for core {}, id: {} failed due to: {}",
                  core.getName(),
                  core,
                  e,
                  e);
              success = false;
            }
          }
          if (success) {
            if (searcher == null) {
              // should never happen
              if (log.isDebugEnabled()) {
                log.debug(
                    "Did not find a searcher even after the future callback for core: {}, id: {}!!!",
                    core.getName(),
                    core);
              }
            } else {
              if (log.isInfoEnabled()) {
                log.info(
                    "Found a registered searcher: {}, took: {} ms for core: {}, id: {}",
                    searcher.get(),
                    timer.getTime(),
                    core.getName(),
                    core);
              }
            }
          }
        } finally {
          if (searcher != null) {
            searcher.decref();
          }
        }
      }
      RefCounted<SolrIndexSearcher> newestSearcher = core.getNewestSearcher(false);
      if (newestSearcher != null) {
        if (log.isDebugEnabled()) {
          log.debug(
              "Found newest searcher: {} for core: {}, id: {}",
              newestSearcher.get(),
              core.getName(),
              core);
        }
        newestSearcher.decref();
      }
    }
  }
}
