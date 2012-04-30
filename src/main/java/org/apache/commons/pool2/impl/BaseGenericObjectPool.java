/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.commons.pool2.impl;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.management.ListenerNotFoundException;
import javax.management.MBeanNotificationInfo;
import javax.management.Notification;
import javax.management.NotificationBroadcasterSupport;
import javax.management.NotificationEmitter;
import javax.management.NotificationFilter;
import javax.management.NotificationListener;
import javax.management.ObjectName;

/**
 * Base class that provides common functionality for {@link GenericObjectPool}
 * and {@link GenericKeyedObjectPool}. The primary reason this class exists is
 * reduce code duplication between the two pool implementations.
 *
 * @param <T> Type of element pooled in this pool.
 */
public abstract class BaseGenericObjectPool<T> implements NotificationEmitter {

    // Constants
    /**
     * Name of the JMX notification broadcast when the pool implementation
     * swallows an {@link Exception}.
     */
    public static final String NOTIFICATION_SWALLOWED_EXCEPTION =
            "SWALLOWED_EXCEPTION";
    /**
     * The size of the caches used to store historical data for some attributes
     * so that rolling means may be calculated.
     */
    public static final int MEAN_TIMING_STATS_CACHE_SIZE = 100;
    private static final int SWALLOWED_EXCEPTION_QUEUE_SIZE = 10;

    // Configuration attributes
    private volatile int maxTotal =
            GenericKeyedObjectPoolConfig.DEFAULT_MAX_TOTAL;
    private volatile boolean blockWhenExhausted =
            GenericObjectPoolConfig.DEFAULT_BLOCK_WHEN_EXHAUSTED;
    private volatile long maxWaitMillis =
            GenericKeyedObjectPoolConfig.DEFAULT_MAX_WAIT_MILLIS;
    private volatile boolean lifo = GenericObjectPoolConfig.DEFAULT_LIFO;
    private volatile boolean testOnBorrow =
            GenericObjectPoolConfig.DEFAULT_TEST_ON_BORROW;
    private volatile boolean testOnReturn =
            GenericObjectPoolConfig.DEFAULT_TEST_ON_RETURN;
    private volatile boolean testWhileIdle =
            GenericObjectPoolConfig.DEFAULT_TEST_WHILE_IDLE;
    private volatile long timeBetweenEvictionRunsMillis =
            GenericObjectPoolConfig.DEFAULT_TIME_BETWEEN_EVICTION_RUNS_MILLIS;
    private volatile int numTestsPerEvictionRun =
            GenericObjectPoolConfig.DEFAULT_NUM_TESTS_PER_EVICTION_RUN;
    private volatile long minEvictableIdleTimeMillis =
            GenericObjectPoolConfig.DEFAULT_MIN_EVICTABLE_IDLE_TIME_MILLIS;
    private volatile long softMinEvictableIdleTimeMillis =
            GenericObjectPoolConfig.DEFAULT_SOFT_MIN_EVICTABLE_IDLE_TIME_MILLIS;
    private volatile EvictionPolicy<T> evictionPolicy;


    // Internal (primarily state) attributes
    volatile boolean closed = false;
    final Object evictionLock = new Object();
    private Evictor evictor = null; // @GuardedBy("evictionLock")
    Iterator<PooledObject<T>> evictionIterator = null; // @GuardedBy("evictionLock")
    /*
     * Class loader for evictor thread to use since in a J2EE or similar
     * environment the context class loader for the evictor thread may have
     * visibility of the correct factory. See POOL-161.
     */
    private final ClassLoader factoryClassLoader;


    // Monitoring (primarily JMX) attributes
    private final NotificationBroadcasterSupport jmxNotificationSupport;
    private final String creationStackTrace;
    private final Deque<String> swallowedExceptions = new LinkedList<String>();
    private final AtomicInteger swallowedExcpetionCount = new AtomicInteger(0);
    private final AtomicLong borrowedCount = new AtomicLong(0);
    private final AtomicLong returnedCount = new AtomicLong(0);
    final AtomicLong createdCount = new AtomicLong(0);
    final AtomicLong destroyedCount = new AtomicLong(0);
    final AtomicLong destroyedByEvictorCount = new AtomicLong(0);
    final AtomicLong destroyedByBorrowValidationCount = new AtomicLong(0);
    private final LinkedList<Long> activeTimes = new LinkedList<Long>(); // @GuardedBy("activeTimes") - except in initStats()
    private final LinkedList<Long> idleTimes = new LinkedList<Long>(); // @GuardedBy("activeTimes") - except in initStats()
    private final LinkedList<Long> waitTimes = new LinkedList<Long>(); // @GuardedBy("activeTimes") - except in initStats()
    private final Object maxBorrowWaitTimeMillisLock = new Object();
    private volatile long maxBorrowWaitTimeMillis = 0; // @GuardedBy("maxBorrowWaitTimeMillisLock")


    public BaseGenericObjectPool(BaseObjectPoolConfig config) {
        if (config.getJmxEnabled()) {
            this.jmxNotificationSupport = new NotificationBroadcasterSupport();
        } else {
            this.jmxNotificationSupport = null;
        }

        // Populate the swallowed exceptions queue
        for (int i = 0; i < SWALLOWED_EXCEPTION_QUEUE_SIZE; i++) {
            swallowedExceptions.add(null);
        }

        // Populate the creation stack trace
        this.creationStackTrace = getStackTrace(new Exception());

        // save the current CCL to be used later by the evictor Thread
        factoryClassLoader = Thread.currentThread().getContextClassLoader();

        // Initialise the attributes used to record rolling averages
        initStats();
    }


    /**
     * Returns the maximum number of objects that can be allocated by the pool
     * (checked out to clients, or idle awaiting checkout) at a given time. When
     * non-positive, there is no limit to the number of objects that can be
     * managed by the pool at one time.
     *
     * @return the cap on the total number of object instances managed by the
     *         pool.
     *
     * @see #setMaxTotal
     */
    public int getMaxTotal() {
        return maxTotal;
    }

    /**
     * Sets the cap on the number of objects that can be allocated by the pool
     * (checked out to clients, or idle awaiting checkout) at a given time. Use
     * a negative value for no limit.
     *
     * @param maxTotal  The cap on the total number of object instances managed
     *                  by the pool. Negative values mean that there is no limit
     *                  to the number of objects allocated by the pool.
     *
     * @see #getMaxTotal
     */
    public void setMaxTotal(int maxTotal) {
        this.maxTotal = maxTotal;
    }

    /**
     * Returns whether to block when the <code>borrowObject()</code> method is
     * invoked when the pool is exhausted (the maximum number of "active"
     * objects has been reached).
     *
     * @return <code>true</code> if <code>borrowObject()</code> should block
     *         when the pool is exhausted
     *
     * @see #setBlockWhenExhausted
     */
    public boolean getBlockWhenExhausted() {
        return blockWhenExhausted;
    }

    /**
     * Sets whether to block when the <code>borrowObject()</code> method is
     * invoked when the pool is exhausted (the maximum number of "active"
     * objects has been reached).
     *
     * @param blockWhenExhausted    <code>true</code> if
     *                              <code>borrowObject()</code> should block
     *                              when the pool is exhausted
     *
     * @see #getBlockWhenExhausted
     */
    public void setBlockWhenExhausted(boolean blockWhenExhausted) {
        this.blockWhenExhausted = blockWhenExhausted;
    }

    /**
     * Returns the maximum amount of time (in milliseconds) the
     * <code>borrowObject()</code> method should block before throwing an
     * exception when the pool is exhausted and
     * {@link #getBlockWhenExhausted} is true. When less than 0, the
     * <code>borrowObject()</code> method may block indefinitely.
     *
     * @return the maximum number of milliseconds <code>borrowObject()</code>
     *         will block.
     *
     * @see #setMaxWaitMillis
     * @see #setBlockWhenExhausted
     */
    public long getMaxWaitMillis() {
        return maxWaitMillis;
    }

    /**
     * Sets the maximum amount of time (in milliseconds) the
     * <code>borrowObject()</code> method should block before throwing an
     * exception when the pool is exhausted and
     * {@link #getBlockWhenExhausted} is true. When less than 0, the
     * <code>borrowObject()</code> method may block indefinitely.
     *
     * @param maxWaitMillis the maximum number of milliseconds
     *                      <code>borrowObject()</code> will block or negative
     *                      for indefinitely.
     *
     * @see #getMaxWaitMillis
     * @see #setBlockWhenExhausted
     */
    public void setMaxWaitMillis(long maxWaitMillis) {
        this.maxWaitMillis = maxWaitMillis;
    }

    /**
     * Returns whether the pool has LIFO (last in, first out) behaviour with
     * respect to idle objects - always returning the most recently used object
     * from the pool, or as a FIFO (first in, first out) queue, where the pool
     * always returns the oldest object in the idle object pool.
     *
     * @return <code>true</true> if the pool is configured with LIFO behaviour
     *         or <code>false</code> if the pool is configured with FIFO
     *         behaviour
     *
     * @see #setLifo
     */
    public boolean getLifo() {
        return lifo;
    }

    /**
     * Sets whether the pool has LIFO (last in, first out) behaviour with
     * respect to idle objects - always returning the most recently used object
     * from the pool, or as a FIFO (first in, first out) queue, where the pool
     * always returns the oldest object in the idle object pool.
     *
     * @param lifo  <code>true</true> if the pool is to be configured with LIFO
     *              behaviour or <code>false</code> if the pool is to be
     *              configured with FIFO behaviour
     *
     * @see #getLifo()
     */
    public void setLifo(boolean lifo) {
        this.lifo = lifo;
    }

    /**
     * Returns whether objects borrowed from the pool will be validated before
     * being returned from the <code>borrowObject()</code> method. Validation is
     * performed by the factory associated with the pool. If the object fails to
     * validate, it will be removed from the pool and destroyed, and a new
     * attempt will be made to borrow an object from the pool.
     *
     * @return <code>true</code> if objects are validated before being returned
     *         from the <code>borrowObject()</code> method
     *
     * @see #setTestOnBorrow
     */
    public boolean getTestOnBorrow() {
        return testOnBorrow;
    }

    /**
     * Sets whether objects borrowed from the pool will be validated before
     * being returned from the <code>borrowObject()</code> method. Validation is
     * performed by the factory associated with the pool. If the object fails to
     * validate, it will be removed from the pool and destroyed, and a new
     * attempt will be made to borrow an object from the pool.
     *
     * @param testOnBorrow  <code>true</code> if objects should be validated
     *                      before being returned from the
     *                      <code>borrowObject()</code> method
     *
     * @see #getTestOnBorrow
     */
    public void setTestOnBorrow(boolean testOnBorrow) {
        this.testOnBorrow = testOnBorrow;
    }

    /**
     * Returns whether objects borrowed from the pool will be validated when
     * they are returned to the pool via the <code>returnObject()</code> method.
     * Validation is performed by the factory associated with the pool. If the
     * object fails to it will be destroyed rather then returned the pool.
     *
     * @return <code>true</code> if objects are validated on being returned to
     *         the pool via the <code>returnObject()</code> method
     *
     * @see #setTestOnReturn
     */
    public boolean getTestOnReturn() {
        return testOnReturn;
    }

    /**
     * Sets whether objects borrowed from the pool will be validated when
     * they are returned to the pool via the <code>returnObject()</code> method.
     * Validation is performed by the factory associated with the pool. If the
     * object fails to it will be destroyed rather then returned the pool.
     *
     * @param testOnReturn <code>true</code> if objects are validated on being
     *                     returned to the pool via the
     *                     <code>returnObject()</code> method
     *
     * @see #getTestOnReturn
     */
    public void setTestOnReturn(boolean testOnReturn) {
        this.testOnReturn = testOnReturn;
    }

    /**
     * Returns whether objects sitting idle in the pool will be validated by the
     * idle object evictor (if any - see
     * {@link #setTimeBetweenEvictionRunsMillis(long)}). Validation is performed
     * by the factory associated with the pool. If the object fails to validate,
     * it will be removed from the pool and destroyed.
     *
     * @return <code>true</code> if objects will be validated by the evictor
     *
     * @see #setTestWhileIdle
     * @see #setTimeBetweenEvictionRunsMillis
     */
    public boolean getTestWhileIdle() {
        return testWhileIdle;
    }

    /**
     * Returns whether objects sitting idle in the pool will be validated by the
     * idle object evictor (if any - see
     * {@link #setTimeBetweenEvictionRunsMillis(long)}). Validation is performed
     * by the factory associated with the pool. If the object fails to validate,
     * it will be removed from the pool and destroyed.
     *
     * @param testWhileIdle
     *            <code>true</code> so objects will be validated by the evictor
     *
     * @see #getTestWhileIdle
     * @see #setTimeBetweenEvictionRunsMillis
     */
    public void setTestWhileIdle(boolean testWhileIdle) {
        this.testWhileIdle = testWhileIdle;
    }

    /**
     * Returns the number of milliseconds to sleep between runs of the idle
     * object evictor thread. When non-positive, no idle object evictor thread
     * will be run.
     *
     * @return number of milliseconds to sleep between evictor runs
     *
     * @see #setTimeBetweenEvictionRunsMillis
     */
    public long getTimeBetweenEvictionRunsMillis() {
        return timeBetweenEvictionRunsMillis;
    }

    /**
     * Sets the number of milliseconds to sleep between runs of the idle
     * object evictor thread. When non-positive, no idle object evictor thread
     * will be run.
     *
     * @param timeBetweenEvictionRunsMillis
     *            number of milliseconds to sleep between evictor runs
     *
     * @see #getTimeBetweenEvictionRunsMillis
     */
    public void setTimeBetweenEvictionRunsMillis(
            long timeBetweenEvictionRunsMillis) {
        this.timeBetweenEvictionRunsMillis = timeBetweenEvictionRunsMillis;
        startEvictor(timeBetweenEvictionRunsMillis);
    }

    /**
     * Returns the maximum number of objects to examine during each run (if any)
     * of the idle object evictor thread. When positive, the number of tests
     * performed for a run will be the minimum of the configured value and the
     * number of idle instances in the pool. When negative, the number of tests
     * performed will be <code>ceil({@link #getNumIdle}/
     * abs({@link #getNumTestsPerEvictionRun})) whch means that when the value
     * is <code>-n</code> roughly one nth of the idle objects will be tested per
     * run.
     *
     * @return max number of objects to examine during each evictor run
     *
     * @see #setNumTestsPerEvictionRun
     * @see #setTimeBetweenEvictionRunsMillis
     */
    public int getNumTestsPerEvictionRun() {
        return numTestsPerEvictionRun;
    }

    /**
     * Sets the maximum number of objects to examine during each run (if any)
     * of the idle object evictor thread. When positive, the number of tests
     * performed for a run will be the minimum of the configured value and the
     * number of idle instances in the pool. When negative, the number of tests
     * performed will be <code>ceil({@link #getNumIdle}/
     * abs({@link #getNumTestsPerEvictionRun})) whch means that when the value
     * is <code>-n</code> roughly one nth of the idle objects will be tested per
     * run.
     *
     * @param numTestsPerEvictionRun
     *            max number of objects to examine during each evictor run
     *
     * @see #getNumTestsPerEvictionRun
     * @see #setTimeBetweenEvictionRunsMillis
     */
    public void setNumTestsPerEvictionRun(int numTestsPerEvictionRun) {
        this.numTestsPerEvictionRun = numTestsPerEvictionRun;
    }

    /**
     * Returns the minimum amount of time an object may sit idle in the pool
     * before it is eligible for eviction by the idle object evictor (if any -
     * see {@link #setTimeBetweenEvictionRunsMillis(long)}). When non-positive,
     * no objects will be evicted from the pool due to idle time alone.
     *
     * @return minimum amount of time an object may sit idle in the pool before
     *         it is eligible for eviction
     *
     * @see #setMinEvictableIdleTimeMillis
     * @see #setTimeBetweenEvictionRunsMillis
     */
    public long getMinEvictableIdleTimeMillis() {
        return minEvictableIdleTimeMillis;
    }

    /**
     * Sets the minimum amount of time an object may sit idle in the pool
     * before it is eligible for eviction by the idle object evictor (if any -
     * see {@link #setTimeBetweenEvictionRunsMillis(long)}). When non-positive,
     * no objects will be evicted from the pool due to idle time alone.
     *
     * @param minEvictableIdleTimeMillis
     *            minimum amount of time an object may sit idle in the pool
     *            before it is eligible for eviction
     *
     * @see #getMinEvictableIdleTimeMillis
     * @see #setTimeBetweenEvictionRunsMillis
     */
    public void setMinEvictableIdleTimeMillis(long minEvictableIdleTimeMillis) {
        this.minEvictableIdleTimeMillis = minEvictableIdleTimeMillis;
    }

    /**
     * Returns the minimum amount of time an object may sit idle in the pool
     * before it is eligible for eviction by the idle object evictor (if any -
     * see {@link #setTimeBetweenEvictionRunsMillis(long)}),
     * with the extra condition that at least <code>minIdle</code> object
     * instances remain in the pool. This setting is overridden by
     * {@link #getMinEvictableIdleTimeMillis} (that is, if
     * {@link #getMinEvictableIdleTimeMillis} is positive, then
     * {@link #getSoftMinEvictableIdleTimeMillis} is ignored).
     *
     * @return minimum amount of time an object may sit idle in the pool before
     *         it is eligible for eviction if minIdle instances are available
     *
     * @see #setSoftMinEvictableIdleTimeMillis
     */
    public long getSoftMinEvictableIdleTimeMillis() {
        return softMinEvictableIdleTimeMillis;
    }

    /**
     * Sets the minimum amount of time an object may sit idle in the pool
     * before it is eligible for eviction by the idle object evictor (if any -
     * see {@link #setTimeBetweenEvictionRunsMillis(long)}),
     * with the extra condition that at least <code>minIdle</code> object
     * instances remain in the pool. This setting is overridden by
     * {@link #getMinEvictableIdleTimeMillis} (that is, if
     * {@link #getMinEvictableIdleTimeMillis} is positive, then
     * {@link #getSoftMinEvictableIdleTimeMillis} is ignored).
     *
     * @param softMinEvictableIdleTimeMillis
     *            minimum amount of time an object may sit idle in the pool
     *            before it is eligible for eviction if minIdle instances are
     *            available
     *
     * @see #getSoftMinEvictableIdleTimeMillis
     */
    public void setSoftMinEvictableIdleTimeMillis(
            long softMinEvictableIdleTimeMillis) {
        this.softMinEvictableIdleTimeMillis = softMinEvictableIdleTimeMillis;
    }

    /**
     * Returns the name of the {@link EvictionPolicy} implementation that is
     * used by this pool.
     *
     * @return  The fully qualified class name of the {@link EvictionPolicy}
     *
     * @see #setEvictionPolicyClassName(String)
     */
    public String getEvictionPolicyClassName() {
        return evictionPolicy.getClass().getName();
    }

    /**
     * Sets the name of the {@link EvictionPolicy} implementation that is
     * used by this pool.
     *
     * @param evictionPolicyClassName   the fully qualified class name of the
     *                                  new eviction policy
     *
     * @see #getEvictionPolicyClassName()
     */
    @SuppressWarnings("unchecked")
    public void setEvictionPolicyClassName(String evictionPolicyClassName) {
        try {
            Class<?> clazz = Class.forName(evictionPolicyClassName);
            Object policy = clazz.newInstance();
            if (policy instanceof EvictionPolicy<?>) {
                this.evictionPolicy = (EvictionPolicy<T>) policy;
            }
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException(
                    "Unable to create EvictionPolicy instance of type " +
                    evictionPolicyClassName, e);
        } catch (InstantiationException e) {
            throw new IllegalArgumentException(
                    "Unable to create EvictionPolicy instance of type " +
                    evictionPolicyClassName, e);
        } catch (IllegalAccessException e) {
            throw new IllegalArgumentException(
                    "Unable to create EvictionPolicy instance of type " +
                    evictionPolicyClassName, e);
        }
    }


    /**
     * Closes the pool, destroys the remaining idle objects and, if registered
     * in JMX, deregisters it.
     */
    public abstract void close();

    /**
     * Has this pool instance been closed.
     * @return <code>true</code> when this pool has been closed.
     */
    public final boolean isClosed() {
        return closed;
    }

    /**
     * <p>Perform <code>numTests</code> idle object eviction tests, evicting
     * examined objects that meet the criteria for eviction. If
     * <code>testWhileIdle</code> is true, examined objects are validated
     * when visited (and removed if invalid); otherwise only objects that
     * have been idle for more than <code>minEvicableIdleTimeMillis</code>
     * are removed.</p>
     *
     * @throws Exception when there is a problem evicting idle objects.
     */
    public abstract void evict() throws Exception;

    /*
     * Make the eviction policy instance available to the sub-classes
     */
    EvictionPolicy<T> getEvictionPolicy() {
        return evictionPolicy;
    }

    /**
     * Throws an <code>IllegalStateException</code> if called when the pool has
     * been closed.
     *
     * @throws IllegalStateException if this pool has been closed.
     * @see #isClosed()
     */
    final void assertOpen() throws IllegalStateException {
        if (isClosed()) {
            throw new IllegalStateException("Pool not open");
        }
    }

    /**
     * Start the eviction thread or service, or when <i>delay</i> is
     * non-positive, stop it if it is already running.
     *
     * @param delay
     *            milliseconds between evictor runs.
     */
    // Needs to be final; see POOL-195. Make method final as it is
    // called from a constructor.
    final void startEvictor(long delay) {
        synchronized (evictionLock) {
            if (null != evictor) {
                EvictionTimer.cancel(evictor);
                evictor = null;
                evictionIterator = null;
            }
            if (delay > 0) {
                evictor = new Evictor();
                EvictionTimer.schedule(evictor, delay, delay);
            }
        }
    }

    abstract void ensureMinIdle() throws Exception;


    // Monitoring (primarily JMX) related methods

    /**
     * Provides the name under which the pool has been registered with the
     * platform MBean server or <code>null</code> if the pool has not been
     * registered.
     */
    public abstract ObjectName getJmxName();

    /**
     * Provides the stack trace for the call that created this pool. JMX
     * registration may trigger a memory leak so it is important that pools are
     * deregistered when no longer used by calling the {@link #close()} method.
     * This method is provided to assist with identifying code that creates but
     * does not close it thereby creating a memory leak.
     */
    public String getCreationStackTrace() {
        return creationStackTrace;
    }

    /**
     * Lists the most recent exceptions that have been swallowed by the pool
     * implementation. Exceptions are typically swallowed when a problem occurs
     * while destroying an object.
     */
    public String[] getSwallowedExceptions() {
        List<String> temp =
                new ArrayList<String>(SWALLOWED_EXCEPTION_QUEUE_SIZE);
        synchronized (swallowedExceptions) {
            temp.addAll(swallowedExceptions);
        }
        return temp.toArray(new String[SWALLOWED_EXCEPTION_QUEUE_SIZE]);
    }

    /**
     * The total number of objects successfully borrowed from this pool over the
     * lifetime of the pool.
     */
    public long getBorrowedCount() {
        return borrowedCount.get();
    }

    /**
     * The total number of objects returned to this pool over the lifetime of
     * the pool. This excludes attempts to return the same object multiple
     * times.
     */
    public long getReturnedCount() {
        return returnedCount.get();
    }

    /**
     * The total number of objects created for this pool over the lifetime of
     * the pool.
     */
    public long getCreatedCount() {
        return createdCount.get();
    }

    /**
     * The total number of objects destroyed by this pool over the lifetime of
     * the pool.
     */
    public long getDestroyedCount() {
        return destroyedCount.get();
    }

    /**
     * The total number of objects destroyed by the evictor associated with this
     * pool over the lifetime of the pool.
     */
    public long getDestroyedByEvictorCount() {
        return destroyedByEvictorCount.get();
    }

    /**
     * The total number of objects destroyed by this pool as a result of failing
     * validation during <code>borrowObject()</code> over the lifetime of the
     * pool.
     */
    public long getDestroyedByBorrowValidationCount() {
        return destroyedByBorrowValidationCount.get();
    }

    /**
     * The mean time objects are active for based on the last {@link
     * #MEAN_TIMING_STATS_CACHE_SIZE} objects returned to the pool.
     */
    public long getMeanActiveTimeMillis() {
        return getMeanFromStatsCache(activeTimes);
    }

    /**
     * The mean time objects are idle for based on the last {@link
     * #MEAN_TIMING_STATS_CACHE_SIZE} objects borrowed from the pool.
     */
    public long getMeanIdleTimeMillis() {
        return getMeanFromStatsCache(idleTimes);
    }

    /**
     * The mean time threads wait to borrow an object based on the last {@link
     * #MEAN_TIMING_STATS_CACHE_SIZE} objects borrowed from the pool.
     */
    public long getMeanBorrowWaitTimeMillis() {
        return getMeanFromStatsCache(waitTimes);
    }

    /**
     * The maximum time a thread has waited to borrow objects from the pool.
     */
    public long getMaxBorrowWaitTimeMillis() {
        return maxBorrowWaitTimeMillis;
    }

    final NotificationBroadcasterSupport getJmxNotificationSupport() {
        return jmxNotificationSupport;
    }

    void swallowException(Exception e) {
        String msg = getStackTrace(e);

        ObjectName oname = getJmxName();
        if (oname != null) {
            Notification n = new Notification(NOTIFICATION_SWALLOWED_EXCEPTION,
                    oname, swallowedExcpetionCount.incrementAndGet(), msg);
            getJmxNotificationSupport().sendNotification(n);
        }

        // Add the exception the queue, removing the oldest
        synchronized (swallowedExceptions) {
            swallowedExceptions.addLast(msg);
            swallowedExceptions.pollFirst();
        }
    }

    void updateStatsBorrow(PooledObject<T> p, long waitTime) {
        borrowedCount.incrementAndGet();
        synchronized (idleTimes) {
            idleTimes.add(Long.valueOf(p.getIdleTimeMillis()));
            idleTimes.poll();
        }
        synchronized (waitTimes) {
            waitTimes.add(Long.valueOf(waitTime));
            waitTimes.poll();
        }
        synchronized (maxBorrowWaitTimeMillisLock) {
            if (waitTime > maxBorrowWaitTimeMillis) {
                maxBorrowWaitTimeMillis = waitTime;
            }
        }
    }

    void updateStatsReturn(long activeTime) {
        returnedCount.incrementAndGet();
        synchronized (activeTimes) {
            activeTimes.add(Long.valueOf(activeTime));
            activeTimes.poll();
        }
    }

    private String getStackTrace(Exception e) {
        // Need the exception in string form to prevent the retention of
        // references to classes in the stack trace that could trigger a memory
        // leak in a container environment
        Writer w = new StringWriter();
        PrintWriter pw = new PrintWriter(w);
        e.printStackTrace(pw);
        return w.toString();
    }

    private long getMeanFromStatsCache(LinkedList<Long> cache) {
        List<Long> times = new ArrayList<Long>(MEAN_TIMING_STATS_CACHE_SIZE);
        synchronized (cache) {
            times.addAll(cache);
        }
        double result = 0;
        int counter = 0;
        Iterator<Long> iter = times.iterator();
        while (iter.hasNext()) {
            Long time = iter.next();
            if (time != null) {
                counter++;
                result = result * ((counter - 1) / (double) counter) +
                        time.longValue()/(double) counter;
            }
        }
        return (long) result;
    }

    private void initStats() {
        for (int i = 0; i < MEAN_TIMING_STATS_CACHE_SIZE; i++) {
            activeTimes.add(null);
            idleTimes.add(null);
            waitTimes.add(null);
        }
    }


    // Implement NotificationEmitter interface

    @Override
    public final void addNotificationListener(NotificationListener listener,
            NotificationFilter filter, Object handback)
            throws IllegalArgumentException {

        if (jmxNotificationSupport == null) {
            throw new UnsupportedOperationException("JMX is not enabled");
        }
        jmxNotificationSupport.addNotificationListener(
                listener, filter, handback);
    }

    @Override
    public final void removeNotificationListener(NotificationListener listener)
            throws ListenerNotFoundException {

        if (jmxNotificationSupport == null) {
            throw new UnsupportedOperationException("JMX is not enabled");
        }
        jmxNotificationSupport.removeNotificationListener(listener);
    }

    @Override
    public final MBeanNotificationInfo[] getNotificationInfo() {

        if (jmxNotificationSupport == null) {
            throw new UnsupportedOperationException("JMX is not enabled");
        }
        return jmxNotificationSupport.getNotificationInfo();
    }

    @Override
    public final void removeNotificationListener(NotificationListener listener,
            NotificationFilter filter, Object handback)
            throws ListenerNotFoundException {

        if (jmxNotificationSupport == null) {
            throw new UnsupportedOperationException("JMX is not enabled");
        }
        jmxNotificationSupport.removeNotificationListener(
                listener, filter, handback);
    }


    // Inner classes

    /**
     * The idle object evictor {@link TimerTask}.
     *
     * @see GenericKeyedObjectPool#setTimeBetweenEvictionRunsMillis
     */
    class Evictor extends TimerTask {
        /**
         * Run pool maintenance.  Evict objects qualifying for eviction and then
         * ensure that the minimum number of idle instances are available.
         * Since the Timer that invokes Evictors is shared for all Pools but
         * pools may exist in different class loaders, the Evictor ensures that
         * any actions taken are under the class loader of the factory
         * associated with the pool.
         */
        @Override
        public void run() {
            ClassLoader savedClassLoader =
                    Thread.currentThread().getContextClassLoader();
            try {
                // Set the class loader for the factory
                Thread.currentThread().setContextClassLoader(
                        factoryClassLoader);

                // Evict from the pool
                try {
                    evict();
                } catch(Exception e) {
                    // Ignored
                } catch(OutOfMemoryError oome) {
                    // Log problem but give evictor thread a chance to continue
                    // in case error is recoverable
                    oome.printStackTrace(System.err);
                }
                // Re-create idle instances.
                try {
                    ensureMinIdle();
                } catch (Exception e) {
                    // Ignored
                }
            } finally {
                // Restore the previous CCL
                Thread.currentThread().setContextClassLoader(savedClassLoader);
            }
        }
    }
}
