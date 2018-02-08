/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/

package org.apache.kylin.common.metrics.metrics2;

import java.io.Closeable;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.commons.lang3.ClassUtils;
import org.apache.kylin.common.KylinConfig;
import org.apache.kylin.common.metrics.common.Metrics;
import org.apache.kylin.common.metrics.common.MetricsConstant;
import org.apache.kylin.common.metrics.common.MetricsScope;
import org.apache.kylin.common.metrics.common.MetricsVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codahale.metrics.Counter;
import com.codahale.metrics.ExponentiallyDecayingReservoir;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.Meter;
import com.codahale.metrics.Metric;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.MetricSet;
import com.codahale.metrics.Timer;
import com.codahale.metrics.json.MetricsModule;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.Lists;

/**
 * Codahale-backed Metrics implementation.
 */
public class CodahaleMetrics implements Metrics {

    public static final Logger LOGGER = LoggerFactory.getLogger(CodahaleMetrics.class);

    public final MetricRegistry metricRegistry = new MetricRegistry();
    private final Lock timersLock = new ReentrantLock();
    private final Lock countersLock = new ReentrantLock();
    private final Lock gaugesLock = new ReentrantLock();
    private final Lock metersLock = new ReentrantLock();
    private final Lock histogramLock = new ReentrantLock();
    private final Set<Closeable> reporters = new HashSet<Closeable>();
    private final ThreadLocal<HashMap<String, CodahaleMetricsScope>> threadLocalScopes = new ThreadLocal<HashMap<String, CodahaleMetricsScope>>() {
        @Override
        protected HashMap<String, CodahaleMetricsScope> initialValue() {
            return new HashMap<String, CodahaleMetricsScope>();
        }
    };
    private LoadingCache<String, Timer> timers;
    private LoadingCache<String, Counter> counters;
    private LoadingCache<String, Meter> meters;
    private LoadingCache<String, Histogram> histograms;
    private ConcurrentHashMap<String, Gauge> gauges;
    private KylinConfig conf;

    public CodahaleMetrics() {
        this.conf = KylinConfig.getInstanceFromEnv();
        //Codahale artifacts are lazily-created.
        timers = CacheBuilder.newBuilder().build(new CacheLoader<String, Timer>() {
            @Override
            public Timer load(String key) {
                Timer timer = new Timer(new ExponentiallyDecayingReservoir());
                metricRegistry.register(key, timer);
                return timer;
            }
        });
        counters = CacheBuilder.newBuilder().build(new CacheLoader<String, Counter>() {
            @Override
            public Counter load(String key) {
                Counter counter = new Counter();
                metricRegistry.register(key, counter);
                return counter;
            }
        });
        meters = CacheBuilder.newBuilder().build(new CacheLoader<String, Meter>() {
            @Override
            public Meter load(String key) {
                Meter meter = new Meter();
                metricRegistry.register(key, meter);
                return meter;
            }
        });
        histograms = CacheBuilder.newBuilder().build(new CacheLoader<String, Histogram>() {
            @Override
            public Histogram load(String key) {
                Histogram histogram = new Histogram(new ExponentiallyDecayingReservoir());
                metricRegistry.register(key, histogram);
                return histogram;
            }
        });
        gauges = new ConcurrentHashMap<String, Gauge>();
        //register JVM metrics
        //        registerAll("gc", new GarbageCollectorMetricSet());
        //        registerAll("buffers", new BufferPoolMetricSet(ManagementFactory.getPlatformMBeanServer()));
        //        registerAll("memory", new MemoryUsageGaugeSet());
        //        registerAll("threads", new ThreadStatesGaugeSet());
        //        registerAll("classLoadingz", new ClassLoadingGaugeSet());

        //initialize reporters
        initReporting();
    }

    @Override
    public void close() throws Exception {
        if (reporters != null) {
            for (Closeable reporter : reporters) {
                reporter.close();
            }
        }
        for (Map.Entry<String, Metric> metric : metricRegistry.getMetrics().entrySet()) {
            metricRegistry.remove(metric.getKey());
        }

        try {
            timersLock.lock();
            timers.invalidateAll();
        } finally {
            timersLock.unlock();
        }

        try {
            countersLock.lock();
            counters.invalidateAll();
        } finally {
            countersLock.unlock();
        }

        try {
            metersLock.lock();
            meters.invalidateAll();
        } finally {
            metersLock.unlock();
        }
    }

    @Override
    public void startStoredScope(String name) {
        if (threadLocalScopes.get().containsKey(name)) {
            threadLocalScopes.get().get(name).open();
        } else {
            threadLocalScopes.get().put(name, new CodahaleMetricsScope(name));
        }
    }

    public MetricsScope getStoredScope(String name) throws IllegalArgumentException {
        if (threadLocalScopes.get().containsKey(name)) {
            return threadLocalScopes.get().get(name);
        } else {
            throw new IllegalArgumentException("No metrics scope named " + name);
        }
    }

    @Override
    public void endStoredScope(String name) {
        if (threadLocalScopes.get().containsKey(name)) {
            threadLocalScopes.get().get(name).close();
            threadLocalScopes.get().remove(name);
        }
    }

    public MetricsScope createScope(String name) {
        return new CodahaleMetricsScope(name);
    }

    public void endScope(MetricsScope scope) {
        ((CodahaleMetricsScope) scope).close();
    }

    @Override
    public Long incrementCounter(String name) {
        return incrementCounter(name, 1L);
    }

    @Override
    public Long incrementCounter(String name, long increment) {
        String key = name;
        try {
            countersLock.lock();
            counters.get(key).inc(increment);
            return counters.get(key).getCount();
        } catch (ExecutionException ee) {
            throw new IllegalStateException("Error retrieving counter from the metric registry ", ee);
        } finally {
            countersLock.unlock();
        }
    }

    @Override
    public Long decrementCounter(String name) {
        return decrementCounter(name, 1L);
    }

    @Override
    public Long decrementCounter(String name, long decrement) {
        String key = name;
        try {
            countersLock.lock();
            counters.get(key).dec(decrement);
            return counters.get(key).getCount();
        } catch (ExecutionException ee) {
            throw new IllegalStateException("Error retrieving counter from the metric registry ", ee);
        } finally {
            countersLock.unlock();
        }
    }

    @Override
    public void addGauge(String name, final MetricsVariable variable) {
        Gauge gauge = new Gauge() {
            @Override
            public Object getValue() {
                return variable.getValue();
            }
        };
        addGaugeInternal(name, gauge);
    }

    @Override
    public void addRatio(String name, MetricsVariable<Integer> numerator, MetricsVariable<Integer> denominator) {
        Preconditions.checkArgument(numerator != null, "Numerator must not be null");
        Preconditions.checkArgument(denominator != null, "Denominator must not be null");

        MetricVariableRatioGauge gauge = new MetricVariableRatioGauge(numerator, denominator);
        addGaugeInternal(name, gauge);
    }

    private void addGaugeInternal(String name, Gauge gauge) {
        try {
            gaugesLock.lock();
            gauges.put(name, gauge);
            // Metrics throws an Exception if we don't do this when the key already exists
            if (metricRegistry.getGauges().containsKey(name)) {
                LOGGER.warn("A Gauge with name [" + name + "] already exists. "
                        + " The old gauge will be overwritten, but this is not recommended");
                metricRegistry.remove(name);
            }
            metricRegistry.register(name, gauge);
        } finally {
            gaugesLock.unlock();
        }
    }

    @Override
    public void markMeter(String name) {
        String key = name;
        try {
            metersLock.lock();
            Meter meter = meters.get(name);
            meter.mark();
        } catch (ExecutionException e) {
            throw new IllegalStateException("Error retrieving meter " + name + " from the metric registry ", e);
        } finally {
            metersLock.unlock();
        }
    }

    @Override
    public void updateHistogram(String name, long count) {
        try {
            histogramLock.lock();
            Histogram histogram = histograms.get(name);
            histogram.update(count);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Error retrieving meter " + name + " from the metric registry ", e);
        } finally {
            histogramLock.unlock();
        }
    }

    @Override
    public void updateTimer(String name, long duration, TimeUnit unit) {
        String key = name;
        try {
            timersLock.lock();
            Timer timer = timers.get(key);
            timer.update(duration, unit);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Error retrieving timer " + name + " from the metric registry ", e);
        } finally {
            timersLock.unlock();
        }
    }

    // This method is necessary to synchronize lazy-creation to the timers.
    public Timer getTimer(String name) {
        try {
            timersLock.lock();
            return timers.get(name);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Error retrieving timer " + name + " from the metric registry ", e);
        } finally {
            timersLock.unlock();
        }
    }

    public Counter getCounter(String name) {
        try {
            countersLock.lock();
            return counters.get(name);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Error retrieving counter " + name + " from the metric registry ", e);
        } finally {
            countersLock.unlock();
        }
    }

    public Histogram getHistogram(String name) {
        try {
            histogramLock.lock();
            return histograms.get(name);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Error retrieving histogram " + name + " from the metric registry ", e);
        } finally {
            histogramLock.unlock();
        }
    }

    public Meter getMeter(String name) {
        try {
            metersLock.lock();
            return meters.get(name);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Error retrieving meter " + name + " from the metric registry ", e);
        } finally {
            metersLock.unlock();
        }
    }

    private void registerAll(String prefix, MetricSet metricSet) {
        for (Map.Entry<String, Metric> entry : metricSet.getMetrics().entrySet()) {
            if (entry.getValue() instanceof MetricSet) {
                registerAll(prefix + "." + entry.getKey(), (MetricSet) entry.getValue());
            } else {
                metricRegistry.register(prefix + "." + entry.getKey(), entry.getValue());
            }
        }
    }

    @VisibleForTesting
    public MetricRegistry getMetricRegistry() {
        return metricRegistry;
    }

    @VisibleForTesting
    public String dumpJson() throws Exception {
        ObjectMapper jsonMapper = new ObjectMapper()
                .registerModule(new MetricsModule(TimeUnit.MILLISECONDS, TimeUnit.MILLISECONDS, false));
        return jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(metricRegistry);
    }

    /**
     * Initializes reporters from kylin.metric.codahale-metric-report-classes  if the former is not defined.
     * Note: if both confs are defined, only  kylin.metric.codahale-metric-report-classes will be used.
     */
    private void initReporting() {

        if (!(initCodahaleMetricsReporterClasses() || initMetricsReporter())) {
            LOGGER.warn("Unable to initialize metrics reporting");
        }
        if (reporters.isEmpty()) {
            // log a warning incase no reporters were successfully added
            LOGGER.warn("No reporters configured for codahale metrics!");
        }
    }

    /**
     * Initializes reporting using kylin.metric.codahale-metric-report-classes.
     *
     * @return whether initialization was successful or not
     */
    private boolean initCodahaleMetricsReporterClasses() {

        List<String> reporterClasses = Lists.newArrayList(Splitter.on(",").trimResults().omitEmptyStrings()
                .split(KylinConfig.getInstanceFromEnv().getCoadhaleMetricsReportClassesNames()));
        if (reporterClasses.isEmpty()) {
            return false;
        }

        for (String reporterClass : reporterClasses) {
            Class name = null;
            try {
                name = ClassUtils.getClass(reporterClass);
            } catch (ClassNotFoundException e) {
                LOGGER.error("Unable to instantiate metrics reporter class " + reporterClass
                        + " from conf kylin.metric.codahale-metric-report-classes", e);
                throw new IllegalArgumentException(e);
            }
            try {
                Constructor constructor = name.getConstructor(MetricRegistry.class, KylinConfig.class);
                CodahaleReporter reporter = (CodahaleReporter) constructor.newInstance(metricRegistry, conf);
                reporter.start();
                reporters.add(reporter);
            } catch (NoSuchMethodException | InstantiationException | IllegalAccessException
                    | InvocationTargetException e) {
                LOGGER.error("Unable to instantiate using constructor(MetricRegistry, KylinConfig) for" + " reporter "
                        + reporterClass + " from conf kylin.metric.codahale-metric-report-classes", e);
                throw new IllegalArgumentException(e);
            }
        }
        return true;
    }

    /**
     * Initializes reporting using KYLIN_METRICS+REPORTER.
     *
     * @return whether initialization was successful or not
     */
    private boolean initMetricsReporter() {

        List<String> metricsReporterNames = Lists.newArrayList(Splitter.on(",").trimResults().omitEmptyStrings()
                .split(KylinConfig.getInstanceFromEnv().getCoadhaleMetricsReportClassesNames()));
        if (metricsReporterNames.isEmpty()) {
            return false;
        }

        MetricsReporting reporter = null;
        for (String metricsReportingName : metricsReporterNames) {
            try {
                reporter = MetricsReporting.valueOf(metricsReportingName.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                LOGGER.error("Invalid reporter name " + metricsReportingName, e);
                throw e;
            }
            CodahaleReporter codahaleReporter = null;
            switch (reporter) {
            case CONSOLE:
                codahaleReporter = new ConsoleMetricsReporter(metricRegistry, conf);
                break;
            case JMX:
                codahaleReporter = new JmxMetricsReporter(metricRegistry, conf);
                break;
            case JSON_FILE:
                codahaleReporter = new JsonFileMetricsReporter(metricRegistry, conf);
                break;
            case HADOOP2:
                codahaleReporter = new Metrics2Reporter(metricRegistry, conf);
                break;
            default:
                LOGGER.warn("Unhandled reporter " + reporter + " provided.");
            }
            if (codahaleReporter != null) {
                codahaleReporter.start();
                reporters.add(codahaleReporter);
            }
        }
        return true;
    }

    public class CodahaleMetricsScope implements MetricsScope {

        private final String name;
        private final Timer timer;
        private Timer.Context timerContext;

        private boolean isOpen = false;

        /**
         * Instantiates a named scope - intended to only be called by Metrics, so locally scoped.
         *
         * @param name - name of the variable
         */
        private CodahaleMetricsScope(String name) {
            this.name = name;
            this.timer = CodahaleMetrics.this.getTimer(MetricsConstant.CALLS + name);
            open();
        }

        /**
         * Opens scope, and makes note of the time started, increments run counter
         */
        public void open() {
            if (!isOpen) {
                isOpen = true;
                this.timerContext = timer.time();
                CodahaleMetrics.this.incrementCounter(MetricsConstant.ACTIVE_CALLS + name);
            } else {
                LOGGER.warn("Scope named " + name + " is not closed, cannot be opened.");
            }
        }

        /**
         * Closes scope, and records the time taken
         */
        public void close() {
            if (isOpen) {
                timerContext.close();
                CodahaleMetrics.this.decrementCounter(MetricsConstant.ACTIVE_CALLS + name);
            } else {
                LOGGER.warn("Scope named " + name + " is not open, cannot be closed.");
            }
            isOpen = false;
        }
    }
}