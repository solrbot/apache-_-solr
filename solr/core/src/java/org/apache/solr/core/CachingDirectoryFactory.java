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
package org.apache.solr.core;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.lucene.store.AlreadyClosedException;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FilterDirectory;
import org.apache.lucene.store.LockFactory;
import org.apache.lucene.util.IOUtils;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrException.ErrorCode;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.ObjectReleaseTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link DirectoryFactory} impl base class for caching Directory instances per path. Most
 * DirectoryFactory implementations will want to extend this class and simply implement {@link
 * DirectoryFactory#create(String, LockFactory)}.
 *
 * <p>This is an expert class and these API's are subject to change.
 */
public abstract class CachingDirectoryFactory extends DirectoryFactory {
  protected static class CacheValue {
    public final String path;
    public final Directory directory;
    // for debug
    // final Exception originTrace;
    // use the setter!
    private boolean deleteOnClose = false;

    public CacheValue(String path, Directory directory) {
      this.path = Objects.requireNonNull(path);
      this.directory = Objects.requireNonNull(directory);
      this.closeEntries.add(this);
      // for debug
      // this.originTrace = new RuntimeException("Originated from:");
    }

    public int refCnt = 1;
    // has doneWithDirectory(Directory) been called on this?
    public boolean closeCacheValueCalled = false;
    public boolean doneWithDir = false;
    private boolean deleteAfterCoreClose = false;
    public Set<CacheValue> closeEntries = new HashSet<>();

    public void setDeleteOnClose(boolean deleteOnClose, boolean deleteAfterCoreClose) {
      this.deleteOnClose = deleteOnClose;
      this.deleteAfterCoreClose = deleteAfterCoreClose;
    }

    @Override
    public String toString() {
      return "CachedDir<<" + "refCount=" + refCnt + ";path=" + path + ";done=" + doneWithDir + ">>";
    }
  }

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  protected Map<String, CacheValue> byPathCache = new HashMap<>();

  protected IdentityHashMap<Directory, CacheValue> byDirectoryCache = new IdentityHashMap<>();

  protected Map<Directory, List<CloseListener>> closeListeners = new HashMap<>();

  protected Set<CacheValue> removeEntries = new HashSet<>();

  private Double maxWriteMBPerSecFlush;

  private Double maxWriteMBPerSecMerge;

  private Double maxWriteMBPerSecRead;

  private Double maxWriteMBPerSecDefault;

  private boolean closed;

  public interface CloseListener {
    public void postClose();

    public void preClose();
  }

  @Override
  public void addCloseListener(Directory dir, CloseListener closeListener) {
    synchronized (this) {
      if (!byDirectoryCache.containsKey(dir)) {
        throw new IllegalArgumentException("Unknown directory: " + dir + " " + byDirectoryCache);
      }
      List<CloseListener> listeners = closeListeners.get(dir);
      if (listeners == null) {
        listeners = new ArrayList<>();
        closeListeners.put(dir, listeners);
      }
      listeners.add(closeListener);

      closeListeners.put(dir, listeners);
    }
  }

  @Override
  public void doneWithDirectory(Directory directory) throws IOException {
    synchronized (this) {
      CacheValue cacheValue = byDirectoryCache.get(directory);
      if (cacheValue == null) {
        throw new IllegalArgumentException(
            "Unknown directory: " + directory + " " + byDirectoryCache);
      }
      cacheValue.doneWithDir = true;
      log.debug("Done with dir: {}", cacheValue);
      if (cacheValue.refCnt == 0 && !closed) {
        boolean cl = closeCacheValue(cacheValue, null);
        if (cl) {
          removeFromCache(cacheValue);
        }
      }
    }
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.solr.core.DirectoryFactory#close()
   */
  @Override
  public void close() throws IOException {
    synchronized (this) {
      if (log.isDebugEnabled()) {
        log.debug(
            "Closing {} - {} directories currently being tracked",
            this.getClass().getSimpleName(),
            byDirectoryCache.size());
      }
      this.closed = true;
      Collection<CacheValue> values = byDirectoryCache.values();
      for (CacheValue val : values) {

        if (log.isDebugEnabled()) {
          log.debug("Closing {} - currently tracking: {}", this.getClass().getSimpleName(), val);
        }
        try {
          // if there are still refs out, we have to wait for them
          assert val.refCnt > -1 : val.refCnt;
          int cnt = 0;
          while (val.refCnt != 0) {
            wait(100);

            if (cnt++ >= 120) {
              String msg =
                  "Timeout waiting for all directory ref counts to be released - gave up waiting on "
                      + val;
              log.error(msg);
              // debug
              // val.originTrace.printStackTrace();
              throw new SolrException(ErrorCode.SERVER_ERROR, msg);
            }
          }
          assert val.refCnt == 0 : val.refCnt;
        } catch (Exception e) {
          log.error("Error closing directory", e);
        }
      }

      values = byDirectoryCache.values();
      Set<CacheValue> closedDirs = new HashSet<>();
      for (CacheValue val : values) {
        try {
          for (CacheValue v : val.closeEntries) {
            assert v.refCnt == 0 : val.refCnt;
            log.debug("Closing directory when closing factory: {}", v.path);
            boolean cl = closeCacheValue(v, closedDirs);
            if (cl) {
              closedDirs.add(v);
            }
          }
        } catch (Exception e) {
          log.error("Error closing directory", e);
        }
      }

      for (CacheValue val : sorted(removeEntries)) {
        log.debug("Removing directory after core close: {}", val.path);
        try {
          removeDirectory(val);
        } catch (Exception e) {
          log.error("Error removing directory", e);
        }
      }

      for (CacheValue v : closedDirs) {
        removeFromCache(v);
      }
    }
  }

  private void removeFromCache(CacheValue v) {
    log.debug("Removing from cache: {}", v);
    byDirectoryCache.remove(v.directory);
    byPathCache.remove(v.path);
  }

  // be sure the method is called with the sync lock on this object
  // returns true if we closed the cacheValue, false if it will be closed later
  private boolean closeCacheValue(CacheValue cacheValue, Set<CacheValue> deferRemove) {
    log.debug("looking to close {} {}", cacheValue.path, cacheValue.closeEntries);
    List<CloseListener> listeners = closeListeners.remove(cacheValue.directory);
    if (listeners != null) {
      for (CloseListener listener : listeners) {
        try {
          listener.preClose();
        } catch (Exception e) {
          log.error("Error executing preClose for directory", e);
        }
      }
    }
    cacheValue.closeCacheValueCalled = true;
    if (cacheValue.deleteOnClose && maybeDeferClose(cacheValue)) {
      // we will be closed by a child path
      return false;
    }

    boolean cl = false;
    for (CacheValue val : sorted(cacheValue.closeEntries)) {
      if (!val.deleteOnClose) {
        // just a simple close, do it unconditionally
        close(val);
      } else {
        if (maybeDeferClose(val)) {
          // parent path must have been arbitrarily associated with one of multiple
          // subpaths; so, assuming this one of the subpaths, we now delegate to
          // another live subpath.
          assert val != cacheValue; // else would already have been deferred
          continue;
        }
        close(val);
        if (!val.deleteAfterCoreClose) {
          log.debug("Removing directory before core close: {}", val.path);
          try {
            removeDirectory(val);
          } catch (Exception e) {
            log.error("Error removing directory {} before core close", val.path, e);
          }
        } else {
          removeEntries.add(val);
        }
      }
      if (val == cacheValue) {
        cl = true;
      } else {
        // this was a deferred close, so it's our responsibility to remove it from cache
        assert val.closeEntries.isEmpty();
        if (deferRemove == null) {
          removeFromCache(val);
        } else {
          deferRemove.add(val);
        }
      }
    }

    if (listeners != null) {
      for (CloseListener listener : listeners) {
        try {
          listener.postClose();
        } catch (Exception e) {
          log.error("Error executing postClose for directory", e);
        }
      }
    }
    return cl;
  }

  private static Iterable<CacheValue> sorted(Set<CacheValue> vals) {
    if (vals.size() < 2) {
      return vals;
    }
    // here we reverse-sort entries by path, in order to trivially ensure that
    // subpaths are removed before parent paths.
    return vals.stream().sorted((a, b) -> b.path.compareTo(a.path)).collect(Collectors.toList());
  }

  private boolean maybeDeferClose(CacheValue maybeDefer) {
    assert maybeDefer.deleteOnClose;
    for (CacheValue maybeChildPath : byPathCache.values()) {
      // if we are a parent path and a sub path is not already closed, get a sub path to close us
      // later
      if (maybeDefer != maybeChildPath
          && isSubPath(maybeDefer, maybeChildPath)
          && !maybeChildPath.closeCacheValueCalled) {
        // we let the sub dir remove and close us
        if (maybeChildPath.deleteAfterCoreClose && !maybeDefer.deleteAfterCoreClose) {
          // if we need to hold onto the child path until after core close, then don't allow
          // the parent path to be deleted before!
          maybeDefer.deleteAfterCoreClose = true;
        }
        if (maybeDefer.closeEntries.isEmpty()) {
          // we've already been deferred
          maybeChildPath.closeEntries.add(maybeDefer);
        } else {
          maybeChildPath.closeEntries.addAll(maybeDefer.closeEntries);
          maybeDefer.closeEntries.clear();
        }
        return true;
      }
    }
    if (!maybeDefer.deleteAfterCoreClose) {
      // check whether we need to order ourselves after potential subpath `deleteAfterCoreClose`
      for (CacheValue maybeChildPath : removeEntries) {
        if (isSubPath(maybeDefer, maybeChildPath)) {
          maybeDefer.deleteAfterCoreClose = true;
          break;
        }
      }
    }
    return false;
  }

  private void close(CacheValue val) {
    if (log.isDebugEnabled()) {
      log.debug(
          "Closing directory, CoreContainer#isShutdown={}",
          coreContainer != null ? coreContainer.isShutDown() : "null");
    }
    try {
      if (coreContainer != null
          && coreContainer.isShutDown()
          && val.directory instanceof ShutdownAwareDirectory) {
        log.debug("Closing directory on shutdown: {}", val.path);
        ((ShutdownAwareDirectory) val.directory).closeOnShutdown();
      } else {
        log.debug("Closing directory: {}", val.path);
        val.directory.close();
      }
      assert ObjectReleaseTracker.release(val.directory);
    } catch (Exception e) {
      log.error("Error closing directory", e);
    }
  }

  private static boolean isSubPath(CacheValue cacheValue, CacheValue otherCacheValue) {
    return Path.of(otherCacheValue.path).startsWith(Path.of(cacheValue.path));
  }

  @Override
  public boolean exists(String path) throws IOException {
    // we go by the persistent storage ...
    Path dirPath = Path.of(path);
    if (Files.isReadable(dirPath)) {
      try (DirectoryStream<Path> directory = Files.newDirectoryStream(dirPath)) {
        return directory.iterator().hasNext();
      }
    }
    return false;
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.solr.core.DirectoryFactory#get(java.lang.String,
   * java.lang.String, boolean)
   */
  @Override
  public final Directory get(String path, DirContext dirContext, String rawLockType)
      throws IOException {
    String fullPath = normalize(path);
    Directory directory;
    CacheValue cacheValue;
    synchronized (this) {
      if (closed) {
        throw new AlreadyClosedException("Already closed");
      }

      cacheValue = byPathCache.get(fullPath);
      if (cacheValue == null) {
        directory = create(fullPath, createLockFactory(rawLockType));
        assert ObjectReleaseTracker.track(directory);
        boolean success = false;
        try {
          cacheValue = new CacheValue(fullPath, directory);
          byDirectoryCache.put(directory, cacheValue);
          byPathCache.put(fullPath, cacheValue);
          log.debug("return new directory for {}", fullPath);
          success = true;
        } finally {
          if (!success) {
            IOUtils.closeWhileHandlingException(directory);
          }
        }
      } else {
        directory = cacheValue.directory;
        cacheValue.refCnt++;
        log.debug("Reusing cached directory: {}", cacheValue);
      }
    }

    Directory filteredDir = filterDirectory(directory, dirContext);
    // If the directory is filtered/unwrapped, we need to wrap it in a ReleasableDirectory
    // form to be able to recognize it when release(Directory) is called.
    return filteredDir == directory ? directory : new ReleasableDirectory(filteredDir, cacheValue);
  }

  /**
   * Potentially filters or unwraps the cached {@link Directory} depending on the intended use
   * defined by the {@link org.apache.solr.core.DirectoryFactory.DirContext}.
   *
   * @param dir the {@link Directory} cached by this {@link CachingDirectoryFactory}.
   * @param dirContext the nature or the intended use of the directory.
   * @return a filtered or unwrapped version of the directory parameter, or directly the directory
   *     parameter if it does not need any filtering/unwrapping.
   */
  protected Directory filterDirectory(Directory dir, DirContext dirContext) {
    // If the DirContext is REPLICATION or BACKUP, then unwrap the Directory to allow the caller to
    // copy raw bytes, skipping any additional logic that would be added by a FilterDirectory on top
    // of the raw Directory.
    return dirContext == DirContext.REPLICATION || dirContext == DirContext.BACKUP
        ? FilterDirectory.unwrap(dir)
        : dir;
  }

  /*
   * (non-Javadoc)
   *
   * @see
   * org.apache.solr.core.DirectoryFactory#incRef(org.apache.lucene.store.Directory
   * )
   */
  @Override
  public void incRef(Directory directory) {
    synchronized (this) {
      if (closed) {
        throw new SolrException(ErrorCode.SERVICE_UNAVAILABLE, "Already closed");
      }
      CacheValue cacheValue = byDirectoryCache.get(directory);
      if (cacheValue == null) {
        throw new IllegalArgumentException("Unknown directory: " + directory);
      }

      cacheValue.refCnt++;
      log.debug("incRef'ed: {}", cacheValue);
    }
  }

  @Override
  public void init(NamedList<?> args) {
    maxWriteMBPerSecFlush = (Double) args.get("maxWriteMBPerSecFlush");
    maxWriteMBPerSecMerge = (Double) args.get("maxWriteMBPerSecMerge");
    maxWriteMBPerSecRead = (Double) args.get("maxWriteMBPerSecRead");
    maxWriteMBPerSecDefault = (Double) args.get("maxWriteMBPerSecDefault");

    // override global config
    if (args.get(SolrXmlConfig.SOLR_DATA_HOME) != null) {
      dataHomePath =
          Path.of((String) args.get(SolrXmlConfig.SOLR_DATA_HOME)).toAbsolutePath().normalize();
    }
    if (dataHomePath != null) {
      log.info("{} = {}", SolrXmlConfig.SOLR_DATA_HOME, dataHomePath);
    }
  }

  /*
   * (non-Javadoc)
   *
   * @see
   * org.apache.solr.core.DirectoryFactory#release(org.apache.lucene.store.Directory
   * )
   */
  @Override
  public void release(Directory directory) throws IOException {
    if (directory == null) {
      throw new NullPointerException();
    }
    synchronized (this) {
      // don't check if already closed here - we need to be able to release
      // while #close() waits.

      CacheValue cacheValue = byDirectoryCache.get(directory);
      if (cacheValue == null) {
        // The directory is not registered, it is a ReleasableDirectory wrapper.
        try {
          cacheValue = ((ReleasableDirectory) directory).cacheValue;
        } catch (ClassCastException e) {
          throw new IllegalArgumentException(
              "Unknown directory: " + directory + " " + byDirectoryCache);
        }
      }
      if (log.isDebugEnabled()) {
        log.debug(
            "Releasing directory: {} {} {}",
            cacheValue.path,
            (cacheValue.refCnt - 1),
            cacheValue.doneWithDir);
      }

      cacheValue.refCnt--;

      assert cacheValue.refCnt >= 0 : cacheValue.refCnt;

      if (cacheValue.refCnt == 0 && cacheValue.doneWithDir && !closed) {
        boolean cl = closeCacheValue(cacheValue, null);
        if (cl) {
          removeFromCache(cacheValue);
        }
      }
    }
  }

  @Override
  public void remove(String path) throws IOException {
    remove(path, false);
  }

  @Override
  public void remove(Directory dir) throws IOException {
    remove(dir, false);
  }

  @Override
  public void remove(String path, boolean deleteAfterCoreClose) throws IOException {
    synchronized (this) {
      CacheValue val = byPathCache.get(normalize(path));
      if (val == null) {
        throw new IllegalArgumentException("Unknown directory " + path);
      }
      val.setDeleteOnClose(true, deleteAfterCoreClose);
    }
  }

  @Override
  public void remove(Directory dir, boolean deleteAfterCoreClose) throws IOException {
    synchronized (this) {
      CacheValue val = byDirectoryCache.get(dir);
      if (val == null) {
        throw new IllegalArgumentException("Unknown directory " + dir);
      }
      val.setDeleteOnClose(true, deleteAfterCoreClose);
    }
  }

  protected synchronized void removeDirectory(CacheValue cacheValue) throws IOException {
    // this page intentionally left blank
  }

  @Override
  public String normalize(String path) throws IOException {
    path = stripTrailingSlash(path);
    return path;
  }

  protected String stripTrailingSlash(String path) {
    if (path.endsWith("/")) {
      path = path.substring(0, path.length() - 1);
    }
    return path;
  }

  /**
   * Method for inspecting the cache
   *
   * @return paths in the cache which have not been marked "done"
   * @see #doneWithDirectory
   */
  public synchronized Set<String> getLivePaths() {
    HashSet<String> livePaths = new HashSet<>();
    for (CacheValue val : byPathCache.values()) {
      if (!val.doneWithDir) {
        livePaths.add(val.path);
      }
    }
    return livePaths;
  }

  @Override
  protected boolean deleteOldIndexDirectory(String oldDirPath) throws IOException {
    Set<String> livePaths = getLivePaths();
    if (livePaths.contains(oldDirPath)) {
      log.warn(
          "Cannot delete directory {} as it is still being referenced in the cache!", oldDirPath);
      return false;
    }

    return super.deleteOldIndexDirectory(oldDirPath);
  }

  protected synchronized String getPath(Directory directory) {
    return byDirectoryCache.get(directory).path;
  }

  /**
   * Delegates to a filtered or unwrapped directory, and allows this caching factory to release
   * correctly the corresponding cached directory.
   */
  public static class ReleasableDirectory extends FilterDirectory {

    private final CacheValue cacheValue;

    private ReleasableDirectory(Directory filteredDir, CacheValue cacheValue) {
      super(filteredDir);
      assert cacheValue != null;
      this.cacheValue = cacheValue;
    }
  }
}
