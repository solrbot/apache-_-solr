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
package org.apache.solr.client.solrj.impl;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Set;
import org.apache.solr.client.solrj.ResponseParser;
import org.apache.solr.common.util.JavaBinCodec;
import org.apache.solr.common.util.NamedList;

/**
 * @since solr 1.3
 */
public class JavaBinResponseParser extends ResponseParser {
  public static final String JAVABIN_CONTENT_TYPE_V2 = "application/vnd.apache.solr.javabin";
  public static final String JAVABIN_CONTENT_TYPE = "application/octet-stream";

  protected JavaBinCodec.StringCache stringCache;

  public JavaBinResponseParser setStringCache(JavaBinCodec.StringCache cache) {
    this.stringCache = cache;
    return this;
  }

  @Override
  public String getWriterType() {
    return "javabin";
  }

  @Override
  @SuppressWarnings({"unchecked"})
  public NamedList<Object> processResponse(InputStream body, String encoding) throws IOException {
    return (NamedList<Object>) createCodec().unmarshal(body);
  }

  protected JavaBinCodec createCodec() {
    return new JavaBinCodec(null, stringCache);
  }

  @Override
  public Collection<String> getContentTypes() {
    return Set.of(JAVABIN_CONTENT_TYPE, JAVABIN_CONTENT_TYPE_V2);
  }
}
