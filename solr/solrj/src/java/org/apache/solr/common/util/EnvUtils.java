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

package org.apache.solr.common.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.apache.solr.common.SolrException;

/**
 * Provides convenient access to System Properties for Solr. It also converts 'SOLR_FOO' env vars to
 * system properties 'solr.foo', which is done on first access of this class. All Solr code should
 * use this in lieu of JDK equivalents.
 */
public class EnvUtils {
  /** Maps ENV keys to sys prop keys for special/custom mappings */
  private static final Map<String, String> CUSTOM_MAPPINGS = new HashMap<>();

  private static final Map<String, String> camelCaseToDotsMap = new ConcurrentHashMap<>();

  static {
    try {
      Properties props = new Properties();
      try (InputStream stream =
          EnvUtils.class.getClassLoader().getResourceAsStream("EnvToSyspropMappings.properties")) {
        props.load(new InputStreamReader(Objects.requireNonNull(stream), StandardCharsets.UTF_8));
        for (String key : props.stringPropertyNames()) {
          CUSTOM_MAPPINGS.put(key, props.getProperty(key));
        }
        init(false, System.getenv());
      }
    } catch (IOException e) {
      throw new SolrException(
          SolrException.ErrorCode.INVALID_STATE, "Failed loading env.var->properties mapping", e);
    }
  }

  /** Get all Solr system properties as a sorted map */
  public static SortedMap<String, String> getProperties() {
    return System.getProperties().entrySet().stream()
        .collect(
            Collectors.toMap(
                entry -> entry.getKey().toString(),
                entry -> entry.getValue().toString(),
                (e1, e2) -> e1,
                TreeMap::new));
  }

  /** Get a property as string */
  public static String getProperty(String key) {
    return getProperty(key, null);
  }

  /**
   * Get a property as string with a fallback value. All other getProperty* methods use this.
   *
   * @param key property key, which treats 'camelCase' the same as 'camel.case'
   * @param defaultValue fallback value if property is not found
   */
  public static String getProperty(String key, String defaultValue) {
    String value = getPropertyWithCamelCaseFallback(key);
    return value != null ? value : defaultValue;
  }

  /**
   * Get a property from given key or an alias key converted from CamelCase to dot separated.
   *
   * @return property value or value of dot-separated alias key or null if not found
   */
  private static String getPropertyWithCamelCaseFallback(String key) {
    String value = System.getProperty(key);
    if (value != null) {
      return value;
    } else {
      // Figure out if string is CamelCase and convert to dot separated
      String altKey = camelCaseToDotSeparated(key);
      return System.getProperty(altKey);
    }
  }

  private static String camelCaseToDotSeparated(String key) {
    return camelCaseToDotsMap.computeIfAbsent(
        key,
        (k) -> String.join(".", k.split("(?=[A-Z])")).replace("..", ".").toLowerCase(Locale.ROOT));
  }

  /** Get property as integer */
  public static Integer getPropertyAsInteger(String key) {
    return getPropertyAsInteger(key, null);
  }

  /** Get property as integer, or default value */
  public static Integer getPropertyAsInteger(String key, Integer defaultValue) {
    String value = getProperty(key);
    if (value == null) {
      return defaultValue;
    }
    return Integer.parseInt(value);
  }

  /** Get property as long */
  public static Long getPropertyAsLong(String key) {
    return getPropertyAsLong(key, null);
  }

  /** Get property as long, or default value */
  public static Long getPropertyAsLong(String key, Long defaultValue) {
    String value = getProperty(key);
    if (value == null) {
      return defaultValue;
    }
    return Long.parseLong(value);
  }

  /** Get property as boolean */
  public static Boolean getPropertyAsBool(String key) {
    return getPropertyAsBool(key, null);
  }

  /** Get property as boolean, or default value */
  public static Boolean getPropertyAsBool(String key, Boolean defaultValue) {
    String value = getProperty(key);
    if (value == null) {
      return defaultValue;
    }
    return StrUtils.parseBool(value);
  }

  /**
   * Get comma separated strings from sysprop as List
   *
   * @return list of strings, or null if not found
   */
  public static List<String> getPropertyAsList(String key) {
    return getPropertyAsList(key, null);
  }

  /**
   * Get comma separated strings from sysprop as List, or default value
   *
   * @return list of strings, or provided default if not found
   */
  public static List<String> getPropertyAsList(String key, List<String> defaultValue) {
    return getProperty(key) != null ? stringValueToList(getProperty(key)) : defaultValue;
  }

  /** Set a system property. Shim to {@link System#setProperty(String, String)} */
  public static void setProperty(String key, String value) {
    System.setProperty(key, value);
    System.setProperty(camelCaseToDotSeparated(key), value);
  }

  /**
   * Re-reads environment variables and updates the internal map. Mainly for internal and test use.
   */
  static synchronized void init(boolean overwrite, Map<String, String> env) {
    // Convert eligible environment variables to system properties
    for (String key : env.keySet()) {
      if (key.startsWith("SOLR_") || CUSTOM_MAPPINGS.containsKey(key)) {
        String sysPropKey = envNameToSyspropName(key);
        // Existing system properties take precedence
        if (!sysPropKey.isBlank() && (overwrite || getProperty(sysPropKey, null) == null)) {
          setProperty(sysPropKey, env.get(key));
        }
      }
    }
  }

  protected static String envNameToSyspropName(String envName) {
    return CUSTOM_MAPPINGS.containsKey(envName)
        ? CUSTOM_MAPPINGS.get(envName)
        : envName.toLowerCase(Locale.ROOT).replace("_", ".");
  }

  /**
   * Convert a string to a List&lt;String&gt;. If the string is a JSON array, it will be parsed as
   * such. String splitting uses "splitSmart" which supports backslash escaped characters.
   */
  @SuppressWarnings("unchecked")
  private static List<String> stringValueToList(String string) {
    if (string.startsWith("[") && string.endsWith("]")) {
      // Convert a JSON string to a List<String> using Noggit parser
      return (List<String>) Utils.fromJSONString(string);
    } else {
      return StrUtils.splitSmart(string, ",", true).stream()
          .map(String::trim)
          .collect(Collectors.toList());
    }
  }
}
