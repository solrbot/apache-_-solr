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
package org.apache.solr.uninverting;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.DoublePoint;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FloatPoint;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.MultiReader;
import org.apache.lucene.index.NoMergePolicy;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.Directory;
import org.apache.lucene.tests.analysis.MockAnalyzer;
import org.apache.lucene.tests.index.RandomIndexWriter;
import org.apache.lucene.tests.util.TestUtil;
import org.apache.solr.SolrTestCase;
import org.apache.solr.legacy.LegacyDoubleField;
import org.apache.solr.legacy.LegacyFloatField;
import org.apache.solr.legacy.LegacyIntField;
import org.apache.solr.legacy.LegacyLongField;
import org.apache.solr.uninverting.UninvertingReader.Type;

/*
 * Tests sorting (but with fieldcache instead of docvalues)
 */
public class TestFieldCacheSort extends SolrTestCase {

  public void testString() throws IOException {
    testString(SortField.Type.STRING);
  }

  public void testStringVal() throws Exception {
    testString(SortField.Type.STRING_VAL);
  }

  /** Tests sorting on type string */
  private void testString(SortField.Type sortType) throws IOException {
    Directory dir = newDirectory();
    RandomIndexWriter writer = new RandomIndexWriter(random(), dir);
    Document doc = new Document();
    doc.add(newStringField("value", "foo", Field.Store.YES));
    writer.addDocument(doc);
    doc = new Document();
    doc.add(newStringField("value", "bar", Field.Store.YES));
    writer.addDocument(doc);
    Type type = sortType == SortField.Type.STRING ? Type.SORTED : Type.BINARY;
    IndexReader ir =
        UninvertingReader.wrap(writer.getReader(), Collections.singletonMap("value", type));
    writer.close();

    IndexSearcher searcher = newSearcher(ir);
    Sort sort = new Sort(new SortField("value", sortType));

    TopDocs td = searcher.search(new MatchAllDocsQuery(), 10, sort);
    assertEquals(2, td.totalHits.value);
    // 'bar' comes before 'foo'
    assertEquals("bar", searcher.storedFields().document(td.scoreDocs[0].doc).get("value"));
    assertEquals("foo", searcher.storedFields().document(td.scoreDocs[1].doc).get("value"));

    TestUtil.checkReader(ir);
    ir.close();
    dir.close();
  }

  public void testStringMissing() throws IOException {
    testStringMissing(SortField.Type.STRING);
  }

  public void testStringValMissing() throws IOException {
    testStringMissing(SortField.Type.STRING_VAL);
  }

  /** Tests sorting on type string with a missing value */
  private void testStringMissing(SortField.Type sortType) throws IOException {
    Directory dir = newDirectory();
    RandomIndexWriter writer = new RandomIndexWriter(random(), dir);
    Document doc = new Document();
    writer.addDocument(doc);
    doc = new Document();
    doc.add(newStringField("value", "foo", Field.Store.YES));
    writer.addDocument(doc);
    doc = new Document();
    doc.add(newStringField("value", "bar", Field.Store.YES));
    writer.addDocument(doc);
    Type type = sortType == SortField.Type.STRING ? Type.SORTED : Type.BINARY;
    IndexReader ir =
        UninvertingReader.wrap(writer.getReader(), Collections.singletonMap("value", type));
    writer.close();

    IndexSearcher searcher = newSearcher(ir);
    Sort sort = new Sort(new SortField("value", sortType));

    TopDocs td = searcher.search(new MatchAllDocsQuery(), 10, sort);
    assertEquals(3, td.totalHits.value);
    // null comes first
    assertNull(searcher.storedFields().document(td.scoreDocs[0].doc).get("value"));
    assertEquals("bar", searcher.storedFields().document(td.scoreDocs[1].doc).get("value"));
    assertEquals("foo", searcher.storedFields().document(td.scoreDocs[2].doc).get("value"));
    TestUtil.checkReader(ir);
    ir.close();
    dir.close();
  }

  public void testStringReverse() throws IOException {
    testStringReverse(SortField.Type.STRING);
  }

  public void testStringValReverse() throws IOException {
    testStringReverse(SortField.Type.STRING_VAL);
  }

  /** Tests reverse sorting on type string */
  private void testStringReverse(SortField.Type sortType) throws IOException {
    Directory dir = newDirectory();
    RandomIndexWriter writer = new RandomIndexWriter(random(), dir);
    Document doc = new Document();
    doc.add(newStringField("value", "bar", Field.Store.YES));
    writer.addDocument(doc);
    doc = new Document();
    doc.add(newStringField("value", "foo", Field.Store.YES));
    writer.addDocument(doc);
    Type type = sortType == SortField.Type.STRING ? Type.SORTED : Type.BINARY;
    IndexReader ir =
        UninvertingReader.wrap(writer.getReader(), Collections.singletonMap("value", type));
    writer.close();

    IndexSearcher searcher = newSearcher(ir);
    Sort sort = new Sort(new SortField("value", sortType, true));

    TopDocs td = searcher.search(new MatchAllDocsQuery(), 10, sort);
    assertEquals(2, td.totalHits.value);
    // 'foo' comes after 'bar' in reverse order
    assertEquals("foo", searcher.storedFields().document(td.scoreDocs[0].doc).get("value"));
    assertEquals("bar", searcher.storedFields().document(td.scoreDocs[1].doc).get("value"));
    TestUtil.checkReader(ir);
    ir.close();
    dir.close();
  }

  public void testStringMissingSortedFirst() throws IOException {
    testStringMissingSortedFirst(SortField.Type.STRING);
  }

  public void testStringValMissingSortedFirst() throws IOException {
    testStringMissingSortedFirst(SortField.Type.STRING_VAL);
  }

  /** Tests sorting on type string with a missing value sorted first */
  private void testStringMissingSortedFirst(SortField.Type sortType) throws IOException {
    Directory dir = newDirectory();
    RandomIndexWriter writer = new RandomIndexWriter(random(), dir);
    Document doc = new Document();
    writer.addDocument(doc);
    doc = new Document();
    doc.add(newStringField("value", "foo", Field.Store.YES));
    writer.addDocument(doc);
    doc = new Document();
    doc.add(newStringField("value", "bar", Field.Store.YES));
    writer.addDocument(doc);
    Type type = sortType == SortField.Type.STRING ? Type.SORTED : Type.BINARY;
    IndexReader ir =
        UninvertingReader.wrap(writer.getReader(), Collections.singletonMap("value", type));
    writer.close();

    IndexSearcher searcher = newSearcher(ir);
    SortField sf = new SortField("value", sortType);
    Sort sort = new Sort(sf);

    TopDocs td = searcher.search(new MatchAllDocsQuery(), 10, sort);
    assertEquals(3, td.totalHits.value);
    // null comes first
    assertNull(searcher.storedFields().document(td.scoreDocs[0].doc).get("value"));
    assertEquals("bar", searcher.storedFields().document(td.scoreDocs[1].doc).get("value"));
    assertEquals("foo", searcher.storedFields().document(td.scoreDocs[2].doc).get("value"));
    TestUtil.checkReader(ir);
    ir.close();
    dir.close();
  }

  public void testStringMissingSortedFirstReverse() throws IOException {
    testStringMissingSortedFirstReverse(SortField.Type.STRING);
  }

  public void testStringValMissingSortedFirstReverse() throws IOException {
    testStringMissingSortedFirstReverse(SortField.Type.STRING_VAL);
  }

  /** Tests reverse sorting on type string with a missing value sorted first */
  private void testStringMissingSortedFirstReverse(SortField.Type sortType) throws IOException {
    Directory dir = newDirectory();
    RandomIndexWriter writer = new RandomIndexWriter(random(), dir);
    Document doc = new Document();
    writer.addDocument(doc);
    doc = new Document();
    doc.add(newStringField("value", "foo", Field.Store.YES));
    writer.addDocument(doc);
    doc = new Document();
    doc.add(newStringField("value", "bar", Field.Store.YES));
    writer.addDocument(doc);
    Type type = sortType == SortField.Type.STRING ? Type.SORTED : Type.BINARY;
    IndexReader ir =
        UninvertingReader.wrap(writer.getReader(), Collections.singletonMap("value", type));
    writer.close();

    IndexSearcher searcher = newSearcher(ir);
    SortField sf = new SortField("value", sortType, true);
    Sort sort = new Sort(sf);

    TopDocs td = searcher.search(new MatchAllDocsQuery(), 10, sort);
    assertEquals(3, td.totalHits.value);
    assertEquals("foo", searcher.storedFields().document(td.scoreDocs[0].doc).get("value"));
    assertEquals("bar", searcher.storedFields().document(td.scoreDocs[1].doc).get("value"));
    // null comes last
    assertNull(searcher.storedFields().document(td.scoreDocs[2].doc).get("value"));
    TestUtil.checkReader(ir);
    ir.close();
    dir.close();
  }

  public void testStringMissingSortedLast() throws IOException {
    testStringMissingSortedLast(SortField.Type.STRING);
  }

  public void testStringValMissingSortedLast() throws IOException {
    testStringMissingSortedLast(SortField.Type.STRING_VAL);
  }

  /** Tests sorting on type string with a missing value sorted last */
  private void testStringMissingSortedLast(SortField.Type sortType) throws IOException {
    Directory dir = newDirectory();
    RandomIndexWriter writer = new RandomIndexWriter(random(), dir);
    Document doc = new Document();
    writer.addDocument(doc);
    doc = new Document();
    doc.add(newStringField("value", "foo", Field.Store.YES));
    writer.addDocument(doc);
    doc = new Document();
    doc.add(newStringField("value", "bar", Field.Store.YES));
    writer.addDocument(doc);
    Type type = sortType == SortField.Type.STRING ? Type.SORTED : Type.BINARY;
    IndexReader ir =
        UninvertingReader.wrap(writer.getReader(), Collections.singletonMap("value", type));
    writer.close();

    IndexSearcher searcher = newSearcher(ir);
    SortField sf = new SortField("value", sortType);
    sf.setMissingValue(SortField.STRING_LAST);
    Sort sort = new Sort(sf);

    TopDocs td = searcher.search(new MatchAllDocsQuery(), 10, sort);
    assertEquals(3, td.totalHits.value);
    assertEquals("bar", searcher.storedFields().document(td.scoreDocs[0].doc).get("value"));
    assertEquals("foo", searcher.storedFields().document(td.scoreDocs[1].doc).get("value"));
    // null comes last
    assertNull(searcher.storedFields().document(td.scoreDocs[2].doc).get("value"));
    TestUtil.checkReader(ir);
    ir.close();
    dir.close();
  }

  public void testStringMissingSortedLastReverse() throws IOException {
    testStringMissingSortedLastReverse(SortField.Type.STRING);
  }

  public void testStringValMissingSortedLastReverse() throws IOException {
    testStringMissingSortedLastReverse(SortField.Type.STRING_VAL);
  }

  /** Tests reverse sorting on type string with a missing value sorted last */
  private void testStringMissingSortedLastReverse(SortField.Type sortType) throws IOException {
    Directory dir = newDirectory();
    RandomIndexWriter writer = new RandomIndexWriter(random(), dir);
    Document doc = new Document();
    writer.addDocument(doc);
    doc = new Document();
    doc.add(newStringField("value", "foo", Field.Store.YES));
    writer.addDocument(doc);
    doc = new Document();
    doc.add(newStringField("value", "bar", Field.Store.YES));
    writer.addDocument(doc);
    Type type = sortType == SortField.Type.STRING ? Type.SORTED : Type.BINARY;
    IndexReader ir =
        UninvertingReader.wrap(writer.getReader(), Collections.singletonMap("value", type));
    writer.close();

    IndexSearcher searcher = newSearcher(ir);
    SortField sf = new SortField("value", sortType, true);
    sf.setMissingValue(SortField.STRING_LAST);
    Sort sort = new Sort(sf);

    TopDocs td = searcher.search(new MatchAllDocsQuery(), 10, sort);
    assertEquals(3, td.totalHits.value);
    // null comes first
    assertNull(searcher.storedFields().document(td.scoreDocs[0].doc).get("value"));
    assertEquals("foo", searcher.storedFields().document(td.scoreDocs[1].doc).get("value"));
    assertEquals("bar", searcher.storedFields().document(td.scoreDocs[2].doc).get("value"));
    TestUtil.checkReader(ir);
    ir.close();
    dir.close();
  }

  /** Tests sorting on internal docid order */
  public void testFieldDoc() throws Exception {
    Directory dir = newDirectory();
    RandomIndexWriter writer = new RandomIndexWriter(random(), dir);
    Document doc = new Document();
    doc.add(newStringField("value", "foo", Field.Store.NO));
    writer.addDocument(doc);
    doc = new Document();
    doc.add(newStringField("value", "bar", Field.Store.NO));
    writer.addDocument(doc);
    IndexReader ir = writer.getReader();
    writer.close();

    IndexSearcher searcher = newSearcher(ir);
    Sort sort = new Sort(SortField.FIELD_DOC);

    TopDocs td = searcher.search(new MatchAllDocsQuery(), 10, sort);
    assertEquals(2, td.totalHits.value);
    // docid 0, then docid 1
    assertEquals(0, td.scoreDocs[0].doc);
    assertEquals(1, td.scoreDocs[1].doc);
    TestUtil.checkReader(ir);
    ir.close();
    dir.close();
  }

  /** Tests sorting on reverse internal docid order */
  public void testFieldDocReverse() throws Exception {
    Directory dir = newDirectory();
    RandomIndexWriter writer = new RandomIndexWriter(random(), dir);
    Document doc = new Document();
    doc.add(newStringField("value", "foo", Field.Store.NO));
    writer.addDocument(doc);
    doc = new Document();
    doc.add(newStringField("value", "bar", Field.Store.NO));
    writer.addDocument(doc);
    IndexReader ir = writer.getReader();
    writer.close();

    IndexSearcher searcher = newSearcher(ir);
    Sort sort = new Sort(new SortField(null, SortField.Type.DOC, true));

    TopDocs td = searcher.search(new MatchAllDocsQuery(), 10, sort);
    assertEquals(2, td.totalHits.value);
    // docid 1, then docid 0
    assertEquals(1, td.scoreDocs[0].doc);
    assertEquals(0, td.scoreDocs[1].doc);
    TestUtil.checkReader(ir);
    ir.close();
    dir.close();
  }

  /** Tests default sort (by score) */
  public void testFieldScore() throws Exception {
    Directory dir = newDirectory();
    RandomIndexWriter writer = new RandomIndexWriter(random(), dir);
    Document doc = new Document();
    doc.add(newTextField("value", "foo bar bar bar bar", Field.Store.NO));
    writer.addDocument(doc);
    doc = new Document();
    doc.add(newTextField("value", "foo foo foo foo foo", Field.Store.NO));
    writer.addDocument(doc);
    IndexReader ir = writer.getReader();
    writer.close();

    IndexSearcher searcher = newSearcher(ir);
    Sort sort = new Sort();

    TopDocs actual = searcher.search(new TermQuery(new Term("value", "foo")), 10, sort);
    assertEquals(2, actual.totalHits.value);

    TopDocs expected = searcher.search(new TermQuery(new Term("value", "foo")), 10);
    // the two topdocs should be the same
    assertEquals(expected.totalHits.value, actual.totalHits.value);
    for (int i = 0; i < actual.scoreDocs.length; i++) {
      assertEquals(actual.scoreDocs[i].doc, expected.scoreDocs[i].doc);
    }
    TestUtil.checkReader(ir);
    ir.close();
    dir.close();
  }

  /** Tests default sort (by score) in reverse */
  public void testFieldScoreReverse() throws Exception {
    Directory dir = newDirectory();
    RandomIndexWriter writer = new RandomIndexWriter(random(), dir);
    Document doc = new Document();
    doc.add(newTextField("value", "foo bar bar bar bar", Field.Store.NO));
    writer.addDocument(doc);
    doc = new Document();
    doc.add(newTextField("value", "foo foo foo foo foo", Field.Store.NO));
    writer.addDocument(doc);
    IndexReader ir = writer.getReader();
    writer.close();

    IndexSearcher searcher = newSearcher(ir);
    // this test expects the freq to make doc 1 scores greater than doc 0
    searcher.setSimilarity(new BM25Similarity());
    Sort sort = new Sort(new SortField(null, SortField.Type.SCORE, true));

    TopDocs actual = searcher.search(new TermQuery(new Term("value", "foo")), 10, sort);
    assertEquals(2, actual.totalHits.value);

    TopDocs expected = searcher.search(new TermQuery(new Term("value", "foo")), 10);
    // the two topdocs should be the reverse of each other
    assertEquals(expected.totalHits.value, actual.totalHits.value);
    assertEquals(actual.scoreDocs[0].doc, expected.scoreDocs[1].doc);
    assertEquals(actual.scoreDocs[1].doc, expected.scoreDocs[0].doc);
    TestUtil.checkReader(ir);
    ir.close();
    dir.close();
  }

  /** Tests sorting on type int */
  public void testInt() throws IOException {
    Directory dir = newDirectory();
    RandomIndexWriter writer = new RandomIndexWriter(random(), dir);
    Document doc = new Document();
    doc.add(new IntPoint("value", 300000));
    doc.add(new StoredField("value", 300000));
    writer.addDocument(doc);
    doc = new Document();
    doc.add(new IntPoint("value", -1));
    doc.add(new StoredField("value", -1));
    writer.addDocument(doc);
    doc = new Document();
    doc.add(new IntPoint("value", 4));
    doc.add(new StoredField("value", 4));
    writer.addDocument(doc);
    IndexReader ir =
        UninvertingReader.wrap(
            writer.getReader(), Collections.singletonMap("value", Type.INTEGER_POINT));
    writer.close();

    IndexSearcher searcher = newSearcher(ir, false);
    Sort sort = new Sort(new SortField("value", SortField.Type.INT));

    TopDocs td = searcher.search(new MatchAllDocsQuery(), 10, sort);
    assertEquals(3, td.totalHits.value);
    // numeric order
    assertEquals("-1", searcher.storedFields().document(td.scoreDocs[0].doc).get("value"));
    assertEquals("4", searcher.storedFields().document(td.scoreDocs[1].doc).get("value"));
    assertEquals("300000", searcher.storedFields().document(td.scoreDocs[2].doc).get("value"));
    TestUtil.checkReader(ir);
    ir.close();
    dir.close();
  }

  /** Tests sorting on type int with a missing value */
  public void testIntMissing() throws IOException {
    Directory dir = newDirectory();
    RandomIndexWriter writer = new RandomIndexWriter(random(), dir);
    Document doc = new Document();
    writer.addDocument(doc);
    doc = new Document();
    doc.add(new IntPoint("value", -1));
    doc.add(new StoredField("value", -1));
    writer.addDocument(doc);
    doc = new Document();
    doc.add(new IntPoint("value", 4));
    doc.add(new StoredField("value", 4));
    writer.addDocument(doc);
    IndexReader ir =
        UninvertingReader.wrap(
            writer.getReader(), Collections.singletonMap("value", Type.INTEGER_POINT));
    writer.close();

    IndexSearcher searcher = newSearcher(ir, false);
    Sort sort = new Sort(new SortField("value", SortField.Type.INT));

    TopDocs td = searcher.search(new MatchAllDocsQuery(), 10, sort);
    assertEquals(3, td.totalHits.value);
    // null is treated as a 0
    assertEquals("-1", searcher.storedFields().document(td.scoreDocs[0].doc).get("value"));
    assertNull(searcher.storedFields().document(td.scoreDocs[1].doc).get("value"));
    assertEquals("4", searcher.storedFields().document(td.scoreDocs[2].doc).get("value"));
    TestUtil.checkReader(ir);
    ir.close();
    dir.close();
  }

  /**
   * Tests sorting on type int, specifying the missing value should be treated as Integer.MAX_VALUE
   */
  public void testIntMissingLast() throws IOException {
    Directory dir = newDirectory();
    RandomIndexWriter writer = new RandomIndexWriter(random(), dir);
    Document doc = new Document();
    writer.addDocument(doc);
    doc = new Document();
    doc.add(new IntPoint("value", -1));
    doc.add(new StoredField("value", -1));
    writer.addDocument(doc);
    doc = new Document();
    doc.add(new IntPoint("value", 4));
    doc.add(new StoredField("value", 4));
    writer.addDocument(doc);
    IndexReader ir =
        UninvertingReader.wrap(
            writer.getReader(), Collections.singletonMap("value", Type.INTEGER_POINT));
    writer.close();

    IndexSearcher searcher = newSearcher(ir, false);
    SortField sortField = new SortField("value", SortField.Type.INT);
    sortField.setMissingValue(Integer.MAX_VALUE);
    Sort sort = new Sort(sortField);

    TopDocs td = searcher.search(new MatchAllDocsQuery(), 10, sort);
    assertEquals(3, td.totalHits.value);
    // null is treated as an Integer.MAX_VALUE
    assertEquals("-1", searcher.storedFields().document(td.scoreDocs[0].doc).get("value"));
    assertEquals("4", searcher.storedFields().document(td.scoreDocs[1].doc).get("value"));
    assertNull(searcher.storedFields().document(td.scoreDocs[2].doc).get("value"));
    TestUtil.checkReader(ir);
    ir.close();
    dir.close();
  }

  /** Tests sorting on type int in reverse */
  public void testIntReverse() throws IOException {
    Directory dir = newDirectory();
    RandomIndexWriter writer = new RandomIndexWriter(random(), dir);
    Document doc = new Document();
    doc.add(new IntPoint("value", 300000));
    doc.add(new StoredField("value", 300000));
    writer.addDocument(doc);
    doc = new Document();
    doc.add(new IntPoint("value", -1));
    doc.add(new StoredField("value", -1));
    writer.addDocument(doc);
    doc = new Document();
    doc.add(new IntPoint("value", 4));
    doc.add(new StoredField("value", 4));
    writer.addDocument(doc);
    IndexReader ir =
        UninvertingReader.wrap(
            writer.getReader(), Collections.singletonMap("value", Type.INTEGER_POINT));
    writer.close();

    IndexSearcher searcher = newSearcher(ir, false);
    Sort sort = new Sort(new SortField("value", SortField.Type.INT, true));

    TopDocs td = searcher.search(new MatchAllDocsQuery(), 10, sort);
    assertEquals(3, td.totalHits.value);
    // reverse numeric order
    assertEquals("300000", searcher.storedFields().document(td.scoreDocs[0].doc).get("value"));
    assertEquals("4", searcher.storedFields().document(td.scoreDocs[1].doc).get("value"));
    assertEquals("-1", searcher.storedFields().document(td.scoreDocs[2].doc).get("value"));
    TestUtil.checkReader(ir);
    ir.close();
    dir.close();
  }

  /** Tests sorting on type legacy int */
  public void testLegacyInt() throws IOException {
    Directory dir = newDirectory();
    RandomIndexWriter writer = new RandomIndexWriter(random(), dir);
    Document doc = new Document();
    doc.add(new LegacyIntField("value", 300000, Field.Store.YES));
    writer.addDocument(doc);
    doc = new Document();
    doc.add(new LegacyIntField("value", -1, Field.Store.YES));
    writer.addDocument(doc);
    doc = new Document();
    doc.add(new LegacyIntField("value", 4, Field.Store.YES));
    writer.addDocument(doc);
    IndexReader ir =
        UninvertingReader.wrap(
            writer.getReader(), Collections.singletonMap("value", Type.LEGACY_INTEGER));
    writer.close();

    IndexSearcher searcher = newSearcher(ir);
    Sort sort = new Sort(new SortField("value", SortField.Type.INT));

    TopDocs td = searcher.search(new MatchAllDocsQuery(), 10, sort);
    assertEquals(3, td.totalHits.value);
    // numeric order
    assertEquals("-1", searcher.storedFields().document(td.scoreDocs[0].doc).get("value"));
    assertEquals("4", searcher.storedFields().document(td.scoreDocs[1].doc).get("value"));
    assertEquals("300000", searcher.storedFields().document(td.scoreDocs[2].doc).get("value"));
    TestUtil.checkReader(ir);
    ir.close();
    dir.close();
  }

  /** Tests sorting on type legacy int with a missing value */
  public void testLegacyIntMissing() throws IOException {
    Directory dir = newDirectory();
    RandomIndexWriter writer = new RandomIndexWriter(random(), dir);
    Document doc = new Document();
    writer.addDocument(doc);
    doc = new Document();
    doc.add(new LegacyIntField("value", -1, Field.Store.YES));
    writer.addDocument(doc);
    doc = new Document();
    doc.add(new LegacyIntField("value", 4, Field.Store.YES));
    writer.addDocument(doc);
    IndexReader ir =
        UninvertingReader.wrap(
            writer.getReader(), Collections.singletonMap("value", Type.LEGACY_INTEGER));
    writer.close();

    IndexSearcher searcher = newSearcher(ir);
    Sort sort = new Sort(new SortField("value", SortField.Type.INT));

    TopDocs td = searcher.search(new MatchAllDocsQuery(), 10, sort);
    assertEquals(3, td.totalHits.value);
    // null is treated as a 0
    assertEquals("-1", searcher.storedFields().document(td.scoreDocs[0].doc).get("value"));
    assertNull(searcher.storedFields().document(td.scoreDocs[1].doc).get("value"));
    assertEquals("4", searcher.storedFields().document(td.scoreDocs[2].doc).get("value"));
    TestUtil.checkReader(ir);
    ir.close();
    dir.close();
  }

  /**
   * Tests sorting on type legacy int, specifying the missing value should be treated as
   * Integer.MAX_VALUE
   */
  public void testLegacyIntMissingLast() throws IOException {
    Directory dir = newDirectory();
    RandomIndexWriter writer = new RandomIndexWriter(random(), dir);
    Document doc = new Document();
    writer.addDocument(doc);
    doc = new Document();
    doc.add(new LegacyIntField("value", -1, Field.Store.YES));
    writer.addDocument(doc);
    doc = new Document();
    doc.add(new LegacyIntField("value", 4, Field.Store.YES));
    writer.addDocument(doc);
    IndexReader ir =
        UninvertingReader.wrap(
            writer.getReader(), Collections.singletonMap("value", Type.LEGACY_INTEGER));
    writer.close();

    IndexSearcher searcher = newSearcher(ir);
    SortField sortField = new SortField("value", SortField.Type.INT);
    sortField.setMissingValue(Integer.MAX_VALUE);
    Sort sort = new Sort(sortField);

    TopDocs td = searcher.search(new MatchAllDocsQuery(), 10, sort);
    assertEquals(3, td.totalHits.value);
    // null is treated as an Integer.MAX_VALUE
    assertEquals("-1", searcher.storedFields().document(td.scoreDocs[0].doc).get("value"));
    assertEquals("4", searcher.storedFields().document(td.scoreDocs[1].doc).get("value"));
    assertNull(searcher.storedFields().document(td.scoreDocs[2].doc).get("value"));
    TestUtil.checkReader(ir);
    ir.close();
    dir.close();
  }

  /** Tests sorting on type legacy int in reverse */
  public void testLegacyIntReverse() throws IOException {
    Directory dir = newDirectory();
    RandomIndexWriter writer = new RandomIndexWriter(random(), dir);
    Document doc = new Document();
    doc.add(new LegacyIntField("value", 300000, Field.Store.YES));
    writer.addDocument(doc);
    doc = new Document();
    doc.add(new LegacyIntField("value", -1, Field.Store.YES));
    writer.addDocument(doc);
    doc = new Document();
    doc.add(new LegacyIntField("value", 4, Field.Store.YES));
    writer.addDocument(doc);
    IndexReader ir =
        UninvertingReader.wrap(
            writer.getReader(), Collections.singletonMap("value", Type.LEGACY_INTEGER));
    writer.close();

    IndexSearcher searcher = newSearcher(ir);
    Sort sort = new Sort(new SortField("value", SortField.Type.INT, true));

    TopDocs td = searcher.search(new MatchAllDocsQuery(), 10, sort);
    assertEquals(3, td.totalHits.value);
    // reverse numeric order
    assertEquals("300000", searcher.storedFields().document(td.scoreDocs[0].doc).get("value"));
    assertEquals("4", searcher.storedFields().document(td.scoreDocs[1].doc).get("value"));
    assertEquals("-1", searcher.storedFields().document(td.scoreDocs[2].doc).get("value"));
    TestUtil.checkReader(ir);
    ir.close();
    dir.close();
  }

  /** Tests sorting on type long */
  public void testLong() throws IOException {
    Directory dir = newDirectory();
    RandomIndexWriter writer = new RandomIndexWriter(random(), dir);
    Document doc = new Document();
    doc.add(new LongPoint("value", 3000000000L));
    doc.add(new StoredField("value", 3000000000L));
    writer.addDocument(doc);
    doc = new Document();
    doc.add(new LongPoint("value", -1));
    doc.add(new StoredField("value", -1));
    writer.addDocument(doc);
    doc = new Document();
    doc.add(new LongPoint("value", 4));
    doc.add(new StoredField("value", 4));
    writer.addDocument(doc);
    IndexReader ir =
        UninvertingReader.wrap(
            writer.getReader(), Collections.singletonMap("value", Type.LONG_POINT));
    writer.close();

    IndexSearcher searcher = newSearcher(ir, false);
    Sort sort = new Sort(new SortField("value", SortField.Type.LONG));

    TopDocs td = searcher.search(new MatchAllDocsQuery(), 10, sort);
    assertEquals(3, td.totalHits.value);
    // numeric order
    assertEquals("-1", searcher.storedFields().document(td.scoreDocs[0].doc).get("value"));
    assertEquals("4", searcher.storedFields().document(td.scoreDocs[1].doc).get("value"));
    assertEquals("3000000000", searcher.storedFields().document(td.scoreDocs[2].doc).get("value"));
    TestUtil.checkReader(ir);
    ir.close();
    dir.close();
  }

  /** Tests sorting on type long with a missing value */
  public void testLongMissing() throws IOException {
    Directory dir = newDirectory();
    RandomIndexWriter writer = new RandomIndexWriter(random(), dir);
    Document doc = new Document();
    writer.addDocument(doc);
    doc = new Document();
    doc.add(new LongPoint("value", -1));
    doc.add(new StoredField("value", -1));
    writer.addDocument(doc);
    doc = new Document();
    doc.add(new LongPoint("value", 4));
    doc.add(new StoredField("value", 4));
    writer.addDocument(doc);
    IndexReader ir =
        UninvertingReader.wrap(
            writer.getReader(), Collections.singletonMap("value", Type.LONG_POINT));
    writer.close();

    IndexSearcher searcher = newSearcher(ir, false);
    Sort sort = new Sort(new SortField("value", SortField.Type.LONG));

    TopDocs td = searcher.search(new MatchAllDocsQuery(), 10, sort);
    assertEquals(3, td.totalHits.value);
    // null is treated as 0
    assertEquals("-1", searcher.storedFields().document(td.scoreDocs[0].doc).get("value"));
    assertNull(searcher.storedFields().document(td.scoreDocs[1].doc).get("value"));
    assertEquals("4", searcher.storedFields().document(td.scoreDocs[2].doc).get("value"));
    TestUtil.checkReader(ir);
    ir.close();
    dir.close();
  }

  /**
   * Tests sorting on type long, specifying the missing value should be treated as Long.MAX_VALUE
   */
  public void testLongMissingLast() throws IOException {
    Directory dir = newDirectory();
    RandomIndexWriter writer = new RandomIndexWriter(random(), dir);
    Document doc = new Document();
    writer.addDocument(doc);
    doc = new Document();
    doc.add(new LongPoint("value", -1));
    doc.add(new StoredField("value", -1));
    writer.addDocument(doc);
    doc = new Document();
    doc.add(new LongPoint("value", 4));
    doc.add(new StoredField("value", 4));
    writer.addDocument(doc);
    IndexReader ir =
        UninvertingReader.wrap(
            writer.getReader(), Collections.singletonMap("value", Type.LONG_POINT));
    writer.close();

    IndexSearcher searcher = newSearcher(ir, false);
    SortField sortField = new SortField("value", SortField.Type.LONG);
    sortField.setMissingValue(Long.MAX_VALUE);
    Sort sort = new Sort(sortField);

    TopDocs td = searcher.search(new MatchAllDocsQuery(), 10, sort);
    assertEquals(3, td.totalHits.value);
    // null is treated as Long.MAX_VALUE
    assertEquals("-1", searcher.storedFields().document(td.scoreDocs[0].doc).get("value"));
    assertEquals("4", searcher.storedFields().document(td.scoreDocs[1].doc).get("value"));
    assertNull(searcher.storedFields().document(td.scoreDocs[2].doc).get("value"));
    TestUtil.checkReader(ir);
    ir.close();
    dir.close();
  }

  /** Tests sorting on type long in reverse */
  public void testLongReverse() throws IOException {
    Directory dir = newDirectory();
    RandomIndexWriter writer = new RandomIndexWriter(random(), dir);
    Document doc = new Document();
    doc.add(new LongPoint("value", 3000000000L));
    doc.add(new StoredField("value", 3000000000L));
    writer.addDocument(doc);
    doc = new Document();
    doc.add(new LongPoint("value", -1));
    doc.add(new StoredField("value", -1));
    writer.addDocument(doc);
    doc = new Document();
    doc.add(new LongPoint("value", 4));
    doc.add(new StoredField("value", 4));
    writer.addDocument(doc);
    IndexReader ir =
        UninvertingReader.wrap(
            writer.getReader(), Collections.singletonMap("value", Type.LONG_POINT));
    writer.close();

    IndexSearcher searcher = newSearcher(ir, false);
    Sort sort = new Sort(new SortField("value", SortField.Type.LONG, true));

    TopDocs td = searcher.search(new MatchAllDocsQuery(), 10, sort);
    assertEquals(3, td.totalHits.value);
    // reverse numeric order
    assertEquals("3000000000", searcher.storedFields().document(td.scoreDocs[0].doc).get("value"));
    assertEquals("4", searcher.storedFields().document(td.scoreDocs[1].doc).get("value"));
    assertEquals("-1", searcher.storedFields().document(td.scoreDocs[2].doc).get("value"));
    TestUtil.checkReader(ir);
    ir.close();
    dir.close();
  }

  /** Tests sorting on type legacy long */
  public void testLegacyLong() throws IOException {
    Directory dir = newDirectory();
    RandomIndexWriter writer = new RandomIndexWriter(random(), dir);
    Document doc = new Document();
    doc.add(new LegacyLongField("value", 3000000000L, Field.Store.YES));
    writer.addDocument(doc);
    doc = new Document();
    doc.add(new LegacyLongField("value", -1, Field.Store.YES));
    writer.addDocument(doc);
    doc = new Document();
    doc.add(new LegacyLongField("value", 4, Field.Store.YES));
    writer.addDocument(doc);
    IndexReader ir =
        UninvertingReader.wrap(
            writer.getReader(), Collections.singletonMap("value", Type.LEGACY_LONG));
    writer.close();

    IndexSearcher searcher = newSearcher(ir);
    Sort sort = new Sort(new SortField("value", SortField.Type.LONG));

    TopDocs td = searcher.search(new MatchAllDocsQuery(), 10, sort);
    assertEquals(3, td.totalHits.value);
    // numeric order
    assertEquals("-1", searcher.storedFields().document(td.scoreDocs[0].doc).get("value"));
    assertEquals("4", searcher.storedFields().document(td.scoreDocs[1].doc).get("value"));
    assertEquals("3000000000", searcher.storedFields().document(td.scoreDocs[2].doc).get("value"));
    TestUtil.checkReader(ir);
    ir.close();
    dir.close();
  }

  /** Tests sorting on type legacy long with a missing value */
  public void testLegacyLongMissing() throws IOException {
    Directory dir = newDirectory();
    RandomIndexWriter writer = new RandomIndexWriter(random(), dir);
    Document doc = new Document();
    writer.addDocument(doc);
    doc = new Document();
    doc.add(new LegacyLongField("value", -1, Field.Store.YES));
    writer.addDocument(doc);
    doc = new Document();
    doc.add(new LegacyLongField("value", 4, Field.Store.YES));
    writer.addDocument(doc);
    IndexReader ir =
        UninvertingReader.wrap(
            writer.getReader(), Collections.singletonMap("value", Type.LEGACY_LONG));
    writer.close();

    IndexSearcher searcher = newSearcher(ir);
    Sort sort = new Sort(new SortField("value", SortField.Type.LONG));

    TopDocs td = searcher.search(new MatchAllDocsQuery(), 10, sort);
    assertEquals(3, td.totalHits.value);
    // null is treated as 0
    assertEquals("-1", searcher.storedFields().document(td.scoreDocs[0].doc).get("value"));
    assertNull(searcher.storedFields().document(td.scoreDocs[1].doc).get("value"));
    assertEquals("4", searcher.storedFields().document(td.scoreDocs[2].doc).get("value"));
    TestUtil.checkReader(ir);
    ir.close();
    dir.close();
  }

  /**
   * Tests sorting on type legacy long, specifying the missing value should be treated as
   * Long.MAX_VALUE
   */
  public void testLegacyLongMissingLast() throws IOException {
    Directory dir = newDirectory();
    RandomIndexWriter writer = new RandomIndexWriter(random(), dir);
    Document doc = new Document();
    writer.addDocument(doc);
    doc = new Document();
    doc.add(new LegacyLongField("value", -1, Field.Store.YES));
    writer.addDocument(doc);
    doc = new Document();
    doc.add(new LegacyLongField("value", 4, Field.Store.YES));
    writer.addDocument(doc);
    IndexReader ir =
        UninvertingReader.wrap(
            writer.getReader(), Collections.singletonMap("value", Type.LEGACY_LONG));
    writer.close();

    IndexSearcher searcher = newSearcher(ir);
    SortField sortField = new SortField("value", SortField.Type.LONG);
    sortField.setMissingValue(Long.MAX_VALUE);
    Sort sort = new Sort(sortField);

    TopDocs td = searcher.search(new MatchAllDocsQuery(), 10, sort);
    assertEquals(3, td.totalHits.value);
    // null is treated as Long.MAX_VALUE
    assertEquals("-1", searcher.storedFields().document(td.scoreDocs[0].doc).get("value"));
    assertEquals("4", searcher.storedFields().document(td.scoreDocs[1].doc).get("value"));
    assertNull(searcher.storedFields().document(td.scoreDocs[2].doc).get("value"));
    TestUtil.checkReader(ir);
    ir.close();
    dir.close();
  }

  /** Tests sorting on type legacy long in reverse */
  public void testLegacyLongReverse() throws IOException {
    Directory dir = newDirectory();
    RandomIndexWriter writer = new RandomIndexWriter(random(), dir);
    Document doc = new Document();
    doc.add(new LegacyLongField("value", 3000000000L, Field.Store.YES));
    writer.addDocument(doc);
    doc = new Document();
    doc.add(new LegacyLongField("value", -1, Field.Store.YES));
    writer.addDocument(doc);
    doc = new Document();
    doc.add(new LegacyLongField("value", 4, Field.Store.YES));
    writer.addDocument(doc);
    IndexReader ir =
        UninvertingReader.wrap(
            writer.getReader(), Collections.singletonMap("value", Type.LEGACY_LONG));
    writer.close();

    IndexSearcher searcher = newSearcher(ir);
    Sort sort = new Sort(new SortField("value", SortField.Type.LONG, true));

    TopDocs td = searcher.search(new MatchAllDocsQuery(), 10, sort);
    assertEquals(3, td.totalHits.value);
    // reverse numeric order
    assertEquals("3000000000", searcher.storedFields().document(td.scoreDocs[0].doc).get("value"));
    assertEquals("4", searcher.storedFields().document(td.scoreDocs[1].doc).get("value"));
    assertEquals("-1", searcher.storedFields().document(td.scoreDocs[2].doc).get("value"));
    TestUtil.checkReader(ir);
    ir.close();
    dir.close();
  }

  /** Tests sorting on type float */
  public void testFloat() throws IOException {
    Directory dir = newDirectory();
    RandomIndexWriter writer = new RandomIndexWriter(random(), dir);
    Document doc = new Document();
    doc.add(new FloatPoint("value", 30.1f));
    doc.add(new StoredField("value", 30.1f));
    writer.addDocument(doc);
    doc = new Document();
    doc.add(new FloatPoint("value", -1.3f));
    doc.add(new StoredField("value", -1.3f));
    writer.addDocument(doc);
    doc = new Document();
    doc.add(new FloatPoint("value", 4.2f));
    doc.add(new StoredField("value", 4.2f));
    writer.addDocument(doc);
    IndexReader ir =
        UninvertingReader.wrap(
            writer.getReader(), Collections.singletonMap("value", Type.FLOAT_POINT));
    writer.close();

    IndexSearcher searcher = newSearcher(ir, false);
    Sort sort = new Sort(new SortField("value", SortField.Type.FLOAT));

    TopDocs td = searcher.search(new MatchAllDocsQuery(), 10, sort);
    assertEquals(3, td.totalHits.value);
    // numeric order
    assertEquals("-1.3", searcher.storedFields().document(td.scoreDocs[0].doc).get("value"));
    assertEquals("4.2", searcher.storedFields().document(td.scoreDocs[1].doc).get("value"));
    assertEquals("30.1", searcher.storedFields().document(td.scoreDocs[2].doc).get("value"));
    TestUtil.checkReader(ir);
    ir.close();
    dir.close();
  }

  /** Tests sorting on type float with a missing value */
  public void testFloatMissing() throws IOException {
    Directory dir = newDirectory();
    RandomIndexWriter writer = new RandomIndexWriter(random(), dir);
    Document doc = new Document();
    writer.addDocument(doc);
    doc = new Document();
    doc.add(new FloatPoint("value", -1.3f));
    doc.add(new StoredField("value", -1.3f));
    writer.addDocument(doc);
    doc = new Document();
    doc.add(new FloatPoint("value", 4.2f));
    doc.add(new StoredField("value", 4.2f));
    writer.addDocument(doc);
    IndexReader ir =
        UninvertingReader.wrap(
            writer.getReader(), Collections.singletonMap("value", Type.FLOAT_POINT));
    writer.close();

    IndexSearcher searcher = newSearcher(ir, false);
    Sort sort = new Sort(new SortField("value", SortField.Type.FLOAT));

    TopDocs td = searcher.search(new MatchAllDocsQuery(), 10, sort);
    assertEquals(3, td.totalHits.value);
    // null is treated as 0
    assertEquals("-1.3", searcher.storedFields().document(td.scoreDocs[0].doc).get("value"));
    assertNull(searcher.storedFields().document(td.scoreDocs[1].doc).get("value"));
    assertEquals("4.2", searcher.storedFields().document(td.scoreDocs[2].doc).get("value"));
    TestUtil.checkReader(ir);
    ir.close();
    dir.close();
  }

  /**
   * Tests sorting on type float, specifying the missing value should be treated as Float.MAX_VALUE
   */
  public void testFloatMissingLast() throws IOException {
    Directory dir = newDirectory();
    RandomIndexWriter writer = new RandomIndexWriter(random(), dir);
    Document doc = new Document();
    writer.addDocument(doc);
    doc = new Document();
    doc.add(new FloatPoint("value", -1.3f));
    doc.add(new StoredField("value", -1.3f));
    writer.addDocument(doc);
    doc = new Document();
    doc.add(new FloatPoint("value", 4.2f));
    doc.add(new StoredField("value", 4.2f));
    writer.addDocument(doc);
    IndexReader ir =
        UninvertingReader.wrap(
            writer.getReader(), Collections.singletonMap("value", Type.FLOAT_POINT));
    writer.close();

    IndexSearcher searcher = newSearcher(ir, false);
    SortField sortField = new SortField("value", SortField.Type.FLOAT);
    sortField.setMissingValue(Float.MAX_VALUE);
    Sort sort = new Sort(sortField);

    TopDocs td = searcher.search(new MatchAllDocsQuery(), 10, sort);
    assertEquals(3, td.totalHits.value);
    // null is treated as Float.MAX_VALUE
    assertEquals("-1.3", searcher.storedFields().document(td.scoreDocs[0].doc).get("value"));
    assertEquals("4.2", searcher.storedFields().document(td.scoreDocs[1].doc).get("value"));
    assertNull(searcher.storedFields().document(td.scoreDocs[2].doc).get("value"));
    TestUtil.checkReader(ir);
    ir.close();
    dir.close();
  }

  /** Tests sorting on type float in reverse */
  public void testFloatReverse() throws IOException {
    Directory dir = newDirectory();
    RandomIndexWriter writer = new RandomIndexWriter(random(), dir);
    Document doc = new Document();
    doc.add(new FloatPoint("value", 30.1f));
    doc.add(new StoredField("value", 30.1f));
    writer.addDocument(doc);
    doc = new Document();
    doc.add(new FloatPoint("value", -1.3f));
    doc.add(new StoredField("value", -1.3f));
    writer.addDocument(doc);
    doc = new Document();
    doc.add(new FloatPoint("value", 4.2f));
    doc.add(new StoredField("value", 4.2f));
    writer.addDocument(doc);
    IndexReader ir =
        UninvertingReader.wrap(
            writer.getReader(), Collections.singletonMap("value", Type.FLOAT_POINT));
    writer.close();

    IndexSearcher searcher = newSearcher(ir, false);
    Sort sort = new Sort(new SortField("value", SortField.Type.FLOAT, true));

    TopDocs td = searcher.search(new MatchAllDocsQuery(), 10, sort);
    assertEquals(3, td.totalHits.value);
    // reverse numeric order
    assertEquals("30.1", searcher.storedFields().document(td.scoreDocs[0].doc).get("value"));
    assertEquals("4.2", searcher.storedFields().document(td.scoreDocs[1].doc).get("value"));
    assertEquals("-1.3", searcher.storedFields().document(td.scoreDocs[2].doc).get("value"));
    TestUtil.checkReader(ir);
    ir.close();
    dir.close();
  }

  /** Tests sorting on type legacy float */
  public void testLegacyFloat() throws IOException {
    Directory dir = newDirectory();
    RandomIndexWriter writer = new RandomIndexWriter(random(), dir);
    Document doc = new Document();
    doc.add(new LegacyFloatField("value", 30.1f, Field.Store.YES));
    writer.addDocument(doc);
    doc = new Document();
    doc.add(new LegacyFloatField("value", -1.3f, Field.Store.YES));
    writer.addDocument(doc);
    doc = new Document();
    doc.add(new LegacyFloatField("value", 4.2f, Field.Store.YES));
    writer.addDocument(doc);
    IndexReader ir =
        UninvertingReader.wrap(
            writer.getReader(), Collections.singletonMap("value", Type.LEGACY_FLOAT));
    writer.close();

    IndexSearcher searcher = newSearcher(ir);
    Sort sort = new Sort(new SortField("value", SortField.Type.FLOAT));

    TopDocs td = searcher.search(new MatchAllDocsQuery(), 10, sort);
    assertEquals(3, td.totalHits.value);
    // numeric order
    assertEquals("-1.3", searcher.storedFields().document(td.scoreDocs[0].doc).get("value"));
    assertEquals("4.2", searcher.storedFields().document(td.scoreDocs[1].doc).get("value"));
    assertEquals("30.1", searcher.storedFields().document(td.scoreDocs[2].doc).get("value"));
    TestUtil.checkReader(ir);
    ir.close();
    dir.close();
  }

  /** Tests sorting on type legacy float with a missing value */
  public void testLegacyFloatMissing() throws IOException {
    Directory dir = newDirectory();
    RandomIndexWriter writer = new RandomIndexWriter(random(), dir);
    Document doc = new Document();
    writer.addDocument(doc);
    doc = new Document();
    doc.add(new LegacyFloatField("value", -1.3f, Field.Store.YES));
    writer.addDocument(doc);
    doc = new Document();
    doc.add(new LegacyFloatField("value", 4.2f, Field.Store.YES));
    writer.addDocument(doc);
    IndexReader ir =
        UninvertingReader.wrap(
            writer.getReader(), Collections.singletonMap("value", Type.LEGACY_FLOAT));
    writer.close();

    IndexSearcher searcher = newSearcher(ir);
    Sort sort = new Sort(new SortField("value", SortField.Type.FLOAT));

    TopDocs td = searcher.search(new MatchAllDocsQuery(), 10, sort);
    assertEquals(3, td.totalHits.value);
    // null is treated as 0
    assertEquals("-1.3", searcher.storedFields().document(td.scoreDocs[0].doc).get("value"));
    assertNull(searcher.storedFields().document(td.scoreDocs[1].doc).get("value"));
    assertEquals("4.2", searcher.storedFields().document(td.scoreDocs[2].doc).get("value"));
    TestUtil.checkReader(ir);
    ir.close();
    dir.close();
  }

  /**
   * Tests sorting on type legacy float, specifying the missing value should be treated as
   * Float.MAX_VALUE
   */
  public void testLegacyFloatMissingLast() throws IOException {
    Directory dir = newDirectory();
    RandomIndexWriter writer = new RandomIndexWriter(random(), dir);
    Document doc = new Document();
    writer.addDocument(doc);
    doc = new Document();
    doc.add(new LegacyFloatField("value", -1.3f, Field.Store.YES));
    writer.addDocument(doc);
    doc = new Document();
    doc.add(new LegacyFloatField("value", 4.2f, Field.Store.YES));
    writer.addDocument(doc);
    IndexReader ir =
        UninvertingReader.wrap(
            writer.getReader(), Collections.singletonMap("value", Type.LEGACY_FLOAT));
    writer.close();

    IndexSearcher searcher = newSearcher(ir);
    SortField sortField = new SortField("value", SortField.Type.FLOAT);
    sortField.setMissingValue(Float.MAX_VALUE);
    Sort sort = new Sort(sortField);

    TopDocs td = searcher.search(new MatchAllDocsQuery(), 10, sort);
    assertEquals(3, td.totalHits.value);
    // null is treated as Float.MAX_VALUE
    assertEquals("-1.3", searcher.storedFields().document(td.scoreDocs[0].doc).get("value"));
    assertEquals("4.2", searcher.storedFields().document(td.scoreDocs[1].doc).get("value"));
    assertNull(searcher.storedFields().document(td.scoreDocs[2].doc).get("value"));
    TestUtil.checkReader(ir);
    ir.close();
    dir.close();
  }

  /** Tests sorting on type legacy float in reverse */
  public void testLegacyFloatReverse() throws IOException {
    Directory dir = newDirectory();
    RandomIndexWriter writer = new RandomIndexWriter(random(), dir);
    Document doc = new Document();
    doc.add(new LegacyFloatField("value", 30.1f, Field.Store.YES));
    writer.addDocument(doc);
    doc = new Document();
    doc.add(new LegacyFloatField("value", -1.3f, Field.Store.YES));
    writer.addDocument(doc);
    doc = new Document();
    doc.add(new LegacyFloatField("value", 4.2f, Field.Store.YES));
    writer.addDocument(doc);
    IndexReader ir =
        UninvertingReader.wrap(
            writer.getReader(), Collections.singletonMap("value", Type.LEGACY_FLOAT));
    writer.close();

    IndexSearcher searcher = newSearcher(ir);
    Sort sort = new Sort(new SortField("value", SortField.Type.FLOAT, true));

    TopDocs td = searcher.search(new MatchAllDocsQuery(), 10, sort);
    assertEquals(3, td.totalHits.value);
    // reverse numeric order
    assertEquals("30.1", searcher.storedFields().document(td.scoreDocs[0].doc).get("value"));
    assertEquals("4.2", searcher.storedFields().document(td.scoreDocs[1].doc).get("value"));
    assertEquals("-1.3", searcher.storedFields().document(td.scoreDocs[2].doc).get("value"));
    TestUtil.checkReader(ir);
    ir.close();
    dir.close();
  }

  /** Tests sorting on type double */
  public void testDouble() throws IOException {
    Directory dir = newDirectory();
    RandomIndexWriter writer = new RandomIndexWriter(random(), dir);
    Document doc = new Document();
    doc.add(new DoublePoint("value", 30.1));
    doc.add(new StoredField("value", 30.1));
    writer.addDocument(doc);
    doc = new Document();
    doc.add(new DoublePoint("value", -1.3));
    doc.add(new StoredField("value", -1.3));
    writer.addDocument(doc);
    doc = new Document();
    doc.add(new DoublePoint("value", 4.2333333333333));
    doc.add(new StoredField("value", 4.2333333333333));
    writer.addDocument(doc);
    doc = new Document();
    doc.add(new DoublePoint("value", 4.2333333333332));
    doc.add(new StoredField("value", 4.2333333333332));
    writer.addDocument(doc);
    IndexReader ir =
        UninvertingReader.wrap(
            writer.getReader(), Collections.singletonMap("value", Type.DOUBLE_POINT));
    writer.close();

    IndexSearcher searcher = newSearcher(ir, false);
    Sort sort = new Sort(new SortField("value", SortField.Type.DOUBLE));

    TopDocs td = searcher.search(new MatchAllDocsQuery(), 10, sort);
    assertEquals(4, td.totalHits.value);
    // numeric order
    assertEquals("-1.3", searcher.storedFields().document(td.scoreDocs[0].doc).get("value"));
    assertEquals(
        "4.2333333333332", searcher.storedFields().document(td.scoreDocs[1].doc).get("value"));
    assertEquals(
        "4.2333333333333", searcher.storedFields().document(td.scoreDocs[2].doc).get("value"));
    assertEquals("30.1", searcher.storedFields().document(td.scoreDocs[3].doc).get("value"));
    TestUtil.checkReader(ir);
    ir.close();
    dir.close();
  }

  /** Tests sorting on type double with +/- zero */
  public void testDoubleSignedZero() throws IOException {
    Directory dir = newDirectory();
    RandomIndexWriter writer = new RandomIndexWriter(random(), dir);
    Document doc = new Document();
    doc.add(new DoublePoint("value", +0d));
    doc.add(new StoredField("value", +0d));
    writer.addDocument(doc);
    doc = new Document();
    doc.add(new DoublePoint("value", -0d));
    doc.add(new StoredField("value", -0d));
    writer.addDocument(doc);
    doc = new Document();
    IndexReader ir =
        UninvertingReader.wrap(
            writer.getReader(), Collections.singletonMap("value", Type.DOUBLE_POINT));
    writer.close();

    IndexSearcher searcher = newSearcher(ir, false);
    Sort sort = new Sort(new SortField("value", SortField.Type.DOUBLE));

    TopDocs td = searcher.search(new MatchAllDocsQuery(), 10, sort);
    assertEquals(2, td.totalHits.value);
    // numeric order
    double v0 =
        searcher
            .storedFields()
            .document(td.scoreDocs[0].doc)
            .getField("value")
            .numericValue()
            .doubleValue();
    double v1 =
        searcher
            .storedFields()
            .document(td.scoreDocs[1].doc)
            .getField("value")
            .numericValue()
            .doubleValue();
    assertEquals(0, v0, 0d);
    assertEquals(0, v1, 0d);
    // check sign bits
    assertEquals(1, Double.doubleToLongBits(v0) >>> 63);
    assertEquals(0, Double.doubleToLongBits(v1) >>> 63);
    TestUtil.checkReader(ir);
    ir.close();
    dir.close();
  }

  /** Tests sorting on type double with a missing value */
  public void testDoubleMissing() throws IOException {
    Directory dir = newDirectory();
    RandomIndexWriter writer = new RandomIndexWriter(random(), dir);
    Document doc = new Document();
    writer.addDocument(doc);
    doc = new Document();
    doc.add(new DoublePoint("value", -1.3));
    doc.add(new StoredField("value", -1.3));
    writer.addDocument(doc);
    doc = new Document();
    doc.add(new DoublePoint("value", 4.2333333333333));
    doc.add(new StoredField("value", 4.2333333333333));
    writer.addDocument(doc);
    doc = new Document();
    doc.add(new DoublePoint("value", 4.2333333333332));
    doc.add(new StoredField("value", 4.2333333333332));
    writer.addDocument(doc);
    IndexReader ir =
        UninvertingReader.wrap(
            writer.getReader(), Collections.singletonMap("value", Type.DOUBLE_POINT));
    writer.close();

    IndexSearcher searcher = newSearcher(ir, false);
    Sort sort = new Sort(new SortField("value", SortField.Type.DOUBLE));

    TopDocs td = searcher.search(new MatchAllDocsQuery(), 10, sort);
    assertEquals(4, td.totalHits.value);
    // null treated as a 0
    assertEquals("-1.3", searcher.storedFields().document(td.scoreDocs[0].doc).get("value"));
    assertNull(searcher.storedFields().document(td.scoreDocs[1].doc).get("value"));
    assertEquals(
        "4.2333333333332", searcher.storedFields().document(td.scoreDocs[2].doc).get("value"));
    assertEquals(
        "4.2333333333333", searcher.storedFields().document(td.scoreDocs[3].doc).get("value"));
    TestUtil.checkReader(ir);
    ir.close();
    dir.close();
  }

  /**
   * Tests sorting on type double, specifying the missing value should be treated as
   * Double.MAX_VALUE
   */
  public void testDoubleMissingLast() throws IOException {
    Directory dir = newDirectory();
    RandomIndexWriter writer = new RandomIndexWriter(random(), dir);
    Document doc = new Document();
    writer.addDocument(doc);
    doc = new Document();
    doc.add(new DoublePoint("value", -1.3));
    doc.add(new StoredField("value", -1.3));
    writer.addDocument(doc);
    doc = new Document();
    doc.add(new DoublePoint("value", 4.2333333333333));
    doc.add(new StoredField("value", 4.2333333333333));
    writer.addDocument(doc);
    doc = new Document();
    doc.add(new DoublePoint("value", 4.2333333333332));
    doc.add(new StoredField("value", 4.2333333333332));
    writer.addDocument(doc);
    IndexReader ir =
        UninvertingReader.wrap(
            writer.getReader(), Collections.singletonMap("value", Type.DOUBLE_POINT));
    writer.close();

    IndexSearcher searcher = newSearcher(ir, false);
    SortField sortField = new SortField("value", SortField.Type.DOUBLE);
    sortField.setMissingValue(Double.MAX_VALUE);
    Sort sort = new Sort(sortField);

    TopDocs td = searcher.search(new MatchAllDocsQuery(), 10, sort);
    assertEquals(4, td.totalHits.value);
    // null treated as Double.MAX_VALUE
    assertEquals("-1.3", searcher.storedFields().document(td.scoreDocs[0].doc).get("value"));
    assertEquals(
        "4.2333333333332", searcher.storedFields().document(td.scoreDocs[1].doc).get("value"));
    assertEquals(
        "4.2333333333333", searcher.storedFields().document(td.scoreDocs[2].doc).get("value"));
    assertNull(searcher.storedFields().document(td.scoreDocs[3].doc).get("value"));
    TestUtil.checkReader(ir);
    ir.close();
    dir.close();
  }

  /** Tests sorting on type double in reverse */
  public void testDoubleReverse() throws IOException {
    Directory dir = newDirectory();
    RandomIndexWriter writer = new RandomIndexWriter(random(), dir);
    Document doc = new Document();
    doc.add(new DoublePoint("value", 30.1));
    doc.add(new StoredField("value", 30.1));
    writer.addDocument(doc);
    doc = new Document();
    doc.add(new DoublePoint("value", -1.3));
    doc.add(new StoredField("value", -1.3));
    writer.addDocument(doc);
    doc = new Document();
    doc.add(new DoublePoint("value", 4.2333333333333));
    doc.add(new StoredField("value", 4.2333333333333));
    writer.addDocument(doc);
    doc = new Document();
    doc.add(new DoublePoint("value", 4.2333333333332));
    doc.add(new StoredField("value", 4.2333333333332));
    writer.addDocument(doc);
    IndexReader ir =
        UninvertingReader.wrap(
            writer.getReader(), Collections.singletonMap("value", Type.DOUBLE_POINT));
    writer.close();

    IndexSearcher searcher = newSearcher(ir, false);
    Sort sort = new Sort(new SortField("value", SortField.Type.DOUBLE, true));

    TopDocs td = searcher.search(new MatchAllDocsQuery(), 10, sort);
    assertEquals(4, td.totalHits.value);
    // numeric order
    assertEquals("30.1", searcher.storedFields().document(td.scoreDocs[0].doc).get("value"));
    assertEquals(
        "4.2333333333333", searcher.storedFields().document(td.scoreDocs[1].doc).get("value"));
    assertEquals(
        "4.2333333333332", searcher.storedFields().document(td.scoreDocs[2].doc).get("value"));
    assertEquals("-1.3", searcher.storedFields().document(td.scoreDocs[3].doc).get("value"));
    TestUtil.checkReader(ir);
    ir.close();
    dir.close();
  }

  /** Tests sorting on type legacy double */
  public void testLegacyDouble() throws IOException {
    Directory dir = newDirectory();
    RandomIndexWriter writer = new RandomIndexWriter(random(), dir);
    Document doc = new Document();
    doc.add(new LegacyDoubleField("value", 30.1, Field.Store.YES));
    writer.addDocument(doc);
    doc = new Document();
    doc.add(new LegacyDoubleField("value", -1.3, Field.Store.YES));
    writer.addDocument(doc);
    doc = new Document();
    doc.add(new LegacyDoubleField("value", 4.2333333333333, Field.Store.YES));
    writer.addDocument(doc);
    doc = new Document();
    doc.add(new LegacyDoubleField("value", 4.2333333333332, Field.Store.YES));
    writer.addDocument(doc);
    IndexReader ir =
        UninvertingReader.wrap(
            writer.getReader(), Collections.singletonMap("value", Type.LEGACY_DOUBLE));
    writer.close();

    IndexSearcher searcher = newSearcher(ir);
    Sort sort = new Sort(new SortField("value", SortField.Type.DOUBLE));

    TopDocs td = searcher.search(new MatchAllDocsQuery(), 10, sort);
    assertEquals(4, td.totalHits.value);
    // numeric order
    assertEquals("-1.3", searcher.storedFields().document(td.scoreDocs[0].doc).get("value"));
    assertEquals(
        "4.2333333333332", searcher.storedFields().document(td.scoreDocs[1].doc).get("value"));
    assertEquals(
        "4.2333333333333", searcher.storedFields().document(td.scoreDocs[2].doc).get("value"));
    assertEquals("30.1", searcher.storedFields().document(td.scoreDocs[3].doc).get("value"));
    TestUtil.checkReader(ir);
    ir.close();
    dir.close();
  }

  /** Tests sorting on type legacy double with +/- zero */
  public void testLegacyDoubleSignedZero() throws IOException {
    Directory dir = newDirectory();
    RandomIndexWriter writer = new RandomIndexWriter(random(), dir);
    Document doc = new Document();
    doc.add(new LegacyDoubleField("value", +0d, Field.Store.YES));
    writer.addDocument(doc);
    doc = new Document();
    doc.add(new LegacyDoubleField("value", -0d, Field.Store.YES));
    writer.addDocument(doc);
    doc = new Document();
    IndexReader ir =
        UninvertingReader.wrap(
            writer.getReader(), Collections.singletonMap("value", Type.LEGACY_DOUBLE));
    writer.close();

    IndexSearcher searcher = newSearcher(ir);
    Sort sort = new Sort(new SortField("value", SortField.Type.DOUBLE));

    TopDocs td = searcher.search(new MatchAllDocsQuery(), 10, sort);
    assertEquals(2, td.totalHits.value);
    // numeric order
    double v0 =
        searcher
            .storedFields()
            .document(td.scoreDocs[0].doc)
            .getField("value")
            .numericValue()
            .doubleValue();
    double v1 =
        searcher
            .storedFields()
            .document(td.scoreDocs[1].doc)
            .getField("value")
            .numericValue()
            .doubleValue();
    assertEquals(0, v0, 0d);
    assertEquals(0, v1, 0d);
    // check sign bits
    assertEquals(1, Double.doubleToLongBits(v0) >>> 63);
    assertEquals(0, Double.doubleToLongBits(v1) >>> 63);
    TestUtil.checkReader(ir);
    ir.close();
    dir.close();
  }

  /** Tests sorting on type legacy double with a missing value */
  public void testLegacyDoubleMissing() throws IOException {
    Directory dir = newDirectory();
    RandomIndexWriter writer = new RandomIndexWriter(random(), dir);
    Document doc = new Document();
    writer.addDocument(doc);
    doc = new Document();
    doc.add(new LegacyDoubleField("value", -1.3, Field.Store.YES));
    writer.addDocument(doc);
    doc = new Document();
    doc.add(new LegacyDoubleField("value", 4.2333333333333, Field.Store.YES));
    writer.addDocument(doc);
    doc = new Document();
    doc.add(new LegacyDoubleField("value", 4.2333333333332, Field.Store.YES));
    writer.addDocument(doc);
    IndexReader ir =
        UninvertingReader.wrap(
            writer.getReader(), Collections.singletonMap("value", Type.LEGACY_DOUBLE));
    writer.close();

    IndexSearcher searcher = newSearcher(ir);
    Sort sort = new Sort(new SortField("value", SortField.Type.DOUBLE));

    TopDocs td = searcher.search(new MatchAllDocsQuery(), 10, sort);
    assertEquals(4, td.totalHits.value);
    // null treated as a 0
    assertEquals("-1.3", searcher.storedFields().document(td.scoreDocs[0].doc).get("value"));
    assertNull(searcher.storedFields().document(td.scoreDocs[1].doc).get("value"));
    assertEquals(
        "4.2333333333332", searcher.storedFields().document(td.scoreDocs[2].doc).get("value"));
    assertEquals(
        "4.2333333333333", searcher.storedFields().document(td.scoreDocs[3].doc).get("value"));
    TestUtil.checkReader(ir);
    ir.close();
    dir.close();
  }

  /**
   * Tests sorting on type legacy double, specifying the missing value should be treated as
   * Double.MAX_VALUE
   */
  public void testLegacyDoubleMissingLast() throws IOException {
    Directory dir = newDirectory();
    RandomIndexWriter writer = new RandomIndexWriter(random(), dir);
    Document doc = new Document();
    writer.addDocument(doc);
    doc = new Document();
    doc.add(new LegacyDoubleField("value", -1.3, Field.Store.YES));
    writer.addDocument(doc);
    doc = new Document();
    doc.add(new LegacyDoubleField("value", 4.2333333333333, Field.Store.YES));
    writer.addDocument(doc);
    doc = new Document();
    doc.add(new LegacyDoubleField("value", 4.2333333333332, Field.Store.YES));
    writer.addDocument(doc);
    IndexReader ir =
        UninvertingReader.wrap(
            writer.getReader(), Collections.singletonMap("value", Type.LEGACY_DOUBLE));
    writer.close();

    IndexSearcher searcher = newSearcher(ir);
    SortField sortField = new SortField("value", SortField.Type.DOUBLE);
    sortField.setMissingValue(Double.MAX_VALUE);
    Sort sort = new Sort(sortField);

    TopDocs td = searcher.search(new MatchAllDocsQuery(), 10, sort);
    assertEquals(4, td.totalHits.value);
    // null treated as Double.MAX_VALUE
    assertEquals("-1.3", searcher.storedFields().document(td.scoreDocs[0].doc).get("value"));
    assertEquals(
        "4.2333333333332", searcher.storedFields().document(td.scoreDocs[1].doc).get("value"));
    assertEquals(
        "4.2333333333333", searcher.storedFields().document(td.scoreDocs[2].doc).get("value"));
    assertNull(searcher.storedFields().document(td.scoreDocs[3].doc).get("value"));
    TestUtil.checkReader(ir);
    ir.close();
    dir.close();
  }

  /** Tests sorting on type legacy double in reverse */
  public void testLegacyDoubleReverse() throws IOException {
    Directory dir = newDirectory();
    RandomIndexWriter writer = new RandomIndexWriter(random(), dir);
    Document doc = new Document();
    doc.add(new LegacyDoubleField("value", 30.1, Field.Store.YES));
    writer.addDocument(doc);
    doc = new Document();
    doc.add(new LegacyDoubleField("value", -1.3, Field.Store.YES));
    writer.addDocument(doc);
    doc = new Document();
    doc.add(new LegacyDoubleField("value", 4.2333333333333, Field.Store.YES));
    writer.addDocument(doc);
    doc = new Document();
    doc.add(new LegacyDoubleField("value", 4.2333333333332, Field.Store.YES));
    writer.addDocument(doc);
    IndexReader ir =
        UninvertingReader.wrap(
            writer.getReader(), Collections.singletonMap("value", Type.LEGACY_DOUBLE));
    writer.close();

    IndexSearcher searcher = newSearcher(ir);
    Sort sort = new Sort(new SortField("value", SortField.Type.DOUBLE, true));

    TopDocs td = searcher.search(new MatchAllDocsQuery(), 10, sort);
    assertEquals(4, td.totalHits.value);
    // numeric order
    assertEquals("30.1", searcher.storedFields().document(td.scoreDocs[0].doc).get("value"));
    assertEquals(
        "4.2333333333333", searcher.storedFields().document(td.scoreDocs[1].doc).get("value"));
    assertEquals(
        "4.2333333333332", searcher.storedFields().document(td.scoreDocs[2].doc).get("value"));
    assertEquals("-1.3", searcher.storedFields().document(td.scoreDocs[3].doc).get("value"));
    TestUtil.checkReader(ir);
    ir.close();
    dir.close();
  }

  public void testEmptyStringVsNullStringSort() throws Exception {
    Directory dir = newDirectory();
    IndexWriter w =
        new IndexWriter(
            dir,
            newIndexWriterConfig(new MockAnalyzer(random()))
                .setMergePolicy(NoMergePolicy.INSTANCE));
    Document doc = new Document();
    doc.add(newStringField("f", "", Field.Store.NO));
    doc.add(newStringField("t", "1", Field.Store.NO));
    w.addDocument(doc);
    w.commit();
    doc = new Document();
    doc.add(newStringField("t", "1", Field.Store.NO));
    w.addDocument(doc);

    IndexReader r =
        UninvertingReader.wrap(DirectoryReader.open(w), Collections.singletonMap("f", Type.SORTED));
    w.close();
    IndexSearcher s = newSearcher(r);
    TopDocs hits =
        s.search(
            new TermQuery(new Term("t", "1")),
            10,
            new Sort(new SortField("f", SortField.Type.STRING)));
    assertEquals(2, hits.totalHits.value);
    // null sorts first
    assertEquals(1, hits.scoreDocs[0].doc);
    assertEquals(0, hits.scoreDocs[1].doc);
    TestUtil.checkReader(r);
    r.close();
    dir.close();
  }

  /**
   * test that we throw exception on multi-valued field, creates corrupt reader, use SORTED_SET
   * instead
   */
  public void testMultiValuedField() throws IOException {
    Directory indexStore = newDirectory();
    IndexWriter writer =
        new IndexWriter(indexStore, newIndexWriterConfig(new MockAnalyzer(random())));
    for (int i = 0; i < 5; i++) {
      Document doc = new Document();
      doc.add(new StringField("string", "a" + i, Field.Store.NO));
      doc.add(new StringField("string", "b" + i, Field.Store.NO));
      writer.addDocument(doc);
    }
    writer.forceMerge(1); // enforce one segment to have a higher unique term count in all cases
    writer.close();
    Sort sort = new Sort(new SortField("string", SortField.Type.STRING), SortField.FIELD_DOC);
    IndexReader reader =
        UninvertingReader.wrap(
            DirectoryReader.open(indexStore), Collections.singletonMap("string", Type.SORTED));
    IndexSearcher searcher = new IndexSearcher(reader);
    expectThrows(
        IllegalStateException.class,
        () -> {
          searcher.search(new MatchAllDocsQuery(), 500, sort);
        });
    reader.close();
    indexStore.close();
  }

  public void testMaxScore() throws Exception {
    Directory d = newDirectory();
    // Not RIW because we need exactly 2 segs:
    IndexWriter w = new IndexWriter(d, new IndexWriterConfig(new MockAnalyzer(random())));
    int id = 0;
    for (int seg = 0; seg < 2; seg++) {
      for (int docIDX = 0; docIDX < 10; docIDX++) {
        Document doc = new Document();
        doc.add(new LegacyIntField("id", docIDX, Field.Store.YES));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < id; i++) {
          sb.append(' ');
          sb.append("text");
        }
        doc.add(newTextField("body", sb.toString(), Field.Store.NO));
        w.addDocument(doc);
        id++;
      }
      w.commit();
    }

    IndexReader r =
        UninvertingReader.wrap(
            DirectoryReader.open(w), Collections.singletonMap("id", Type.LEGACY_INTEGER));
    w.close();
    Query q = new TermQuery(new Term("body", "text"));
    TestUtil.checkReader(r);
    r.close();
    d.close();
  }

  /** test sorts when there's nothing in the index */
  public void testEmptyIndex() throws Exception {
    IndexSearcher empty = newSearcher(new MultiReader());
    Query query = new TermQuery(new Term("contents", "foo"));

    Sort sort = new Sort();
    TopDocs td = empty.search(query, 10, sort, true);
    assertEquals(0, td.totalHits.value);

    sort = new Sort(SortField.FIELD_DOC);
    td = empty.search(query, 10, sort, true);
    assertEquals(0, td.totalHits.value);

    sort = new Sort(new SortField("int", SortField.Type.INT), SortField.FIELD_DOC);
    td = empty.search(query, 10, sort, true);
    assertEquals(0, td.totalHits.value);

    sort = new Sort(new SortField("string", SortField.Type.STRING, true), SortField.FIELD_DOC);
    td = empty.search(query, 10, sort, true);
    assertEquals(0, td.totalHits.value);

    sort =
        new Sort(new SortField("string_val", SortField.Type.STRING_VAL, true), SortField.FIELD_DOC);
    td = empty.search(query, 10, sort, true);
    assertEquals(0, td.totalHits.value);

    sort =
        new Sort(
            new SortField("float", SortField.Type.FLOAT),
            new SortField("string", SortField.Type.STRING));
    td = empty.search(query, 10, sort, true);
    assertEquals(0, td.totalHits.value);
  }

  /** Tests sorting a single document */
  public void testSortOneDocument() throws Exception {
    Directory dir = newDirectory();
    RandomIndexWriter writer = new RandomIndexWriter(random(), dir);
    Document doc = new Document();
    doc.add(newStringField("value", "foo", Field.Store.YES));
    writer.addDocument(doc);
    IndexReader ir =
        UninvertingReader.wrap(writer.getReader(), Collections.singletonMap("value", Type.SORTED));
    writer.close();

    IndexSearcher searcher = newSearcher(ir);
    Sort sort = new Sort(new SortField("value", SortField.Type.STRING));

    TopDocs td = searcher.search(new MatchAllDocsQuery(), 10, sort);
    assertEquals(1, td.totalHits.value);
    assertEquals("foo", searcher.storedFields().document(td.scoreDocs[0].doc).get("value"));
    TestUtil.checkReader(ir);
    ir.close();
    dir.close();
  }

  /** Tests sorting a single document with scores */
  public void testSortOneDocumentWithScores() throws Exception {
    Directory dir = newDirectory();
    RandomIndexWriter writer = new RandomIndexWriter(random(), dir);
    Document doc = new Document();
    doc.add(newStringField("value", "foo", Field.Store.YES));
    writer.addDocument(doc);
    IndexReader ir =
        UninvertingReader.wrap(writer.getReader(), Collections.singletonMap("value", Type.SORTED));
    writer.close();

    IndexSearcher searcher = newSearcher(ir);
    Sort sort = new Sort(new SortField("value", SortField.Type.STRING));

    TopDocs expected = searcher.search(new TermQuery(new Term("value", "foo")), 10);
    assertEquals(1, expected.totalHits.value);
    TopDocs actual = searcher.search(new TermQuery(new Term("value", "foo")), 10, sort, true);

    assertEquals(expected.totalHits.value, actual.totalHits.value);
    assertEquals(expected.scoreDocs[0].score, actual.scoreDocs[0].score, 0F);
    TestUtil.checkReader(ir);
    ir.close();
    dir.close();
  }

  /** Tests sorting with two fields */
  public void testSortTwoFields() throws Exception {
    Directory dir = newDirectory();
    RandomIndexWriter writer = new RandomIndexWriter(random(), dir);
    Document doc = new Document();
    doc.add(newStringField("tievalue", "tied", Field.Store.NO));
    doc.add(newStringField("value", "foo", Field.Store.YES));
    writer.addDocument(doc);
    doc = new Document();
    doc.add(newStringField("tievalue", "tied", Field.Store.NO));
    doc.add(newStringField("value", "bar", Field.Store.YES));
    writer.addDocument(doc);
    Map<String, Type> mappings = new HashMap<>();
    mappings.put("tievalue", Type.SORTED);
    mappings.put("value", Type.SORTED);

    IndexReader ir = UninvertingReader.wrap(writer.getReader(), mappings);
    writer.close();

    IndexSearcher searcher = newSearcher(ir);
    // tievalue, then value
    Sort sort =
        new Sort(
            new SortField("tievalue", SortField.Type.STRING),
            new SortField("value", SortField.Type.STRING));

    TopDocs td = searcher.search(new MatchAllDocsQuery(), 10, sort);
    assertEquals(2, td.totalHits.value);
    // 'bar' comes before 'foo'
    assertEquals("bar", searcher.storedFields().document(td.scoreDocs[0].doc).get("value"));
    assertEquals("foo", searcher.storedFields().document(td.scoreDocs[1].doc).get("value"));
    TestUtil.checkReader(ir);
    ir.close();
    dir.close();
  }

  public void testScore() throws IOException {
    Directory dir = newDirectory();
    RandomIndexWriter writer = new RandomIndexWriter(random(), dir);
    Document doc = new Document();
    doc.add(newStringField("value", "bar", Field.Store.NO));
    writer.addDocument(doc);
    doc = new Document();
    doc.add(newStringField("value", "foo", Field.Store.NO));
    writer.addDocument(doc);
    IndexReader ir = writer.getReader();
    writer.close();

    IndexSearcher searcher = newSearcher(ir);
    Sort sort = new Sort(SortField.FIELD_SCORE);

    final BooleanQuery.Builder bq = new BooleanQuery.Builder();
    bq.add(new TermQuery(new Term("value", "foo")), Occur.SHOULD);
    bq.add(new MatchAllDocsQuery(), Occur.SHOULD);
    TopDocs td = searcher.search(bq.build(), 10, sort);
    assertEquals(2, td.totalHits.value);
    if (Float.isNaN(td.scoreDocs[0].score) == false
        && Float.isNaN(td.scoreDocs[1].score) == false) {
      assertEquals(1, td.scoreDocs[0].doc);
      assertEquals(0, td.scoreDocs[1].doc);
    }
    TestUtil.checkReader(ir);
    ir.close();
    dir.close();
  }
}
