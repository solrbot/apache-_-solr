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

import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.SolrResponse;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.request.QueryRequest;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.cloud.SolrCloudTestCase;
import org.apache.solr.common.util.EnvUtils;
import org.apache.solr.common.util.Utils;
import org.apache.solr.util.SecurityJson;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * NOTE: do *not* use real hostnames, not even "example.com", in the webcrawler tests.
 *
 * <p>A MockPageFetcher is used to prevent real HTTP requests from being executed.
 */
@SolrTestCaseJ4.SuppressSSL
public class PostToolTest extends SolrCloudTestCase {

  @BeforeClass
  public static void setupClusterWithSecurityEnabled() throws Exception {
    configureCluster(2)
        .addConfig("conf1", configset("cloud-minimal"))
        .withSecurityJson(SecurityJson.SIMPLE)
        .configure();
  }

  private <T extends SolrRequest<? extends SolrResponse>> T withBasicAuth(T req) {
    req.setBasicAuthCredentials(SecurityJson.USER, SecurityJson.PASS);
    return req;
  }

  @Test
  public void testBasicRun() throws Exception {
    final String collection = "testBasicRun";

    withBasicAuth(CollectionAdminRequest.createCollection(collection, "conf1", 1, 1, 0, 0))
        .processAndWait(cluster.getSolrClient(), 10);

    Path jsonDoc = Files.createTempFile("temp", ".json");

    BufferedWriter fw = Files.newBufferedWriter(jsonDoc, StandardCharsets.UTF_8);
    Utils.writeJson(Map.of("id", "1", "title_s", "mytitle"), fw, true);
    fw.flush();

    String[] args = {
      "post",
      "--solr-url",
      cluster.getJettySolrRunner(0).getBaseUrl().toString(),
      "--name",
      collection,
      "--credentials",
      SecurityJson.USER_PASS,
      jsonDoc.toString(),
    };
    assertEquals(0, CLITestHelper.runTool(args, PostTool.class));

    int numFound = 0;
    int expectedDocCount = 1;

    for (int idx = 0; idx < 100; ++idx) {
      QueryRequest req = withBasicAuth(new QueryRequest(params("q", "*:*")));
      QueryResponse rsp = req.process(cluster.getSolrClient(), collection);

      numFound = (int) rsp.getResults().getNumFound();
      if (numFound == expectedDocCount) {
        break;
      }
      Thread.sleep(100);
    }
    assertEquals("*:* found unexpected number of documents", expectedDocCount, numFound);
  }

  @Test
  public void testRunWithCollectionParam() throws Exception {
    final String collection = "testRunWithCollectionParam";

    // Provide the port for the PostTool to look up.
    EnvUtils.setProperty("jetty.port", cluster.getJettySolrRunner(0).getLocalPort() + "");

    withBasicAuth(CollectionAdminRequest.createCollection(collection, "conf1", 1, 1, 0, 0))
        .processAndWait(cluster.getSolrClient(), 10);

    Path jsonDoc = Files.createTempFile("temp", ".json");

    BufferedWriter fw = Files.newBufferedWriter(jsonDoc, StandardCharsets.UTF_8);
    Utils.writeJson(Map.of("id", "1", "title_s", "mytitle"), fw, true);
    fw.flush();

    String[] args = {
      "post", "-c", collection, "--credentials", SecurityJson.USER_PASS, jsonDoc.toString(),
    };
    assertEquals(0, CLITestHelper.runTool(args, PostTool.class));

    int numFound = 0;
    int expectedDocCount = 1;

    for (int idx = 0; idx < 100; ++idx) {
      QueryRequest req = withBasicAuth(new QueryRequest(params("q", "*:*")));
      QueryResponse rsp = req.process(cluster.getSolrClient(), collection);

      numFound = (int) rsp.getResults().getNumFound();
      if (numFound == expectedDocCount) {
        break;
      }
      Thread.sleep(100);
    }
    assertEquals("*:* found unexpected number of documents", expectedDocCount, numFound);
  }

  @Test
  public void testRunCsvWithCustomSeparatorParam() throws Exception {
    final String collection = "testRunCsvWithCustomSeparatorParam";

    // Provide the port for the PostTool to look up.
    EnvUtils.setProperty("jetty.port", cluster.getJettySolrRunner(0).getLocalPort() + "");

    withBasicAuth(CollectionAdminRequest.createCollection(collection, "conf1", 1, 1, 0, 0))
        .processAndWait(cluster.getSolrClient(), 10);

    Path tsvDoc = Files.createTempFile("temp", ".tsv");

    BufferedWriter fw = Files.newBufferedWriter(tsvDoc, StandardCharsets.UTF_8);
    fw.write("1\tmytitle\n");
    fw.close();

    String[] args = {
      "post",
      "-c",
      collection,
      "--credentials",
      SecurityJson.USER_PASS,
      "--params",
      "\"separator=%09&header=false&fieldnames=id,title_s\"",
      "--type",
      "text/csv",
      tsvDoc.toString(),
    };
    assertEquals(0, CLITestHelper.runTool(args, PostTool.class));

    int numFound = 0;
    int expectedDocCount = 1;

    for (int idx = 0; idx < 100; ++idx) {
      QueryRequest req = withBasicAuth(new QueryRequest(params("q", "*:*")));
      QueryResponse rsp = req.process(cluster.getSolrClient(), collection);

      numFound = (int) rsp.getResults().getNumFound();
      if (numFound == expectedDocCount) {
        break;
      }
      Thread.sleep(100);
    }
    assertEquals("*:* found unexpected number of documents", expectedDocCount, numFound);
  }

  @Test
  public void testNormalizeUrlEnding() {
    assertEquals("http://[ff01::114]", PostTool.normalizeUrlEnding("http://[ff01::114]/"));
    assertEquals(
        "http://[ff01::114]", PostTool.normalizeUrlEnding("http://[ff01::114]/#foo?bar=baz"));
    assertEquals(
        "http://[ff01::114]/index.html",
        PostTool.normalizeUrlEnding("http://[ff01::114]/index.html#hello"));
  }

  @Test
  public void testComputeFullUrl() throws IOException, URISyntaxException {
    assertEquals(
        "http://[ff01::114]/index.html",
        PostTool.computeFullUrl(URI.create("http://[ff01::114]/").toURL(), "/index.html"));
    assertEquals(
        "http://[ff01::114]/index.html",
        PostTool.computeFullUrl(URI.create("http://[ff01::114]/foo/bar/").toURL(), "/index.html"));
    assertEquals(
        "http://[ff01::114]/fil.html",
        PostTool.computeFullUrl(
            URI.create("http://[ff01::114]/foo.htm?baz#hello").toURL(), "fil.html"));
    //    TODO: How to know what is the base if URL path ends with "foo"??
    //    assertEquals("http://[ff01::114]/fil.html", t_web.computeFullUrl(new
    // URL("http://[ff01::114]/foo?baz#hello"), "fil.html"));
    assertNull(PostTool.computeFullUrl(URI.create("http://[ff01::114]/").toURL(), "fil.jpg"));
    assertNull(
        PostTool.computeFullUrl(URI.create("http://[ff01::114]/").toURL(), "mailto:hello@foo.bar"));
    assertNull(
        PostTool.computeFullUrl(URI.create("http://[ff01::114]/").toURL(), "ftp://server/file"));
  }

  @Test
  public void testTypeSupported() {
    CLITestHelper.TestingRuntime runtime = new CLITestHelper.TestingRuntime(false);
    PostTool postTool = new PostTool(runtime);

    assertTrue(postTool.typeSupported("application/pdf"));
    assertTrue(postTool.typeSupported("application/xml"));
    assertFalse(postTool.typeSupported("text/foo"));

    postTool.fileTypes = "doc,xls,ppt";
    postTool.fileFilter = postTool.getFileFilterFromFileTypes(postTool.fileTypes);
    assertFalse(postTool.typeSupported("application/pdf"));
    assertTrue(postTool.typeSupported("application/msword"));
  }

  @Test
  public void testAppendParam() {
    assertEquals(
        "http://[ff01::114]?foo=bar", PostTool.appendParam("http://[ff01::114]", "foo=bar"));
    assertEquals(
        "http://[ff01::114]/?a=b&foo=bar",
        PostTool.appendParam("http://[ff01::114]/?a=b", "foo=bar"));
  }

  @Test
  public void testAppendUrlPath() {
    assertEquals(
        URI.create("http://[ff01::114]/a?foo=bar"),
        PostTool.appendUrlPath(URI.create("http://[ff01::114]?foo=bar"), "/a"));
    assertEquals(
        URI.create("http://[ff01::114]/a?foo=bar"),
        PostTool.appendUrlPath(URI.create("http://[ff01::114]/?foo=bar"), "/a"));
    assertEquals(
        URI.create("http://[ff01::114]/a/b?foo=bar"),
        PostTool.appendUrlPath(URI.create("http://[ff01::114]/a?foo=bar"), "/b"));
    assertEquals(
        URI.create("http://[ff01::114]/a/b?foo=bar"),
        PostTool.appendUrlPath(URI.create("http://[ff01::114]/a/?foo=bar"), "/b"));
    assertEquals(
        URI.create("http://[ff01::114]/a/b?foo=bar"),
        PostTool.appendUrlPath(URI.create("http://[ff01::114]/a?foo=bar"), "b"));
    assertEquals(
        URI.create("http://[ff01::114]/a/b?foo=bar"),
        PostTool.appendUrlPath(URI.create("http://[ff01::114]/a/?foo=bar"), "b"));
  }

  @Test
  public void testGuessType() {
    Path f = Path.of("foo.doc");
    assertEquals("application/msword", PostTool.guessType(f));
    f = Path.of("foobar");
    assertEquals("application/octet-stream", PostTool.guessType(f));
    f = Path.of("foo.json");
    assertEquals("application/json", PostTool.guessType(f));
  }

  @Test
  public void testDoFilesMode() throws IOException {
    ToolRuntime runtime = new CLITestHelper.TestingRuntime(false);
    PostTool postTool = new PostTool(runtime);
    postTool.recursive = 0;
    postTool.dryRun = true;
    postTool.solrUpdateUrl = URI.create("http://localhost:8983/solr/fake/update");
    Path dir = getFile("exampledocs");
    int num = postTool.postFiles(new String[] {dir.toString()}, 0, null, null);
    assertEquals(2, num);
  }

  @Test
  public void testDetectingIfRecursionPossibleInFilesMode() throws IOException {
    ToolRuntime runtime = new CLITestHelper.TestingRuntime(false);
    PostTool postTool = new PostTool(runtime);
    postTool.recursive = 1; // This is the default
    Path dir = getFile("exampledocs");
    Path doc = Files.createTempFile("temp", ".json");
    assertTrue(postTool.recursionPossible(new String[] {dir.toString()}));
    assertFalse(postTool.recursionPossible(new String[] {doc.toString()}));
    assertTrue(postTool.recursionPossible(new String[] {doc.toString(), dir.toString()}));
  }

  @Test
  public void testRecursionAppliesToFilesMode() throws IOException {
    ToolRuntime runtime = new CLITestHelper.TestingRuntime(false);
    PostTool postTool = new PostTool(runtime);
    postTool.recursive = 1; // This is the default
    postTool.dryRun = true;
    postTool.solrUpdateUrl = URI.create("http://localhost:8983/solr/fake/update");
    Path dir = getFile("exampledocs");
    int num = postTool.postFiles(new String[] {dir.toString()}, 0, null, null);
    assertEquals(2, num);
  }

  @Test
  public void testDoWebMode() throws IOException, URISyntaxException {
    ToolRuntime runtime = new CLITestHelper.TestingRuntime(false);
    PostTool postTool = new PostTool(runtime);
    postTool.pageFetcher = new MockPageFetcher(runtime);
    postTool.dryRun = true;
    postTool.solrUpdateUrl = URI.create("http://user:password@localhost:5150/solr/fake/update");

    // Uses mock pageFetcher
    postTool.delay = 0;
    postTool.recursive = 5;
    int num = postTool.postWebPages(new String[] {"http://[ff01::114]/#removeme"}, 0, null);
    assertEquals(5, num);

    postTool.recursive = 1;
    num = postTool.postWebPages(new String[] {"http://[ff01::114]/"}, 0, null);
    assertEquals(3, num);

    // Without respecting robots.txt
    postTool.pageFetcher.robotsCache.put("[ff01::114]", Collections.emptyList());
    postTool.recursive = 5;
    num = postTool.postWebPages(new String[] {"http://[ff01::114]/#removeme"}, 0, null);
    assertEquals(6, num);
  }

  @Test
  public void testRobotsExclusion() throws IOException, URISyntaxException {
    ToolRuntime runtime = new CLITestHelper.TestingRuntime(false);
    PostTool postTool = new PostTool(runtime);
    postTool.pageFetcher = new MockPageFetcher(runtime);
    postTool.dryRun = true;

    assertFalse(
        postTool.pageFetcher.isDisallowedByRobots(URI.create("http://[ff01::114]/").toURL()));
    assertTrue(
        postTool.pageFetcher.isDisallowedByRobots(
            URI.create("http://[ff01::114]/disallowed").toURL()));
    assertEquals(
        "There should be two entries parsed from robots.txt",
        2,
        postTool.pageFetcher.robotsCache.get("[ff01::114]").size());
  }

  static class MockPageFetcher extends PostTool.PageFetcher {
    HashMap<String, String> htmlMap = new HashMap<>();
    HashMap<String, Set<URI>> linkMap = new HashMap<>();

    public MockPageFetcher(ToolRuntime runtime) throws IOException, URISyntaxException {
      (new PostTool(runtime)).super();
      htmlMap.put(
          "http://[ff01::114]",
          "<html><body><a href=\"http://[ff01::114]/page1\">page1</a><a href=\"http://[ff01::114]/page2\">page2</a></body></html>");
      htmlMap.put(
          "http://[ff01::114]/index.html",
          "<html><body><a href=\"http://[ff01::114]/page1\">page1</a><a href=\"http://[ff01::114]/page2\">page2</a></body></html>");
      htmlMap.put(
          "http://[ff01::114]/page1",
          "<html><body><a href=\"http://[ff01::114]/page1/foo\"></body></html>");
      htmlMap.put(
          "http://[ff01::114]/page1/foo",
          "<html><body><a href=\"http://[ff01::114]/page1/foo/bar\"></body></html>");
      htmlMap.put(
          "http://[ff01::114]/page1/foo/bar",
          "<html><body><a href=\"http://[ff01::114]/page1\"></body></html>");
      htmlMap.put(
          "http://[ff01::114]/page2",
          "<html><body><a href=\"http://[ff01::114]/\"><a href=\"http://[ff01::114]/disallowed\"/></body></html>");
      htmlMap.put(
          "http://[ff01::114]/disallowed",
          "<html><body><a href=\"http://[ff01::114]/\"></body></html>");

      Set<URI> s = new HashSet<>();
      s.add(new URI("http://[ff01::114]/page1"));
      s.add(new URI("http://[ff01::114]/page2"));
      linkMap.put("http://[ff01::114]", s);
      linkMap.put("http://[ff01::114]/index.html", s);
      s = new HashSet<>();
      s.add(new URI("http://[ff01::114]/page1/foo"));
      linkMap.put("http://[ff01::114]/page1", s);
      s = new HashSet<>();
      s.add(new URI("http://[ff01::114]/page1/foo/bar"));
      linkMap.put("http://[ff01::114]/page1/foo", s);
      s = new HashSet<>();
      s.add(new URI("http://[ff01::114]/disallowed"));
      linkMap.put("http://[ff01::114]/page2", s);

      // Simulate a robots.txt file with comments and a few disallows
      String sb =
          "# Comments appear after the \"#\" symbol at the start of a line, or after a directive\n"
              + "User-agent: * # match all bots\n"
              + "Disallow:  # This is void\n"
              + "Disallow: /disallow # Disallow this path\n"
              + "Disallow: /nonexistentpath # Disallow this path\n";
      this.robotsCache.put(
          "[ff01::114]",
          super.parseRobotsTxt(new ByteArrayInputStream(sb.getBytes(StandardCharsets.UTF_8))));
    }

    @Override
    public PostTool.PageFetcherResult readPageFromUrl(URL u) {
      PostTool.PageFetcherResult res = new PostTool.PageFetcherResult();
      if (isDisallowedByRobots(u)) {
        res.httpStatus = 403;
        return res;
      }
      res.httpStatus = 200;
      res.contentType = "text/html";
      res.content = ByteBuffer.wrap(htmlMap.get(u.toString()).getBytes(StandardCharsets.UTF_8));
      return res;
    }

    @Override
    public Set<URI> getLinksFromWebPage(URL url, InputStream is, String type, URI postUri) {
      Set<URI> s = linkMap.get(PostTool.normalizeUrlEnding(url.toString()));
      if (s == null) {
        s = new HashSet<>();
      }
      return s;
    }
  }
}
