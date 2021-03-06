/**
 *  Copyright 2011 Terracotta, Inc.
 *  Copyright 2011 Oracle America Incorporated
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package javax.cache.implementation;

import javax.cache.Cache;
import javax.cache.CacheBuilder;
import javax.cache.CacheConfiguration;
import javax.cache.CacheException;
import javax.cache.CacheLoader;
import javax.cache.CacheManager;
import javax.cache.CacheStatisticsMBean;
import javax.cache.CacheStatus;
import javax.cache.event.CacheEntryListener;
import javax.cache.event.NotificationScope;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

/**
 * The reference implementation for JSR107.
 * <p/>
 * This is meant to act as a proof of concept for the API. It is not threadsafe or high performance. It therefore is
 * not suitable for use in production. Please use a production implementation of the API.
 * <p/>
 * This implementation implements all optional parts of JSR107 except for the Transactions chapter. Transactions support
 * simply uses the JTA API. The JSR107 specification details how JTA should be applied to caches.
 *
 * @param <K> the type of keys maintained by this map
 * @param <V> the type of mapped values*
 * @author Greg Luck
 * @author Yannis Cosmadopoulos
 */
public final class RICache<K, V> implements Cache<K, V> {
    private static final int CACHE_LOADER_THREADS = 2;

    private final RISimpleCache<K, V> store;
    private final String cacheName;
    private final CacheConfiguration configuration;
    private final CacheLoader<K, V> cacheLoader;
    private final ExecutorService executorService = Executors.newFixedThreadPool(CACHE_LOADER_THREADS);
    private volatile CacheStatus status;
    private final Set<ScopedListener> cacheEntryListeners = new CopyOnWriteArraySet<ScopedListener>();
    private volatile CacheManager cacheManager;
    private volatile RICacheStatistics statistics;

    /**
     * Constructs a cache.
     *
     * @param cacheName     the cache name
     * @param cacheManagerName the cache manager name
     * @param configuration the configuration
     * @param cacheLoader   the cache loader
     */
    private RICache(String cacheName, String cacheManagerName, CacheConfiguration configuration,
                    CacheLoader<K, V> cacheLoader, CopyOnWriteArraySet<ListenerRegistration<K, V>> listeners) {
        status = CacheStatus.UNINITIALISED;
        assert configuration != null;
        assert cacheName != null;
        assert cacheManagerName != null;
        this.cacheName = cacheName;
        this.configuration = new RIWrappedCacheConfiguration(configuration);
        this.cacheLoader = cacheLoader;
        store = configuration.isStoreByValue() ?
            new RIByValueSimpleCache<K, V>(new RIByValueSerializer<K>(), new RIByValueSerializer<V>()) :
            new RIByReferenceSimpleCache<K, V>();
        statistics = new RICacheStatistics(this, cacheManagerName);
        for (ListenerRegistration<K, V> listener : listeners) {
            registerCacheEntryListener(listener.cacheEntryListener, listener.scope, listener.synchronous);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getName() {
        return cacheName;
    }

    /**
     * @inheritDoc
     */
    @Override
    public CacheManager getCacheManager() {
        return cacheManager;
    }

    /**
     * {@inheritDoc}
     */
    public V get(Object key) throws CacheException {
        checkStatusStarted();
        //noinspection SuspiciousMethodCalls
        return getInternal(key);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Map<K, V> getAll(Collection<? extends K> keys) {
        checkStatusStarted();
        if (keys.contains(null)) {
            throw new NullPointerException("key");
        }
        // will throw NPE if keys=null
        HashMap<K, V> map = new HashMap<K, V>(keys.size());
        for (K key : keys) {
            map.put(key, getInternal(key));
        }
        return map;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean containsKey(Object key) {
        checkStatusStarted();
        //noinspection SuspiciousMethodCalls
        return store.containsKey(key);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Future<V> load(K key, CacheLoader<K, V> specificLoader, Object loaderArgument) {
        checkStatusStarted();
        CacheLoader<K, V> loader = getCacheLoader(specificLoader);
        if (loader == null) {
            return null;
        }
        if (key == null) {
            throw new NullPointerException("key");
        }
        if (containsKey(key)) {
            return null;
        }
        FutureTask<V> task = new FutureTask<V>(new RICacheLoaderLoadCallable<K, V>(this, loader, key, loaderArgument));
        executorService.submit(task);
        return task;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Future<Map<K, V>> loadAll(Collection<? extends K> keys, CacheLoader<K, V> specificLoader, Object loaderArgument) {
        checkStatusStarted();
        if (keys == null) {
            throw new NullPointerException("keys");
        }
        CacheLoader<K, V> loader = getCacheLoader(specificLoader);
        if (loader == null) {
            return null;
        }
        if (keys.contains(null)) {
            throw new NullPointerException("key");
        }
        FutureTask<Map<K, V>> task = new FutureTask<Map<K, V>>(new RICacheLoaderLoadAllCallable<K, V>(this, loader, keys, loaderArgument));
        executorService.submit(task);
        return task;
    }

    private CacheLoader<K, V> getCacheLoader(CacheLoader<K, V> specificLoader) {
        return specificLoader == null ? cacheLoader : specificLoader;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CacheStatisticsMBean getStatistics() {
        checkStatusStarted();
        if (statisticsEnabled()) {
            return statistics;
        } else {
            return null;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void put(K key, V value) {
        checkStatusStarted();
        store.put(key, value);
        if (statisticsEnabled()) {
            statistics.increaseCachePuts(1);
        }
    }

    @Override
    public V getAndPut(K key, V value) throws CacheException {
        checkStatusStarted();
        V result = store.getAndPut(key, value);
        if (statisticsEnabled()) {
            statistics.increaseCachePuts(1);
        }
        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void putAll(Map<? extends K, ? extends V> map) {
        checkStatusStarted();
        if (map.containsKey(null)) {
            throw new NullPointerException("key");
        }
        store.putAll(map);
        if (statisticsEnabled()) {
            statistics.increaseCachePuts(map.size());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean putIfAbsent(K key, V value) {
        checkStatusStarted();
        boolean result = store.putIfAbsent(key, value);
        if (result && statisticsEnabled()) {
            statistics.increaseCachePuts(1);
        }
        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean remove(Object key) {
        checkStatusStarted();
        boolean result = store.remove(key);
        if (result && statisticsEnabled()) {
            statistics.increaseCacheRemovals(1);
        }
        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public V getAndRemove(Object key) {
        checkStatusStarted();
        V result = store.getAndRemove(key);
        if (statisticsEnabled()) {
            if (result != null) {
                statistics.increaseCacheHits(1);
                statistics.increaseCacheRemovals(1);
            } else {
                statistics.increaseCacheMisses(1);
            }
        }
        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean replace(K key, V oldValue, V newValue) {
        checkStatusStarted();
        if (store.replace(key, oldValue, newValue)) {
            if (statisticsEnabled()) {
                statistics.increaseCachePuts(1);
            }
            return true;
        } else {
            return false;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean replace(K key, V value) {
        checkStatusStarted();
        boolean result = store.replace(key, value);
        if (statisticsEnabled()) {
            statistics.increaseCachePuts(1);
        }
        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public V getAndReplace(K key, V value) {
        checkStatusStarted();
        V result = store.getAndReplace(key, value);
        if (statisticsEnabled()) {
            if (result != null) {
                statistics.increaseCacheHits(1);
                statistics.increaseCachePuts(1);
            } else {
                statistics.increaseCacheMisses(1);
            }
        }
        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void removeAll(Collection<? extends K> keys) {
        checkStatusStarted();
        for (K key : keys) {
            store.remove(key);
        }
        if (statisticsEnabled()) {
            statistics.increaseCacheRemovals(keys.size());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void removeAll() {
        checkStatusStarted();
        int size = (statisticsEnabled()) ? store.size() : 0;
        //possible race here but it is only stats
        store.removeAll();
        if (statisticsEnabled()) {
            statistics.increaseCacheRemovals(size);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CacheConfiguration getConfiguration() {
        return configuration;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean registerCacheEntryListener(CacheEntryListener<K, V> cacheEntryListener, NotificationScope scope, boolean synchronous) {
        ScopedListener scopedListener = new ScopedListener(cacheEntryListener, scope, synchronous);
        return cacheEntryListeners.add(scopedListener);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean unregisterCacheEntryListener(CacheEntryListener cacheEntryListener) {
        //Only cacheEntryListener is checked for equality
        ScopedListener scopedListener = new ScopedListener(cacheEntryListener, null, true);
        return cacheEntryListeners.remove(scopedListener);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Iterator<Entry<K, V>> iterator() {
        checkStatusStarted();
        return new RIEntryIterator<K, V>(store.iterator());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void start() throws CacheException {
        status = CacheStatus.STARTED;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void stop() throws CacheException {
        status = CacheStatus.STOPPING;
        executorService.shutdown();
        //TODO: maybe wait for executor to stop
        store.removeAll();
        status = CacheStatus.STOPPED;
    }

    private void checkStatusStarted() {
        if (!status.equals(CacheStatus.STARTED)) {
            throw new IllegalStateException("The cache status is not STARTED");
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CacheStatus getStatus() {
        return status;
    }

    /**
     * Sets the CacheManager. This may only be done once.
     * @param cacheManager the CacheManager this cache has been added to.
     * @throws CacheException if done more than once
     */
    void setCacheManager(CacheManager cacheManager) {
        if (this.cacheManager != null) {
            throw new CacheException("A cache can only be associated with a CacheManager once");
        }
        this.cacheManager = cacheManager;
        //needs the CacheManager
        statistics = new RICacheStatistics(this, cacheManager.getName());
    }

    private boolean statisticsEnabled() {
        return configuration.isStatisticsEnabled();
    }

    /**
     * Combine a Listener and its NotificationScope.  Equality and hashcode are based purely on the listener.
     * This implies that the same listener cannot be added to the set of registered listeners more than
     * once with different notification scopes.
     *
     * @author Greg Luck
     */
    private static final class ScopedListener {
        private final CacheEntryListener listener;
        private final NotificationScope scope;
        private final boolean synchronous;

        private ScopedListener(CacheEntryListener listener, NotificationScope scope, boolean synchronous) {
            this.listener = listener;
            this.scope = scope;
            this.synchronous = synchronous;
        }

        private CacheEntryListener getListener() {
            return listener;
        }

        private NotificationScope getScope() {
            return scope;
        }

        /**
         * Hash code based on listener
         *
         * @see java.lang.Object#hashCode()
         */
        @Override
        public int hashCode() {
            return listener.hashCode();
        }

        /**
         * Equals based on listener (NOT based on scope) - can't have same listener with two different scopes
         *
         * @see java.lang.Object#equals(java.lang.Object)
         */
        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            ScopedListener other = (ScopedListener) obj;
            if (listener == null) {
                if (other.listener != null) {
                    return false;
                }
            } else if (!listener.equals(other.listener)) {
                return false;
            }
            return true;
        }

        @Override
        public String toString() {
            return listener.toString();
        }
    }

    /**
     * {@inheritDoc}
     *
     * @author Yannis Cosmadopoulos
     */
    private static class RIEntry<K, V> implements Entry<K, V> {
        private final K key;
        private final V value;

        public RIEntry(K key, V value) {
            if (key == null) {
                throw new NullPointerException("key");
            }
            this.key = key;
            this.value = value;
        }

        @Override
        public K getKey() {
            return key;
        }

        @Override
        public V getValue() {
            return value;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            RIEntry e2 = (RIEntry) o;

            return this.getKey().equals(e2.getKey()) &&
                    this.getValue().equals(e2.getValue());
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public int hashCode() {
            return getKey().hashCode() ^ getValue().hashCode();
        }
    }

    /**
     * {@inheritDoc}
     *
     * @author Yannis Cosmadopoulos
     */
    private static final class RIEntryIterator<K, V> implements Iterator<Entry<K, V>> {
        private final Iterator<Map.Entry<K, V>> mapIterator;

        private RIEntryIterator(Iterator<Map.Entry<K, V>> mapIterator) {
            this.mapIterator = mapIterator;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean hasNext() {
            return mapIterator.hasNext();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public Entry<K, V> next() {
            Map.Entry<K, V> mapEntry = mapIterator.next();
            return new RIEntry<K, V>(mapEntry.getKey(), mapEntry.getValue());
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void remove() {
            mapIterator.remove();
        }
    }

    /**
     * Callable used for cache loader.
     *
     * @param <K> the type of the key
     * @param <V> the type of the value
     * @author Yannis Cosmadopoulos
     */
    private static class RICacheLoaderLoadCallable<K, V> implements Callable<V> {
        private final RICache<K, V> cache;
        private final CacheLoader<K, V> cacheLoader;
        private final K key;
        private final Object arg;

        RICacheLoaderLoadCallable(RICache<K, V> cache, CacheLoader<K, V> cacheLoader, K key, Object arg) {
            this.cache = cache;
            this.cacheLoader = cacheLoader;
            this.key = key;
            this.arg = arg;
        }

        @Override
        public V call() throws Exception {
            Entry<K, V> entry = cacheLoader.load(key, arg);
            cache.put(entry.getKey(), entry.getValue());
            return entry.getValue();
        }
    }

    /**
     * Callable used for cache loader.
     *
     * @param <K> the type of the key
     * @param <V> the type of the value
     * @author Yannis Cosmadopoulos
     */
    private static class RICacheLoaderLoadAllCallable<K, V> implements Callable<Map<K, V>> {
        private final RICache<K, V> cache;
        private final CacheLoader<K, V> cacheLoader;
        private final Collection<? extends K> keys;
        private final Object arg;

        RICacheLoaderLoadAllCallable(RICache<K, V> cache, CacheLoader<K, V> cacheLoader, Collection<? extends K> keys, Object arg) {
            this.cache = cache;
            this.cacheLoader = cacheLoader;
            this.keys = keys;
            this.arg = arg;
        }

        @Override
        public Map<K, V> call() throws Exception {
            ArrayList<K> keysNotInStore = new ArrayList<K>();
            for (K key : keys) {
                if (!cache.containsKey(key)) {
                    keysNotInStore.add(key);
                }
            }
            Map<K, V> value = cacheLoader.loadAll(keysNotInStore, arg);
            cache.putAll(value);
            return value;
        }
    }

    /**
     * A Builder for RICache.
     *
     * @param <K>
     * @param <V>
     * @author Yannis Cosmadopoulos
     */
    public static class Builder<K, V> implements CacheBuilder<K, V> {
        private final String cacheName;
        private final String cacheManagerName;
        private CacheConfiguration configuration;
        private CacheLoader<K, V> cacheLoader;
        private final CopyOnWriteArraySet<ListenerRegistration<K, V>> listeners = new CopyOnWriteArraySet<ListenerRegistration<K, V>>();

        /**
         * Construct a builder.
         *
         * @param cacheName the name of the cache to be built
         * @param cacheManagerName the name of the cache manager
         */
        public Builder(String cacheName, String cacheManagerName) {
            if (cacheName == null) {
                throw new NullPointerException("cacheName");
            }
            this.cacheName = cacheName;
            if (cacheManagerName == null) {
                throw new NullPointerException("cacheManagerName");
            }
            this.cacheManagerName = cacheManagerName;
        }

        /**
         * Builds the cache
         *
         * @return a constructed cache.
         */
        @Override
        public RICache<K, V> build() {
            if (configuration == null) {
                configuration = new RICacheConfiguration.Builder().build();
            }
            return new RICache<K, V>(cacheName, cacheManagerName, configuration, cacheLoader, listeners);
        }

        /**
         * Set the cache configuration.
         *
         * @param configuration the cache configuration
         * @return the builder
         */
        @Override
        public Builder<K, V> setCacheConfiguration(CacheConfiguration configuration) {
            if (configuration == null) {
                throw new NullPointerException("configuration");
            }
            this.configuration = configuration;
            return this;
        }

        /**
         * Set the cache loader.
         *
         * @param cacheLoader the CacheLoader
         * @return the builder
         */
        @Override
        public Builder<K, V> setCacheLoader(CacheLoader<K, V> cacheLoader) {
            if (cacheLoader == null) {
                throw new NullPointerException("cacheLoader");
            }
            this.cacheLoader = cacheLoader;
            return this;
        }

        @Override
        public CacheBuilder<K, V> registerCacheEntryListener(CacheEntryListener<K, V> listener, NotificationScope scope, boolean synchronous) {
            listeners.add(new ListenerRegistration<K, V>(listener, scope, synchronous));
            return this;
        }
    }

    /**
     * A struct :)
     * @param <K>
     * @param <V>
     */
    private static final class ListenerRegistration<K, V> {
        private final CacheEntryListener<K, V> cacheEntryListener;
        private final NotificationScope scope;
        private final boolean synchronous;

        private ListenerRegistration(CacheEntryListener<K, V> cacheEntryListener, NotificationScope scope, boolean synchronous) {
            this.cacheEntryListener = cacheEntryListener;
            this.scope = scope;
            this.synchronous = synchronous;
        }
    }

    private V getInternal(Object key) {
        //noinspection SuspiciousMethodCalls
        V  value = store.get(key);
        if (value == null) {
            if (cacheLoader != null) {
                return getFromLoader(key);
            } else {
                return null;
            }
        } else {
            return value;
        }
    }

    private V getFromLoader(Object key) {
        Cache.Entry<K, V> entry = cacheLoader.load(key, null);
        if (entry != null) {
            store.put(entry.getKey(), entry.getValue());
            return entry.getValue();
        } else {
            return null;
        }
    }

    /**
     * Returns the size of the cache.
     *
     * @return the size in entries of the cache
     */
    long getSize() {
        return store.size();
    }
}
