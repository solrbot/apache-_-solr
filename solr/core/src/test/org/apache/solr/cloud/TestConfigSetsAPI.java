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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.solr.common.params.CommonParams.NAME;
import static org.apache.solr.core.ConfigSetProperties.DEFAULT_FILENAME;
import static org.hamcrest.CoreMatchers.containsString;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.invoke.MethodHandles;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Principal;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.script.ScriptEngineManager;
import org.apache.commons.io.file.PathUtils;
import org.apache.http.HttpEntity;
import org.apache.http.auth.BasicUserPrincipal;
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.message.BasicHeader;
import org.apache.http.util.EntityUtils;
import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.api.AnnotatedApi;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrRequest.METHOD;
import org.apache.solr.client.solrj.SolrRequest.SolrRequestType;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.CloudLegacySolrClient;
import org.apache.solr.client.solrj.impl.CloudSolrClient;
import org.apache.solr.client.solrj.request.ConfigSetAdminRequest;
import org.apache.solr.client.solrj.request.ConfigSetAdminRequest.Create;
import org.apache.solr.client.solrj.request.ConfigSetAdminRequest.Delete;
import org.apache.solr.client.solrj.request.ConfigSetAdminRequest.Upload;
import org.apache.solr.client.solrj.request.GenericSolrRequest;
import org.apache.solr.client.solrj.request.schema.SchemaRequest;
import org.apache.solr.client.solrj.response.CollectionAdminResponse;
import org.apache.solr.client.solrj.response.ConfigSetAdminResponse;
import org.apache.solr.client.solrj.response.schema.SchemaResponse;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.cloud.SolrZkClient;
import org.apache.solr.common.cloud.ZkMaintenanceUtils;
import org.apache.solr.common.params.CollectionParams.CollectionAction;
import org.apache.solr.common.params.ConfigSetParams;
import org.apache.solr.common.params.ConfigSetParams.ConfigSetAction;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.Utils;
import org.apache.solr.common.util.ValidatingJsonMap;
import org.apache.solr.core.ConfigSetProperties;
import org.apache.solr.core.ConfigSetService;
import org.apache.solr.core.TestSolrConfigHandler;
import org.apache.solr.handler.admin.api.ModifyBasicAuthConfigAPI;
import org.apache.solr.security.AuthorizationContext;
import org.apache.solr.security.AuthorizationPlugin;
import org.apache.solr.security.AuthorizationResponse;
import org.apache.solr.security.BasicAuthPlugin;
import org.apache.solr.servlet.SolrDispatchFilter;
import org.apache.solr.util.ExternalPaths;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.data.Stat;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import org.noggit.JSONParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Simple ConfigSets API tests on user errors and simple success cases. */
public class TestConfigSetsAPI extends SolrCloudTestCase {

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  @BeforeClass
  public static void setUpClass() throws Exception {
    System.setProperty("managed.schema.mutable", "true");
    configureCluster(1).withSecurityJson(getSecurityJson()).configure();
  }

  @AfterClass
  public static void tearDownClass() {
    System.clearProperty("managed.schema.mutable");
  }

  private static ConfigSetService getConfigSetService() {
    return cluster.getOpenOverseer().getCoreContainer().getConfigSetService();
  }

  @Override
  @After
  public void tearDown() throws Exception {
    cluster.deleteAllCollections();
    cluster.deleteAllConfigSets();
    super.tearDown();
  }

  @Test
  public void testCreateErrors() throws Exception {
    final String baseUrl = cluster.getJettySolrRunners().get(0).getBaseUrl().toString();
    try (final SolrClient solrClient = getHttpSolrClient(baseUrl)) {
      getConfigSetService().uploadConfig("configSet", configset("configset-2"));

      // no action
      CreateNoErrorChecking createNoAction = new CreateNoErrorChecking();
      createNoAction.setAction(null);
      verifyException(solrClient, createNoAction, "action");

      // no ConfigSet name
      CreateNoErrorChecking create = new CreateNoErrorChecking();
      verifyException(solrClient, create, NAME);

      // set ConfigSet
      create.setConfigSetName("configSetName");

      // ConfigSet already exists
      Create alreadyExists = new Create();
      alreadyExists.setConfigSetName("configSet").setBaseConfigSetName("baseConfigSet");
      verifyException(solrClient, alreadyExists, "ConfigSet already exists");

      // Base ConfigSet does not exist
      Create baseConfigNoExists = new Create();
      baseConfigNoExists.setConfigSetName("newConfigSet").setBaseConfigSetName("baseConfigSet");
      verifyException(solrClient, baseConfigNoExists, "Base ConfigSet does not exist");
    }
  }

  @Test
  public void testCreate() throws Exception {
    // no old, no new
    verifyCreate(null, "configSet1", null, null, "solr");

    // no old, new
    verifyCreate(
        "baseConfigSet2",
        "configSet2",
        null,
        Map.of("immutable", "true", "key1", "value1"),
        "solr");

    // old, no new
    verifyCreate(
        "baseConfigSet3",
        "configSet3",
        Map.of("immutable", "false", "key2", "value2"),
        null,
        "solr");

    // old, new
    verifyCreate(
        "baseConfigSet4",
        "configSet4",
        Map.of("immutable", "true", "onlyOld", "onlyOldValue"),
        Map.of("immutable", "false", "onlyNew", "onlyNewValue"),
        "solr");
  }

  private void setupBaseConfigSet(String baseConfigSetName, Map<String, String> oldProps)
      throws Exception {
    final Path configDir = getFile("solr").resolve("configsets/configset-2/conf");
    final Path tmpConfigDir = createTempDir();
    PathUtils.copyDirectory(configDir, tmpConfigDir);
    if (oldProps != null) {
      Files.writeString(
          tmpConfigDir.resolve(ConfigSetProperties.DEFAULT_FILENAME),
          getConfigSetProps(oldProps),
          UTF_8);
    }
    getConfigSetService().uploadConfig(baseConfigSetName, tmpConfigDir);
  }

  private void verifyCreate(
      String baseConfigSetName,
      String configSetName,
      Map<String, String> oldProps,
      Map<String, String> newProps,
      String username)
      throws Exception {
    final String baseUrl = cluster.getJettySolrRunners().get(0).getBaseUrl().toString();
    try (final SolrClient solrClient = getHttpSolrClient(baseUrl)) {
      setupBaseConfigSet(baseConfigSetName, oldProps);

      try (SolrZkClient zkClient =
          new SolrZkClient.Builder()
              .withUrl(cluster.getZkServer().getZkAddress())
              .withTimeout(AbstractZkTestCase.TIMEOUT, TimeUnit.MILLISECONDS)
              .withConnTimeOut(AbstractZkTestCase.TIMEOUT, TimeUnit.MILLISECONDS)
              .build()) {
        assertFalse(getConfigSetService().checkConfigExists(configSetName));

        ConfigSetAdminResponse response =
            createConfigSet(baseConfigSetName, configSetName, newProps, solrClient, username);
        assertNotNull(response.getResponse());
        assertTrue(getConfigSetService().checkConfigExists(configSetName));

        verifyProperties(configSetName, oldProps, newProps, zkClient);
      }
    }
  }

  private ConfigSetAdminResponse createConfigSet(
      String baseConfigSetName,
      String configSetName,
      Map<String, String> newProps,
      SolrClient solrClient,
      String username)
      throws SolrServerException, IOException {
    Create create = new Create();
    create.setBaseConfigSetName(baseConfigSetName).setConfigSetName(configSetName);
    if (newProps != null) {
      Properties p = new Properties();
      p.putAll(newProps);
      create.setNewConfigSetProperties(p);
    }
    if (username != null) {
      create.addHeader("user", username);
    }
    return create.process(solrClient);
  }

  private NamedList<Object> getConfigSetPropertiesFromZk(SolrZkClient zkClient, String path)
      throws Exception {
    byte[] oldPropsData = null;
    try {
      oldPropsData = zkClient.getData(path, null, null, true);
    } catch (KeeperException.NoNodeException e) {
      // okay, properties just don't exist
    }

    if (oldPropsData != null) {
      try (InputStreamReader reader =
          new InputStreamReader(new ByteArrayInputStream(oldPropsData), UTF_8)) {
        return ConfigSetProperties.readFromInputStream(reader);
      }
    }
    return null;
  }

  private void verifyProperties(
      String configSetName,
      Map<String, String> oldProps,
      Map<String, String> newProps,
      SolrZkClient zkClient)
      throws Exception {
    NamedList<?> properties =
        getConfigSetPropertiesFromZk(
            zkClient,
            ZkConfigSetService.CONFIGS_ZKNODE + "/" + configSetName + "/" + DEFAULT_FILENAME);
    // let's check without merging the maps, since that's what the MessageHandler does
    // (since we'd probably repeat any bug in the MessageHandler here)
    if (oldProps == null && newProps == null) {
      assertNull(properties);
      return;
    }
    assertNotNull(properties);

    // check all oldProps are in props
    if (oldProps != null) {
      for (Map.Entry<String, String> entry : oldProps.entrySet()) {
        assertNotNull(properties.get(entry.getKey()));
      }
    }
    // check all newProps are in props
    if (newProps != null) {
      for (Map.Entry<String, String> entry : newProps.entrySet()) {
        assertNotNull(properties.get(entry.getKey()));
      }
    }

    // check the value in properties are correct
    for (Map.Entry<String, ?> entry : properties) {
      String newValue = newProps != null ? newProps.get(entry.getKey()) : null;
      String oldValue = oldProps != null ? oldProps.get(entry.getKey()) : null;
      if (newValue != null) {
        assertEquals(newValue, entry.getValue());
      } else if (oldValue != null) {
        assertEquals(oldValue, entry.getValue());
      } else {
        // not in either
        fail();
      }
    }
  }

  @Test
  public void testUploadErrors() throws Exception {
    final SolrClient solrClient =
        getHttpSolrClient(cluster.getJettySolrRunners().get(0).getBaseUrl().toString());

    ByteBuffer emptyData = ByteBuffer.allocate(0);

    ignoreException("The configuration name should be provided");
    // Checking error when no configuration name is specified in request
    Map<?, ?> map =
        postDataAndGetResponse(
            cluster.getSolrClient(),
            cluster.getJettySolrRunners().get(0).getBaseUrl().toString()
                + "/admin/configs?action=UPLOAD",
            emptyData,
            null,
            false);
    assertNotNull(map);
    unIgnoreException("The configuration name should be provided");
    long statusCode = (long) getObjectByPath(map, Arrays.asList("responseHeader", "status"));
    assertEquals(400l, statusCode);

    SolrZkClient zkClient =
        new SolrZkClient.Builder()
            .withUrl(cluster.getZkServer().getZkAddress())
            .withTimeout(AbstractZkTestCase.TIMEOUT, TimeUnit.MILLISECONDS)
            .withConnTimeOut(45000, TimeUnit.MILLISECONDS)
            .build();

    // Create dummy config files in zookeeper
    zkClient.makePath("/configs/myconf", true);
    zkClient.create(
        "/configs/myconf/firstDummyFile",
        "first dummy content".getBytes(UTF_8),
        CreateMode.PERSISTENT,
        true);
    zkClient.create(
        "/configs/myconf/anotherDummyFile",
        "second dummy content".getBytes(UTF_8),
        CreateMode.PERSISTENT,
        true);

    // Checking error when configuration name specified already exists
    ignoreException("already exists");
    map =
        postDataAndGetResponse(
            cluster.getSolrClient(),
            cluster.getJettySolrRunners().get(0).getBaseUrl().toString()
                + "/admin/configs?action=UPLOAD&name=myconf",
            emptyData,
            null,
            false);
    assertNotNull(map);
    unIgnoreException("already exists`");
    statusCode = (long) getObjectByPath(map, Arrays.asList("responseHeader", "status"));
    assertEquals(400l, statusCode);
    assertTrue(
        "Expected file doesnt exist in zk. It's possibly overwritten",
        zkClient.exists("/configs/myconf/firstDummyFile", true));
    assertTrue(
        "Expected file doesnt exist in zk. It's possibly overwritten",
        zkClient.exists("/configs/myconf/anotherDummyFile", true));

    zkClient.close();
    solrClient.close();
  }

  @Test
  public void testUploadDisabledV1() throws Exception {
    testUploadDisabled(false);
  }

  @Test
  public void testUploadDisabledV2() throws Exception {
    testUploadDisabled(true);
  }

  public void testUploadDisabled(boolean v2) throws Exception {
    try (SolrZkClient zkClient =
        new SolrZkClient.Builder()
            .withUrl(cluster.getZkServer().getZkAddress())
            .withTimeout(AbstractZkTestCase.TIMEOUT, TimeUnit.MILLISECONDS)
            .withConnTimeOut(45000, TimeUnit.MILLISECONDS)
            .build()) {

      ignoreException("Configset upload feature is disabled");
      for (boolean enabled : new boolean[] {true, false}) {
        System.setProperty("configset.upload.enabled", String.valueOf(enabled));
        try {
          long statusCode =
              uploadConfigSet("regular", "test-enabled-is-" + enabled, null, zkClient, v2);
          assertEquals(
              "ConfigSet upload enabling/disabling not working as expected for enabled="
                  + enabled
                  + ".",
              enabled ? 0l : 400l,
              statusCode);
        } finally {
          System.clearProperty("configset.upload.enabled");
        }
      }
      unIgnoreException("Configset upload feature is disabled");
    }
  }

  public void testUploadLegacyManagedSchemaFile() throws Exception {
    String configSetName = "legacy-managed-schema";
    SolrZkClient zkClient =
        new SolrZkClient.Builder()
            .withUrl(cluster.getZkServer().getZkAddress())
            .withTimeout(AbstractZkTestCase.TIMEOUT, TimeUnit.MILLISECONDS)
            .withConnTimeOut(45000, TimeUnit.MILLISECONDS)
            .build();
    try {
      long statusCode = uploadConfigSet(configSetName, "", null, zkClient, true);
      assertEquals(0l, statusCode);

      assertTrue(
          "managed-schema file should have been uploaded",
          zkClient.exists("/configs/" + configSetName + "/managed-schema", true));
    } finally {
      zkClient.close();
    }

    // try to create a collection with the uploaded configset
    createCollection("newcollection", configSetName, 1, 1, cluster.getSolrClient());

    String payload =
        "{\n"
            + "    'add-field' : {\n"
            + "                 'name':'a1',\n"
            + "                 'type': 'string',\n"
            + "                 'stored':true,\n"
            + "                 'indexed':false\n"
            + "                 },\n"
            + "    }";

    ByteBuffer buff = UTF_8.encode(payload);
    Map<?, ?> map =
        postDataAndGetResponse(
            cluster.getSolrClient(),
            cluster.getJettySolrRunners().get(0).getBaseUrl().toString()
                + "/newcollection/schema?wt=js"
                + "on",
            buff,
            null,
            false);
    Map<?, ?> responseHeader = (Map<?, ?>) map.get("responseHeader");
    Long status = (Long) responseHeader.get("status");
    assertEquals((long) status, 0L);
  }

  @Test
  public void testOverwriteV1() throws Exception {
    testOverwrite(false);
  }

  @Test
  public void testOverwriteV2() throws Exception {
    testOverwrite(true);
  }

  public void testOverwrite(boolean v2) throws Exception {
    String configsetName = "regular";
    String configsetSuffix = "testOverwrite-1-" + v2;
    uploadConfigSetWithAssertions(configsetName, configsetSuffix, null);
    try (SolrZkClient zkClient =
        new SolrZkClient.Builder()
            .withUrl(cluster.getZkServer().getZkAddress())
            .withTimeout(AbstractZkTestCase.TIMEOUT, TimeUnit.MILLISECONDS)
            .withConnTimeOut(45000, TimeUnit.MILLISECONDS)
            .build()) {
      int solrconfigZkVersion =
          getConfigZNodeVersion(zkClient, configsetName, configsetSuffix, "solrconfig.xml");
      ignoreException("The configuration regulartestOverwrite-1 already exists in zookeeper");
      assertEquals(
          "Can't overwrite an existing configset unless the overwrite parameter is set",
          400,
          uploadConfigSet(configsetName, configsetSuffix, null, false, false, v2, false, false));
      unIgnoreException("The configuration regulartestOverwrite-1 already exists in zookeeper");
      assertEquals(
          "Expecting version to remain equal",
          solrconfigZkVersion,
          getConfigZNodeVersion(zkClient, configsetName, configsetSuffix, "solrconfig.xml"));
      assertEquals(
          0, uploadConfigSet(configsetName, configsetSuffix, null, true, false, v2, false, false));
      assertTrue(
          "Expecting version bump",
          solrconfigZkVersion
              < getConfigZNodeVersion(zkClient, configsetName, configsetSuffix, "solrconfig.xml"));
    }
  }

  @Test
  public void testOverwriteWithCleanupV1() throws Exception {
    testOverwriteWithCleanup(false);
  }

  @Test
  public void testOverwriteWithCleanupV2() throws Exception {
    testOverwriteWithCleanup(true);
  }

  public void testOverwriteWithCleanup(boolean v2) throws Exception {
    String configsetName = "regular";
    String configsetSuffix = "testOverwriteWithCleanup-1-" + v2;
    uploadConfigSetWithAssertions(configsetName, configsetSuffix, null);
    try (SolrZkClient zkClient =
        new SolrZkClient.Builder()
            .withUrl(cluster.getZkServer().getZkAddress())
            .withTimeout(AbstractZkTestCase.TIMEOUT, TimeUnit.MILLISECONDS)
            .withConnTimeOut(45000, TimeUnit.MILLISECONDS)
            .build()) {
      String configPath = "/configs/" + configsetName + configsetSuffix;
      List<String> extraFiles =
          Arrays.asList(
              configPath + "/foo1",
              configPath + "/foo2",
              configPath + "/foo2/1",
              configPath + "/foo2/2");
      for (String f : extraFiles) {
        zkClient.makePath(f, true);
      }
      assertEquals(
          0, uploadConfigSet(configsetName, configsetSuffix, null, true, false, v2, false, false));
      for (String f : extraFiles) {
        assertTrue(
            "Expecting file " + f + " to exist in ConfigSet but it's gone",
            zkClient.exists(f, true));
      }
      assertEquals(
          0, uploadConfigSet(configsetName, configsetSuffix, null, true, true, v2, false, false));
      for (String f : extraFiles) {
        assertFalse(
            "Expecting file " + f + " to be deleted from ConfigSet but it wasn't",
            zkClient.exists(f, true));
      }
      assertConfigsetFiles(configsetName, configsetSuffix, zkClient);
    }
  }

  @Test
  public void testOverwriteWithForbiddenFilesV1() throws Exception {
    testOverwriteWithForbiddenFiles(false);
  }

  @Test
  public void testOverwriteWithForbiddenFilesV2() throws Exception {
    testOverwriteWithForbiddenFiles(true);
  }

  public void testOverwriteWithForbiddenFiles(boolean v2) throws Exception {
    String configsetName = "regular";
    String configsetSuffix = "testOverwriteWithForbiddenFiles-1-" + v2;
    uploadConfigSetWithAssertions(configsetName, configsetSuffix, null);
    try (SolrZkClient zkClient =
        new SolrZkClient.Builder()
            .withUrl(cluster.getZkServer().getZkAddress())
            .withTimeout(AbstractZkTestCase.TIMEOUT, TimeUnit.MILLISECONDS)
            .withConnTimeOut(45000, TimeUnit.MILLISECONDS)
            .build()) {
      String configPath = "/configs/" + configsetName + configsetSuffix;
      assertEquals(
          400, uploadConfigSet(configsetName, configsetSuffix, null, true, false, v2, true, false));

      for (String fileEnding : ZkMaintenanceUtils.DEFAULT_FORBIDDEN_FILE_TYPES) {
        String f = configPath + "/test." + fileEnding;
        assertFalse(
            "Expecting file " + f + " to not exist, because it has a forbidden file type",
            zkClient.exists(f, true));
      }
    }
  }

  @Test
  public void testSingleFileOverwriteV1() throws Exception {
    testSingleFileOverwrite(false);
  }

  @Test
  public void testSingleFileOverwriteV2() throws Exception {
    testSingleFileOverwrite(true);
  }

  public void testSingleFileOverwrite(boolean v2) throws Exception {
    String configsetName = "regular";
    String configsetSuffix = "testSinglePathOverwrite-1-" + v2;
    uploadConfigSetWithAssertions(configsetName, configsetSuffix, null);
    try (SolrZkClient zkClient =
        new SolrZkClient.Builder()
            .withUrl(cluster.getZkServer().getZkAddress())
            .withTimeout(AbstractZkTestCase.TIMEOUT, TimeUnit.MILLISECONDS)
            .withConnTimeOut(45000, TimeUnit.MILLISECONDS)
            .build()) {
      int solrconfigZkVersion =
          getConfigZNodeVersion(zkClient, configsetName, configsetSuffix, "solrconfig.xml");
      ignoreException("The configuration regulartestOverwrite-1 already exists in zookeeper");
      assertEquals(
          "Can't overwrite an existing configset unless the overwrite parameter is set",
          400,
          uploadSingleConfigSetFile(
              configsetName,
              configsetSuffix,
              null,
              "solr/configsets/upload/regular/solrconfig.xml",
              "solrconfig.xml",
              false,
              false,
              v2));
      unIgnoreException("The configuration regulartestOverwrite-1 already exists in zookeeper");
      assertEquals(
          "Expecting version to remain equal",
          solrconfigZkVersion,
          getConfigZNodeVersion(zkClient, configsetName, configsetSuffix, "solrconfig.xml"));
      assertEquals(
          0,
          uploadSingleConfigSetFile(
              configsetName,
              configsetSuffix,
              null,
              "solr/configsets/upload/regular/solrconfig.xml",
              "solrconfig.xml",
              true,
              false,
              v2));
      assertTrue(
          "Expecting version bump",
          solrconfigZkVersion
              < getConfigZNodeVersion(zkClient, configsetName, configsetSuffix, "solrconfig.xml"));
    }
  }

  @Test
  public void testNewSingleFileV1() throws Exception {
    testNewSingleFile(false);
  }

  @Test
  public void testNewSingleFileV2() throws Exception {
    testNewSingleFile(true);
  }

  public void testNewSingleFile(boolean v2) throws Exception {
    String configsetName = "regular";
    String configsetSuffix = "testSinglePathNew-1-" + v2;
    uploadConfigSetWithAssertions(configsetName, configsetSuffix, null);
    try (SolrZkClient zkClient =
        new SolrZkClient.Builder()
            .withUrl(cluster.getZkServer().getZkAddress())
            .withTimeout(AbstractZkTestCase.TIMEOUT, TimeUnit.MILLISECONDS)
            .withConnTimeOut(45000, TimeUnit.MILLISECONDS)
            .build()) {
      assertEquals(
          0,
          uploadSingleConfigSetFile(
              configsetName,
              configsetSuffix,
              null,
              "solr/configsets/upload/regular/solrconfig.xml",
              "/test/upload/path/solrconfig.xml",
              false,
              false,
              v2));
      assertEquals(
          "Expecting first version of new file",
          0,
          getConfigZNodeVersion(
              zkClient, configsetName, configsetSuffix, "test/upload/path/solrconfig.xml"));
      assertConfigsetFiles(configsetName, configsetSuffix, zkClient);
    }
  }

  @Test
  public void testNewSingleFileAfterSchemaAPIV1() throws Exception {
    testNewSingleFileAfterSchemaAPI(false);
  }

  @Test
  public void testNewSingleFileAfterSchemaAPIV2() throws Exception {
    testNewSingleFileAfterSchemaAPI(true);
  }

  private void addStringField(String fieldName, String collection, CloudSolrClient cloudClient)
      throws IOException, SolrServerException {
    Map<String, Object> fieldAttributes = new LinkedHashMap<>();
    fieldAttributes.put("name", fieldName);
    fieldAttributes.put("type", "string");
    SchemaRequest.AddField addFieldUpdateSchemaRequest =
        new SchemaRequest.AddField(fieldAttributes);
    SchemaResponse.UpdateResponse addFieldResponse =
        addFieldUpdateSchemaRequest.process(cloudClient, collection);
    assertEquals(0, addFieldResponse.getStatus());
    assertNull(addFieldResponse.getResponse().get("errors"));

    log.info("added new field={}", fieldName);
  }

  private void testNewSingleFileAfterSchemaAPI(boolean v2) throws Exception {
    String collectionName = "newcollection";
    String configsetName = "regular";
    String configsetSuffix = "testSinglePathNew-1-" + v2;
    createConfigSet(null, configsetName + configsetSuffix, null, cluster.getSolrClient(), "solr");
    createCollection(
        collectionName, configsetName + configsetSuffix, 1, 1, cluster.getSolrClient());
    addStringField("newField", collectionName, cluster.getSolrClient());

    assertEquals(
        0,
        uploadSingleConfigSetFile(
            configsetName,
            configsetSuffix,
            "solr",
            "solr/configsets/upload/regular/solrconfig.xml",
            "/test/upload/path/solrconfig.xml",
            false,
            false,
            v2));
    SolrZkClient zkClient = cluster.getZkServer().getZkClient();
    assertEquals(
        "Expecting first version of new file",
        0,
        getConfigZNodeVersion(
            zkClient, configsetName, configsetSuffix, "test/upload/path/solrconfig.xml"));
  }

  @Test
  public void testSingleWithCleanupV1() throws Exception {
    testSingleWithCleanup(false);
  }

  @Test
  public void testSingleWithCleanupV2() throws Exception {
    testSingleWithCleanup(true);
  }

  public void testSingleWithCleanup(boolean v2) throws Exception {
    String configsetName = "regular";
    String configsetSuffix = "testSinglePathCleanup-1-" + v2;
    uploadConfigSetWithAssertions(configsetName, configsetSuffix, null);
    try (SolrZkClient zkClient =
        new SolrZkClient.Builder()
            .withUrl(cluster.getZkServer().getZkAddress())
            .withTimeout(AbstractZkTestCase.TIMEOUT, TimeUnit.MILLISECONDS)
            .withConnTimeOut(45000, TimeUnit.MILLISECONDS)
            .build()) {
      ignoreException("ConfigSet uploads do not allow cleanup=true when filePath is used.");
      assertEquals(
          400,
          uploadSingleConfigSetFile(
              configsetName,
              configsetSuffix,
              null,
              "solr/configsets/upload/regular/solrconfig.xml",
              "/test/upload/path/solrconfig.xml",
              true,
              true,
              v2));
      assertFalse(
          "New file should not exist, since the trust check did not succeed.",
          zkClient.exists(
              "/configs/" + configsetName + configsetSuffix + "/test/upload/path/solrconfig.xml",
              true));
      assertConfigsetFiles(configsetName, configsetSuffix, zkClient);
      unIgnoreException("ConfigSet uploads do not allow cleanup=true when filePath is used.");
    }
  }

  @Test
  public void testSingleFileForbiddenTypeV1() throws Exception {
    testSingleFileForbiddenType(false);
  }

  @Test
  public void testSingleFileForbiddenTypeV2() throws Exception {
    testSingleFileForbiddenType(true);
  }

  public void testSingleFileForbiddenType(boolean v2) throws Exception {
    String configsetName = "regular";
    String configsetSuffix = "testSingleFileForbiddenType-1-" + v2;
    uploadConfigSetWithAssertions(configsetName, configsetSuffix, "solr");
    try (SolrZkClient zkClient =
        new SolrZkClient.Builder()
            .withUrl(cluster.getZkServer().getZkAddress())
            .withTimeout(AbstractZkTestCase.TIMEOUT, TimeUnit.MILLISECONDS)
            .withConnTimeOut(45000, TimeUnit.MILLISECONDS)
            .build()) {
      for (String fileType : ZkMaintenanceUtils.DEFAULT_FORBIDDEN_FILE_TYPES) {
        ignoreException("is forbidden for use in configSets");
        assertEquals(
            "Can't upload a configset file with a forbidden type: " + fileType,
            400,
            uploadSingleConfigSetFile(
                configsetName,
                configsetSuffix,
                "solr",
                "solr/configsets/upload/regular/solrconfig.xml",
                "/test/different/path/solrconfig." + fileType,
                false,
                false,
                v2));
        assertFalse(
            "New file should not exist, since the filetype is forbidden: " + fileType,
            zkClient.exists(
                "/configs/"
                    + configsetName
                    + configsetSuffix
                    + "/test/different/path/solrconfig."
                    + fileType,
                true));
        unIgnoreException("is forbidden for use in configSets");
      }
    }
  }

  @Test
  public void testSingleFileUntrustedV1() throws Exception {
    testSingleFileUntrusted(false);
  }

  @Test
  public void testSingleFileUntrustedV2() throws Exception {
    testSingleFileUntrusted(true);
  }

  public void testSingleFileUntrusted(boolean v2) throws Exception {
    String configsetName = "regular";
    String configsetSuffix = "testSinglePathUntrusted-1-" + v2;
    uploadConfigSetWithAssertions(configsetName, configsetSuffix, null);

    try (SolrZkClient zkClient =
        new SolrZkClient.Builder()
            .withUrl(cluster.getZkServer().getZkAddress())
            .withTimeout(AbstractZkTestCase.TIMEOUT, TimeUnit.MILLISECONDS)
            .withConnTimeOut(45000, TimeUnit.MILLISECONDS)
            .build()) {
      // New file with trusted request

      assertEquals(
          0,
          uploadSingleConfigSetFile(
              configsetName,
              configsetSuffix,
              "solr",
              "solr/configsets/upload/regular/solrconfig.xml",
              "/test/upload/path/solrconfig.xml",
              false,
              false,
              v2));
      assertEquals(
          "Expecting first version of new file",
          0,
          getConfigZNodeVersion(
              zkClient, configsetName, configsetSuffix, "test/upload/path/solrconfig.xml"));
      assertConfigsetFiles(configsetName, configsetSuffix, zkClient);

      // New file with untrusted request
      assertEquals(
          0,
          uploadSingleConfigSetFile(
              configsetName,
              configsetSuffix,
              null,
              "solr/configsets/upload/regular/solrconfig.xml",
              "/test/different/path/solrconfig.xml",
              false,
              false,
              v2));
      assertEquals(
          "Expecting first version of new file",
          0,
          getConfigZNodeVersion(
              zkClient, configsetName, configsetSuffix, "test/different/path/solrconfig.xml"));
      assertConfigsetFiles(configsetName, configsetSuffix, zkClient);

      // Overwrite with trusted request
      int extraFileZkVersion =
          getConfigZNodeVersion(
              zkClient, configsetName, configsetSuffix, "test/different/path/solrconfig.xml");
      assertEquals(
          0,
          uploadSingleConfigSetFile(
              configsetName,
              configsetSuffix,
              "solr",
              "solr/configsets/upload/regular/solrconfig.xml",
              "/test/different/path/solrconfig.xml",
              true,
              false,
              v2));
      assertTrue(
          "Expecting version bump",
          extraFileZkVersion
              < getConfigZNodeVersion(
                  zkClient, configsetName, configsetSuffix, "test/different/path/solrconfig.xml"));
      assertConfigsetFiles(configsetName, configsetSuffix, zkClient);

      // Overwrite with untrusted request
      extraFileZkVersion =
          getConfigZNodeVersion(
              zkClient, configsetName, configsetSuffix, "test/upload/path/solrconfig.xml");
      assertEquals(
          0,
          uploadSingleConfigSetFile(
              configsetName,
              configsetSuffix,
              null,
              "solr/configsets/upload/regular/solrconfig.xml",
              "/test/upload/path/solrconfig.xml",
              true,
              false,
              v2));
      assertTrue(
          "Expecting version bump",
          extraFileZkVersion
              < getConfigZNodeVersion(
                  zkClient, configsetName, configsetSuffix, "test/upload/path/solrconfig.xml"));
      assertConfigsetFiles(configsetName, configsetSuffix, zkClient);

      // Make sure that cleanup flag does not result in configSet being trusted.
      ignoreException("ConfigSet uploads do not allow cleanup=true when filePath is used.");
      extraFileZkVersion =
          getConfigZNodeVersion(
              zkClient, configsetName, configsetSuffix, "test/different/path/solrconfig.xml");
      assertEquals(
          400,
          uploadSingleConfigSetFile(
              configsetName,
              configsetSuffix,
              "solr",
              "solr/configsets/upload/regular/solrconfig.xml",
              "/test/different/path/solrconfig.xml",
              true,
              true,
              v2));
      assertEquals(
          "Expecting version to stay the same",
          extraFileZkVersion,
          getConfigZNodeVersion(
              zkClient, configsetName, configsetSuffix, "test/different/path/solrconfig.xml"));
      assertConfigsetFiles(configsetName, configsetSuffix, zkClient);
      unIgnoreException("ConfigSet uploads do not allow cleanup=true when filePath is used.");
    }
  }

  @Test
  public void testSingleFileNewConfigV1() throws Exception {
    testSingleFileNewConfig(false);
  }

  @Test
  public void testSingleFileNewConfigV2() throws Exception {
    testSingleFileNewConfig(true);
  }

  public void testSingleFileNewConfig(boolean v2) throws Exception {
    String configsetName = "regular";
    String configsetSuffixTrusted = "testSinglePathNewConfig-1-" + v2;
    String configsetSuffixUntrusted = "testSinglePathNewConfig-2-" + v2;
    try (SolrZkClient zkClient =
        new SolrZkClient.Builder()
            .withUrl(cluster.getZkServer().getZkAddress())
            .withTimeout(AbstractZkTestCase.TIMEOUT, TimeUnit.MILLISECONDS)
            .withConnTimeOut(45000, TimeUnit.MILLISECONDS)
            .build()) {
      // New file with trusted request
      assertEquals(
          0,
          uploadSingleConfigSetFile(
              configsetName,
              configsetSuffixTrusted,
              "solr",
              "solr/configsets/upload/regular/solrconfig.xml",
              "solrconfig.xml",
              false,
              false,
              v2));
      assertEquals(
          "Expecting first version of new file",
          0,
          getConfigZNodeVersion(zkClient, configsetName, configsetSuffixTrusted, "solrconfig.xml"));
      List<String> children =
          zkClient.getChildren(
              String.format(Locale.ROOT, "/configs/%s%s", configsetName, configsetSuffixTrusted),
              null,
              true);
      assertEquals("The configSet should only have one file uploaded.", 1, children.size());
      assertEquals("Incorrect file uploaded.", "solrconfig.xml", children.get(0));

      // New file with trusted request
      assertEquals(
          0,
          uploadSingleConfigSetFile(
              configsetName,
              configsetSuffixUntrusted,
              null,
              "solr/configsets/upload/regular/solrconfig.xml",
              "solrconfig.xml",
              false,
              false,
              v2));
      assertEquals(
          "Expecting first version of new file",
          0,
          getConfigZNodeVersion(
              zkClient, configsetName, configsetSuffixUntrusted, "solrconfig.xml"));
      children =
          zkClient.getChildren(
              String.format(Locale.ROOT, "/configs/%s%s", configsetName, configsetSuffixUntrusted),
              null,
              true);
      assertEquals("The configSet should only have one file uploaded.", 1, children.size());
      assertEquals("Incorrect file uploaded.", "solrconfig.xml", children.get(0));
    }
  }

  private int getConfigZNodeVersion(
      SolrZkClient zkClient, String configsetName, String configsetSuffix, String configFile)
      throws KeeperException, InterruptedException {
    Stat stat = new Stat();
    zkClient.getData(
        String.format(Locale.ROOT, "/configs/%s%s/%s", configsetName, configsetSuffix, configFile),
        null,
        stat,
        true);
    return stat.getVersion();
  }

  @Test
  public void testUpload() throws Exception {
    String suffix = "-untrusted";
    uploadConfigSetWithAssertions("regular", suffix, null);
    // try to create a collection with the uploaded configset
    createCollection("newcollection", "regular" + suffix, 1, 1, cluster.getSolrClient());
  }

  @Test
  public void testUploadWithScriptUpdateProcessor() throws Exception {
    Assume.assumeNotNull((new ScriptEngineManager()).getEngineByExtension("js"));
    Assume.assumeNotNull((new ScriptEngineManager()).getEngineByName("JavaScript"));

    // Authorization off
    final String untrustedSuffix = "-untrusted";
    uploadConfigSetWithAssertions("with-script-processor", untrustedSuffix, null);
    // try to create a collection with the uploaded configset
    ignoreException("uploaded without any authentication in place");
    Throwable thrown =
        expectThrows(
            SolrClient.RemoteSolrException.class,
            () -> {
              createCollection(
                  "newcollection2",
                  "with-script-processor" + untrustedSuffix,
                  1,
                  1,
                  cluster.getSolrClient());
            });
    unIgnoreException("uploaded without any authentication in place");

    assertThat(thrown.getMessage(), containsString("Underlying core creation failed"));

    // Authorization on
    final String trustedSuffix = "-trusted";
    uploadConfigSetWithAssertions("with-script-processor", trustedSuffix, "solr");
    // try to create a collection with the uploaded configset
    CollectionAdminResponse resp =
        createCollection(
            "newcollection2",
            "with-script-processor" + trustedSuffix,
            1,
            1,
            cluster.getSolrClient());
    scriptRequest("newcollection2");
  }

  @Test
  public void testUploadWithForbiddenContent() throws Exception {
    // Uploads a config set containing a script, a class file and jar file, will return 400 error
    long res = uploadConfigSet("forbidden", "suffix", "foo", true, false, true, false, true);
    assertEquals(400, res);
  }

  private static String getSecurityJson() {
    return "{\n"
        + "  'authentication':{\n"
        + "    'blockUnknown': false,\n"
        + "    'class':'"
        + MockAuthenticationPlugin.class.getName()
        + "'},\n"
        + "  'authorization':{\n"
        + "    'class':'"
        + MockAuthorizationPlugin.class.getName()
        + "'}}";
  }

  private void uploadConfigSetWithAssertions(String configSetName, String suffix, String username)
      throws Exception {
    try (SolrZkClient zkClient =
        new SolrZkClient.Builder()
            .withUrl(cluster.getZkServer().getZkAddress())
            .withTimeout(AbstractZkTestCase.TIMEOUT, TimeUnit.MILLISECONDS)
            .withConnTimeOut(45000, TimeUnit.MILLISECONDS)
            .build()) {
      long statusCode = uploadConfigSet(configSetName, suffix, username, zkClient, true);
      assertEquals(0l, statusCode);
      assertConfigsetFiles(configSetName, suffix, zkClient);
    }
  }

  private void assertConfigsetFiles(String configSetName, String suffix, SolrZkClient zkClient)
      throws KeeperException, InterruptedException, IOException {
    assertTrue(
        "managed-schema.xml file should have been uploaded",
        zkClient.exists("/configs/" + configSetName + suffix + "/managed-schema.xml", true));
    assertArrayEquals(
        "managed-schema.xml file contents on zookeeper are not exactly same as that of the file uploaded in config",
        zkClient.getData(
            "/configs/" + configSetName + suffix + "/managed-schema.xml", null, null, true),
        readFile("solr/configsets/upload/" + configSetName + "/managed-schema.xml"));

    assertTrue(
        "solrconfig.xml file should have been uploaded",
        zkClient.exists("/configs/" + configSetName + suffix + "/solrconfig.xml", true));
    byte data[] = zkClient.getData("/configs/" + configSetName + suffix, null, null, true);
    // assertEquals("{\"trusted\": false}", new String(data, StandardCharsets.UTF_8));
    assertArrayEquals(
        "solrconfig.xml file contents on zookeeper are not exactly same as that of the file uploaded in config",
        zkClient.getData(
            "/configs/" + configSetName + suffix + "/solrconfig.xml", null, null, true),
        readFile("solr/configsets/upload/" + configSetName + "/solrconfig.xml"));
  }

  private long uploadConfigSet(
      String configSetName, String suffix, String username, SolrZkClient zkClient, boolean v2)
      throws IOException {
    assertFalse(getConfigSetService().checkConfigExists(configSetName + suffix));
    return uploadConfigSet(configSetName, suffix, username, false, false, v2, false, false);
  }

  private long uploadConfigSet(
      String configSetName,
      String suffix,
      String username,
      boolean overwrite,
      boolean cleanup,
      boolean v2,
      boolean forbiddenTypes,
      boolean forbiddenContent)
      throws IOException {

    Path zipFile;
    if (forbiddenTypes) {
      log.info("Uploading configset with forbidden file endings");
      zipFile =
          createTempZipFileWithForbiddenTypes(
              "solr/configsets/upload/" + configSetName + "/solrconfig.xml");
    } else if (forbiddenContent) {
      log.info("Uploading configset with forbidden file content");
      zipFile = createTempZipFileWithForbiddenContent("magic");
    } else {
      zipFile = createTempZipFile("solr/configsets/upload/" + configSetName);
    }

    // Read zipped sample config
    return uploadGivenConfigSet(zipFile, configSetName, suffix, username, overwrite, cleanup, v2);
  }

  private long uploadBadConfigSet(String configSetName, String suffix, String username, boolean v2)
      throws IOException {

    // Read single file from sample configs. This should fail the unzipping
    return uploadGivenConfigSet(
        SolrTestCaseJ4.getFile("solr/configsets/upload/regular/solrconfig.xml"),
        configSetName,
        suffix,
        username,
        true /* overwrite */,
        true /* cleanup */,
        v2);
  }

  private long uploadGivenConfigSet(
      Path file,
      String configSetName,
      String suffix,
      String username,
      boolean overwrite,
      boolean cleanup,
      boolean v2)
      throws IOException {

    if (v2) {
      // TODO: switch to using V2Request

      final ByteBuffer fileBytes = TestSolrConfigHandler.getFileContent(file.toString(), false);
      final String uriEnding =
          "/configsets/"
              + configSetName
              + suffix
              + (!overwrite ? "?overwrite=false" : "")
              + (cleanup ? "?cleanup=true" : "");
      final boolean usePut = true;
      Map<?, ?> map =
          postDataAndGetResponse(
              cluster.getSolrClient(),
              cluster.getJettySolrRunners().get(0).getBaseURLV2().toString() + uriEnding,
              fileBytes,
              username,
              usePut);
      assertNotNull(map);
      return (Long) getObjectByPath(map, Arrays.asList("responseHeader", "status"));
    } // else "not" a V2 request...

    try {
      return (new Upload())
          .setConfigSetName(configSetName + suffix)
          .setUploadFile(file, "application/zip")
          .setOverwrite(overwrite ? true : null) // expect server default to be 'false'
          .setCleanup(cleanup ? true : null) // expect server default to be 'false'
          .setBasicAuthCredentials(username, username) // for our MockAuthenticationPlugin
          .process(cluster.getSolrClient())
          .getStatus();
    } catch (SolrServerException e1) {
      throw new AssertionError("Server error uploading configset: " + e1, e1);
    } catch (SolrException e2) {
      return e2.code();
    }
  }

  private long uploadSingleConfigSetFile(
      String configSetName,
      String suffix,
      String username,
      String localFilePath,
      String uploadPath,
      boolean overwrite,
      boolean cleanup,
      boolean v2)
      throws IOException {
    // Read single file from sample configs
    final Path file = SolrTestCaseJ4.getFile(localFilePath);

    if (v2) {
      // TODO: switch to use V2Request

      final ByteBuffer sampleConfigFile =
          TestSolrConfigHandler.getFileContent(file.toString(), false);
      if (uploadPath != null && !uploadPath.startsWith("/")) {
        uploadPath = "/" + uploadPath;
      }
      final String uriEnding =
          "/configsets/"
              + configSetName
              + suffix
              + uploadPath
              + (!overwrite ? "?overwrite=false" : "")
              + (cleanup ? "?cleanup=true" : "");
      final boolean usePut = true;

      Map<?, ?> map =
          postDataAndGetResponse(
              cluster.getSolrClient(),
              cluster.getJettySolrRunners().get(0).getBaseURLV2().toString() + uriEnding,
              sampleConfigFile,
              username,
              usePut);
      assertNotNull(map);
      return (long) getObjectByPath(map, Arrays.asList("responseHeader", "status"));
    } // else "not" a V2 request...

    try {
      return (new Upload())
          .setConfigSetName(configSetName + suffix)
          .setFilePath(uploadPath)
          // NOTE: server doesn't actually care, and test plumbing doesn't tell us
          .setUploadFile(file, "application/octet-stream")
          .setOverwrite(overwrite ? true : null) // expect server default to be 'false'
          .setCleanup(cleanup ? true : null) // expect server default to be 'false'
          .setBasicAuthCredentials(username, username) // for our MockAuthenticationPlugin
          .process(cluster.getSolrClient())
          .getStatus();
    } catch (SolrServerException e1) {
      throw new AssertionError("Server error uploading file to configset: " + e1, e1);
    } catch (SolrException e2) {
      return e2.code();
    }
  }

  /**
   * Create a zip file (in the temp directory) containing all the files within the specified
   * directory and return the zip file.
   */
  private Path createTempZipFile(String directoryPath) {
    try {
      final Path zipFile = createTempFile("configset", "zip");
      final Path directory = SolrTestCaseJ4.getFile(directoryPath);
      if (log.isInfoEnabled()) {
        log.info("Directory: {}", directory);
      }
      zip(directory, zipFile);
      if (log.isInfoEnabled()) {
        log.info("Zipfile: {}", zipFile);
      }
      return zipFile;
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Create a zip file (in the temp directory) containing a file with all forbidden types (named
   * "test.fileType")
   */
  private Path createTempZipFileWithForbiddenTypes(String file) {
    try {
      final Path zipFile = createTempFile("configset", "zip");
      final Path directory = SolrTestCaseJ4.getFile(file);
      if (log.isInfoEnabled()) {
        log.info("Directory: {}", directory);
      }
      zipWithForbiddenEndings(directory, zipFile);
      if (log.isInfoEnabled()) {
        log.info("Zipfile: {}", zipFile);
      }
      return zipFile;
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /** Create a zip file (in the temp directory) containing files with forbidden content */
  private Path createTempZipFileWithForbiddenContent(String resourcePath) {
    try {
      final Path zipFile = createTempFile("configset", "zip");
      final Path directory = SolrTestCaseJ4.getFile(resourcePath);
      if (log.isInfoEnabled()) {
        log.info("Directory: {}", directory);
      }
      zipWithForbiddenContent(directory, zipFile);
      if (log.isInfoEnabled()) {
        log.info("Zipfile: {}", zipFile);
      }
      return zipFile;
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private static void zipWithForbiddenContent(Path directory, Path zipfile) throws IOException {
    OutputStream out = Files.newOutputStream(zipfile);
    assertTrue(Files.isDirectory(directory));
    try (ZipOutputStream zout = new ZipOutputStream(out)) {
      // Copy in all files from the directory
      try (Stream<Path> files = Objects.requireNonNull(Files.list(directory))) {
        files.forEach(
            (file) -> {
              try {
                zout.putNextEntry(new ZipEntry(file.getFileName().toString()));
                zout.write(Files.readAllBytes(file));
                zout.closeEntry();
              } catch (IOException e) {
                throw new RuntimeException("Failed to write zip file", e);
              }
            });
      }
    }
  }

  private static void zipWithForbiddenEndings(Path fileOrDirectory, Path zipfile)
      throws IOException {
    OutputStream out = Files.newOutputStream(zipfile);
    try (ZipOutputStream zout = new ZipOutputStream(out)) {
      if (Files.isRegularFile(fileOrDirectory)) {
        // Create entries with given file, one for each forbidden endding
        for (String fileType : ZkMaintenanceUtils.DEFAULT_FORBIDDEN_FILE_TYPES) {
          zout.putNextEntry(new ZipEntry("test." + fileType));

          try (InputStream in = Files.newInputStream(fileOrDirectory)) {
            in.transferTo(zout);
          }

          zout.closeEntry();
        }
      }
      if (Files.isDirectory(fileOrDirectory)) {
        // Copy in all files from the directory
        try (Stream<Path> files = Objects.requireNonNull(Files.list(fileOrDirectory))) {
          files.forEach(
              (file) -> {
                try {
                  zout.putNextEntry(new ZipEntry(file.getFileName().toString()));
                  zout.write(Files.readAllBytes(file));
                  zout.closeEntry();
                } catch (IOException e) {
                  throw new RuntimeException(e);
                }
              });
        }
      }
    }
  }

  private static void zip(Path directory, Path zipfile) throws IOException {
    URI base = directory.toUri();
    Deque<Path> queue = new ArrayDeque<>();
    queue.push(directory);
    OutputStream out = Files.newOutputStream(zipfile);
    try (ZipOutputStream zout = new ZipOutputStream(out)) {
      while (!queue.isEmpty()) {
        directory = queue.pop();
        try (Stream<Path> files = Files.list(directory)) {
          files.forEach(
              (kid) -> {
                try {
                  String name = base.relativize(kid.toUri()).getPath();
                  if (Files.isDirectory(kid)) {
                    queue.push(kid);
                    name = name.endsWith("/") ? name : name + "/";
                    zout.putNextEntry(new ZipEntry(name));
                  } else {
                    zout.putNextEntry(new ZipEntry(name));
                    try (InputStream in = Files.newInputStream(kid)) {
                      in.transferTo(zout);
                    }
                    zout.closeEntry();
                  }
                } catch (IOException e) {
                  throw new RuntimeException(e);
                }
              });
        }
      }
    }
  }

  public void scriptRequest(String collection) throws SolrServerException, IOException {
    SolrClient client = cluster.getSolrClient();
    SolrInputDocument doc = sdoc("id", "4055", "subject", "Solr");
    client.add(collection, doc);
    client.commit(collection);

    assertEquals(
        "42",
        client.query(collection, params("q", "*:*")).getResults().get(0).get("script_added_i"));
  }

  protected CollectionAdminResponse createCollection(
      String collectionName,
      String confSetName,
      int numShards,
      int replicationFactor,
      SolrClient client)
      throws SolrServerException, IOException {
    ModifiableSolrParams params = new ModifiableSolrParams();
    params.set("action", CollectionAction.CREATE.toString());
    params.set("collection.configName", confSetName);
    params.set("name", collectionName);
    params.set("numShards", numShards);
    params.set("replicationFactor", replicationFactor);
    var request =
        new GenericSolrRequest(METHOD.GET, "/admin/collections", SolrRequestType.ADMIN, params);

    CollectionAdminResponse res = new CollectionAdminResponse();
    res.setResponse(client.request(request));
    return res;
  }

  public static Map<?, ?> postDataAndGetResponse(
      CloudSolrClient cloudClient, String uri, ByteBuffer bytarr, String username, boolean usePut)
      throws IOException {
    HttpEntityEnclosingRequestBase httpRequest = null;
    HttpEntity entity;
    String response = null;
    Map<?, ?> m = null;

    try {
      if (usePut) {
        httpRequest = new HttpPut(uri);
      } else {
        httpRequest = new HttpPost(uri);
      }

      if (username != null) {
        httpRequest.addHeader(new BasicHeader("user", username));
      }

      httpRequest.setHeader("Content-Type", "application/octet-stream");
      httpRequest.setEntity(
          new ByteArrayEntity(bytarr.array(), bytarr.arrayOffset(), bytarr.limit()));
      log.info("Uploading configset with user {}", username);
      entity =
          ((CloudLegacySolrClient) cloudClient).getHttpClient().execute(httpRequest).getEntity();
      try {
        response = EntityUtils.toString(entity, UTF_8);
        m = (Map<?, ?>) Utils.fromJSONString(response);
      } catch (JSONParser.ParseException e) {
        System.err.println("err response: " + response);
        throw new AssertionError(e);
      }
    } finally {
      httpRequest.releaseConnection();
    }
    return m;
  }

  private static Object getObjectByPath(Map<?, ?> root, List<String> hierarchy) {
    Map<?, ?> obj = root;
    for (int i = 0; i < hierarchy.size(); i++) {
      String s = hierarchy.get(i);
      if (i < hierarchy.size() - 1) {
        if (!(obj.get(s) instanceof Map)) return null;
        obj = (Map<?, ?>) obj.get(s);
        if (obj == null) return null;
      } else {
        Object val = obj.get(s);
        return val;
      }
    }

    return false;
  }

  private byte[] readFile(String fname) throws IOException {
    byte[] buf = null;
    try (FileInputStream fis = new FileInputStream(getFile(fname).toFile())) {
      buf = new byte[fis.available()];
      fis.read(buf);
    }
    return buf;
  }

  @Test
  public void testDeleteErrors() throws Exception {
    final String baseUrl = cluster.getJettySolrRunners().get(0).getBaseUrl().toString();
    final SolrClient solrClient = getHttpSolrClient(baseUrl);
    final Path configDir = getFile("solr").resolve("configsets/configset-2/conf");
    final Path tmpConfigDir = createTempDir();
    // Ensure ConfigSet is immutable
    PathUtils.copyDirectory(configDir, tmpConfigDir);
    Files.writeString(
        tmpConfigDir.resolve("configsetprops.json"),
        getConfigSetProps(Map.of("immutable", "true")),
        UTF_8);
    getConfigSetService().uploadConfig("configSet", tmpConfigDir);

    // no ConfigSet name
    DeleteNoErrorChecking delete = new DeleteNoErrorChecking();
    verifyException(solrClient, delete, NAME);

    // ConfigSet is immutable
    delete.setConfigSetName("configSet");
    verifyException(solrClient, delete, "Requested delete of immutable ConfigSet");

    solrClient.close();
  }

  private void verifyException(
      SolrClient solrClient, ConfigSetAdminRequest<?, ?> request, String errorContains) {
    ignoreException(errorContains);
    Exception e = expectThrows(Exception.class, () -> solrClient.request(request));
    assertTrue(
        "Expected exception message to contain: " + errorContains + " got: " + e.getMessage(),
        e.getMessage().contains(errorContains));
    unIgnoreException(errorContains);
  }

  @Test
  public void testDelete() throws Exception {
    final String baseUrl = cluster.getJettySolrRunners().get(0).getBaseUrl().toString();
    final SolrClient solrClient = getHttpSolrClient(baseUrl);
    final String configSet = "testDelete";
    getConfigSetService().uploadConfig(configSet, configset("configset-2"));
    assertDelete(solrClient, configSet, true);
    assertDelete(solrClient, "configSetBogus", false);
    solrClient.close();
  }

  private void assertDelete(SolrClient solrClient, String configSet, boolean assertExists)
      throws IOException, SolrServerException {
    SolrZkClient zkClient =
        new SolrZkClient.Builder()
            .withUrl(cluster.getZkServer().getZkAddress())
            .withTimeout(AbstractZkTestCase.TIMEOUT, TimeUnit.MILLISECONDS)
            .withConnTimeOut(AbstractZkTestCase.TIMEOUT, TimeUnit.MILLISECONDS)
            .build();
    try {
      assertEquals(assertExists, getConfigSetService().checkConfigExists(configSet));

      Delete delete = new Delete();
      delete.setConfigSetName(configSet);
      ConfigSetAdminResponse response = delete.process(solrClient);
      assertNotNull(response.getResponse());
      assertFalse(getConfigSetService().checkConfigExists(configSet));
    } finally {
      zkClient.close();
    }
  }

  @Test
  public void testList() throws Exception {
    final String baseUrl = cluster.getJettySolrRunners().get(0).getBaseUrl().toString();
    final SolrClient solrClient = getHttpSolrClient(baseUrl);

    SolrZkClient zkClient =
        new SolrZkClient.Builder()
            .withUrl(cluster.getZkServer().getZkAddress())
            .withTimeout(AbstractZkTestCase.TIMEOUT, TimeUnit.MILLISECONDS)
            .withConnTimeOut(AbstractZkTestCase.TIMEOUT, TimeUnit.MILLISECONDS)
            .build();
    try {
      // test empty
      ConfigSetAdminRequest.List list = new ConfigSetAdminRequest.List();
      ConfigSetAdminResponse.List response = list.process(solrClient);
      Collection<String> actualConfigSets = response.getConfigSets();
      assertEquals(1, actualConfigSets.size()); // only the _default configset

      // test multiple
      Set<String> configSets = new HashSet<String>();
      for (int i = 0; i < 5; ++i) {
        String configSet = "configSet" + i;
        getConfigSetService().uploadConfig(configSet, configset("configset-2"));
        configSets.add(configSet);
      }
      response = list.process(solrClient);
      actualConfigSets = response.getConfigSets();
      assertEquals(configSets.size() + 1, actualConfigSets.size());
      assertTrue(actualConfigSets.containsAll(configSets));
    } finally {
      zkClient.close();
    }

    solrClient.close();
  }

  /**
   * A simple sanity check that the test-framework hueristic logic for setting {@link
   * ExternalPaths#DEFAULT_CONFIGSET} is working as it should in the current test env, and finding
   * the real directory which matches what {@link ZkController} finds and uses to bootstrap ZK in
   * cloud based tests.
   *
   * <p>This assumes the {@link SolrDispatchFilter#SOLR_DEFAULT_CONFDIR_ATTRIBUTE} system property
   * has not been externally set in the environment where this test is being run -- which should
   * <b>never</b> be the case, since it would prevent the test-framework from using {@link
   * ExternalPaths#DEFAULT_CONFIGSET}
   *
   * @see SolrDispatchFilter#SOLR_DEFAULT_CONFDIR_ATTRIBUTE
   * @see #beforeSolrTestCase
   * @see ConfigSetService#getDefaultConfigDirPath
   */
  @Test
  public void testUserAndTestDefaultConfigsetsAreSame() {
    final Path extPath = ExternalPaths.DEFAULT_CONFIGSET;
    assertTrue(
        "_default dir doesn't exist: " + ExternalPaths.DEFAULT_CONFIGSET, Files.exists(extPath));
    assertTrue(
        "_default dir isn't a dir: " + ExternalPaths.DEFAULT_CONFIGSET, Files.isDirectory(extPath));

    final Path zkBootStrap = ConfigSetService.getDefaultConfigDirPath();
    assertEquals("extPath _default configset dir vs zk bootstrap path", extPath, zkBootStrap);
  }

  private StringBuilder getConfigSetProps(Map<String, String> map) {
    return new StringBuilder(new String(Utils.toJSON(map), UTF_8));
  }

  public static class CreateNoErrorChecking extends ConfigSetAdminRequest.Create {
    @Override
    public ConfigSetAdminRequest<Create, ConfigSetAdminResponse> setAction(ConfigSetAction action) {
      return super.setAction(action);
    }

    @Override
    public SolrParams getParams() {
      ModifiableSolrParams params = new ModifiableSolrParams();
      if (action != null) params.set(ConfigSetParams.ACTION, action.toString());
      if (configSetName != null) params.set(NAME, configSetName);
      if (baseConfigSetName != null) params.set("baseConfigSet", baseConfigSetName);
      return params;
    }
  }

  public static class DeleteNoErrorChecking extends ConfigSetAdminRequest.Delete {
    @Override
    public ConfigSetAdminRequest<Delete, ConfigSetAdminResponse> setAction(ConfigSetAction action) {
      return super.setAction(action);
    }

    @Override
    public SolrParams getParams() {
      ModifiableSolrParams params = new ModifiableSolrParams();
      if (action != null) params.set(ConfigSetParams.ACTION, action.toString());
      if (configSetName != null) params.set(NAME, configSetName);
      return params;
    }
  }

  public static class MockAuthenticationPlugin extends BasicAuthPlugin {

    @Override
    public AuthenticationProvider getAuthenticationProvider(Map<String, Object> pluginConfig) {
      return new AuthenticationProvider() {
        @Override
        public void init(Map<String, Object> ignored) {}

        @Override
        public ValidatingJsonMap getSpec() {
          return AnnotatedApi.getApis(new ModifyBasicAuthConfigAPI()).get(0).getSpec();
        }

        @Override
        public boolean authenticate(String user, String pwd) {
          return user.equals(pwd);
        }

        @Override
        public Map<String, String> getPromptHeaders() {
          return Collections.emptyMap();
        }
      };
    }

    @Override
    public boolean doAuthenticate(
        HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws Exception {
      if (request.getHeader("user") != null) {
        final Principal p = new BasicUserPrincipal("solr");
        filterChain.doFilter(wrap(request, p, "solr"), response);
        return true;
      }
      return super.doAuthenticate(request, response, filterChain);
    }

    HttpServletRequest wrap(HttpServletRequest request, Principal principal, String username) {
      return new HttpServletRequestWrapper(request) {
        @Override
        public Principal getUserPrincipal() {
          return principal;
        }

        @Override
        public String getRemoteUser() {
          return username;
        }
      };
    }
  }

  public static class MockAuthorizationPlugin implements AuthorizationPlugin {

    @Override
    public AuthorizationResponse authorize(AuthorizationContext context) {
      return AuthorizationResponse.OK;
    }

    @Override
    public void init(Map<String, Object> initInfo) {}

    @Override
    public void close() {}
  }
}
