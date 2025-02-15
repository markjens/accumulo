/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.accumulo.tserver.compactions;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.apache.accumulo.core.conf.AccumuloConfiguration;
import org.apache.accumulo.core.conf.ConfigurationTypeHelper;
import org.apache.accumulo.core.conf.Property;
import org.apache.accumulo.core.dataImpl.KeyExtent;
import org.apache.accumulo.core.metadata.schema.ExternalCompactionId;
import org.apache.accumulo.core.spi.compaction.CompactionExecutorId;
import org.apache.accumulo.core.spi.compaction.CompactionKind;
import org.apache.accumulo.core.spi.compaction.CompactionServiceId;
import org.apache.accumulo.core.spi.compaction.CompactionServices;
import org.apache.accumulo.core.spi.compaction.DefaultCompactionPlanner;
import org.apache.accumulo.core.tabletserver.thrift.TCompactionQueueSummary;
import org.apache.accumulo.core.util.compaction.CompactionExecutorIdImpl;
import org.apache.accumulo.core.util.threads.Threads;
import org.apache.accumulo.fate.util.Retry;
import org.apache.accumulo.server.ServerContext;
import org.apache.accumulo.tserver.compactions.CompactionExecutor.CType;
import org.apache.accumulo.tserver.metrics.CompactionExecutorsMetrics;
import org.apache.accumulo.tserver.tablet.Tablet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;
import com.google.common.collect.Sets;

public class CompactionManager {

  private static final Logger log = LoggerFactory.getLogger(CompactionManager.class);

  private Iterable<Compactable> compactables;
  private volatile Map<CompactionServiceId,CompactionService> services;

  private LinkedBlockingQueue<Compactable> compactablesToCheck = new LinkedBlockingQueue<>();

  private long maxTimeBetweenChecks;

  private ServerContext context;

  private Config currentCfg;

  private long lastConfigCheckTime = System.nanoTime();

  private CompactionExecutorsMetrics ceMetrics;

  public static final CompactionServiceId DEFAULT_SERVICE = CompactionServiceId.of("default");

  private String lastDeprecationWarning = "";

  private Map<CompactionExecutorId,ExternalCompactionExecutor> externalExecutors;

  private Map<ExternalCompactionId,ExtCompInfo> runningExternalCompactions;

  static class ExtCompInfo {
    final KeyExtent extent;
    final CompactionExecutorId executor;

    public ExtCompInfo(KeyExtent extent, CompactionExecutorId executor) {
      this.extent = extent;
      this.executor = executor;
    }
  }

  private class Config {
    Map<String,String> planners = new HashMap<>();
    Map<String,Long> rateLimits = new HashMap<>();
    Map<String,Map<String,String>> options = new HashMap<>();
    long defaultRateLimit = 0;

    @SuppressWarnings("removal")
    private long getDefaultThroughput(AccumuloConfiguration aconf) {
      if (aconf.isPropertySet(Property.TSERV_MAJC_THROUGHPUT, true)) {
        return aconf.getAsBytes(Property.TSERV_MAJC_THROUGHPUT);
      }

      return ConfigurationTypeHelper
          .getMemoryAsBytes(Property.TSERV_COMPACTION_SERVICE_DEFAULT_RATE_LIMIT.getDefaultValue());
    }

    @SuppressWarnings("removal")
    private Map<String,String> getConfiguration(AccumuloConfiguration aconf) {

      Map<String,String> configs =
          aconf.getAllPropertiesWithPrefix(Property.TSERV_COMPACTION_SERVICE_PREFIX);

      // check if deprecated properties for compaction executor are set
      if (aconf.isPropertySet(Property.TSERV_MAJC_MAXCONCURRENT, true)) {

        String defaultServicePrefix =
            Property.TSERV_COMPACTION_SERVICE_PREFIX.getKey() + DEFAULT_SERVICE.canonical() + ".";

        // check if any properties for the default compaction service are set
        boolean defaultServicePropsSet = configs.keySet().stream()
            .filter(key -> key.startsWith(defaultServicePrefix)).map(Property::getPropertyByKey)
            .anyMatch(prop -> prop == null || aconf.isPropertySet(prop, true));

        if (defaultServicePropsSet) {

          String warning = String.format(
              "The deprecated property %s was set. Properties with the prefix %s "
                  + "were also set, which replace the deprecated properties. The deprecated "
                  + "property was therefore ignored.",
              Property.TSERV_MAJC_MAXCONCURRENT.getKey(), defaultServicePrefix);

          if (!warning.equals(lastDeprecationWarning)) {
            log.warn(warning);
            lastDeprecationWarning = warning;
          }
        } else {
          String numThreads = aconf.get(Property.TSERV_MAJC_MAXCONCURRENT);

          // Its possible a user has configured the others compaction services, but not the default
          // service. In this case want to produce a config with the default service configs
          // overridden using deprecated configs.

          HashMap<String,String> configsCopy = new HashMap<>(configs);

          Map<String,String> defaultServiceConfigs =
              Map.of(defaultServicePrefix + "planner", DefaultCompactionPlanner.class.getName(),
                  defaultServicePrefix + "planner.opts.executors",
                  "[{'name':'deprecated', 'numThreads':" + numThreads + "}]");

          configsCopy.putAll(defaultServiceConfigs);

          String warning = String.format(
              "The deprecated property %s was set. Properties with the prefix %s "
                  + "were not set, these should replace the deprecated properties. The old "
                  + "properties were automatically mapped to the new properties in process "
                  + "creating : %s.",
              Property.TSERV_MAJC_MAXCONCURRENT.getKey(), defaultServicePrefix,
              defaultServiceConfigs);

          if (!warning.equals(lastDeprecationWarning)) {
            log.warn(warning);
            lastDeprecationWarning = warning;
          }

          configs = Map.copyOf(configsCopy);
        }
      }

      return configs;

    }

    Config(AccumuloConfiguration aconf) {
      Map<String,String> configs = getConfiguration(aconf);

      configs.forEach((prop, val) -> {

        var suffix = prop.substring(Property.TSERV_COMPACTION_SERVICE_PREFIX.getKey().length());
        String[] tokens = suffix.split("\\.");
        if (tokens.length == 4 && tokens[1].equals("planner") && tokens[2].equals("opts")) {
          options.computeIfAbsent(tokens[0], k -> new HashMap<>()).put(tokens[3], val);
        } else if (tokens.length == 2 && tokens[1].equals("planner")) {
          planners.put(tokens[0], val);
        } else if (tokens.length == 3 && tokens[1].equals("rate") && tokens[2].equals("limit")) {
          var eprop = Property.getPropertyByKey(prop);
          if (eprop == null || aconf.isPropertySet(eprop, true)
              || !isDeprecatedThroughputSet(aconf)) {
            rateLimits.put(tokens[0], ConfigurationTypeHelper.getFixedMemoryAsBytes(val));
          }
        } else {
          throw new IllegalArgumentException("Malformed compaction service property " + prop);
        }
      });

      defaultRateLimit = getDefaultThroughput(aconf);

      var diff = Sets.difference(options.keySet(), planners.keySet());

      if (!diff.isEmpty()) {
        throw new IllegalArgumentException(
            "Incomplete compaction service definitions, missing planner class " + diff);
      }

    }

    @SuppressWarnings("removal")
    private boolean isDeprecatedThroughputSet(AccumuloConfiguration aconf) {
      return aconf.isPropertySet(Property.TSERV_MAJC_THROUGHPUT, true);
    }

    public long getRateLimit(String serviceName) {
      return rateLimits.getOrDefault(serviceName, defaultRateLimit);
    }

    @Override
    public boolean equals(Object o) {
      if (o instanceof Config) {
        var oc = (Config) o;
        return planners.equals(oc.planners) && options.equals(oc.options)
            && rateLimits.equals(oc.rateLimits);
      }

      return false;
    }

    @Override
    public int hashCode() {
      return Objects.hash(planners, options, rateLimits);
    }
  }

  private void mainLoop() {
    long lastCheckAllTime = System.nanoTime();

    long increment = Math.max(1, maxTimeBetweenChecks / 10);

    var retryFactory = Retry.builder().infiniteRetries()
        .retryAfter(increment, TimeUnit.MILLISECONDS).incrementBy(increment, TimeUnit.MILLISECONDS)
        .maxWait(maxTimeBetweenChecks, TimeUnit.MILLISECONDS).backOffFactor(1.07)
        .logInterval(1, TimeUnit.MINUTES).createFactory();
    var retry = retryFactory.createRetry();
    Compactable last = null;

    while (true) {
      try {
        long passed = TimeUnit.MILLISECONDS.convert(System.nanoTime() - lastCheckAllTime,
            TimeUnit.NANOSECONDS);
        if (passed >= maxTimeBetweenChecks) {
          // take a snapshot of what is currently running
          HashSet<ExternalCompactionId> runningEcids =
              new HashSet<>(runningExternalCompactions.keySet());
          for (Compactable compactable : compactables) {
            last = compactable;
            submitCompaction(compactable);
            // remove anything from snapshot that tablets know are running
            compactable.getExternalCompactionIds(runningEcids::remove);
          }
          lastCheckAllTime = System.nanoTime();
          // anything left in the snapshot is unknown to any tablet and should be removed if it
          // still exists
          runningExternalCompactions.keySet().removeAll(runningEcids);
        } else {
          var compactable =
              compactablesToCheck.poll(maxTimeBetweenChecks - passed, TimeUnit.MILLISECONDS);
          if (compactable != null) {
            last = compactable;
            submitCompaction(compactable);
          }
        }

        last = null;
        if (retry.hasRetried())
          retry = retryFactory.createRetry();

        checkForConfigChanges(false);

      } catch (Exception e) {
        var extent = last == null ? null : last.getExtent();
        log.warn("Failed to compact {} ", extent, e);
        retry.useRetry();
        try {
          retry.waitForNextAttempt();
        } catch (InterruptedException e1) {
          log.debug("Retry interrupted", e1);
        }
      }
    }
  }

  /**
   * Get each configured service for the compactable tablet and submit for compaction
   */
  private void submitCompaction(Compactable compactable) {
    for (CompactionKind ctype : CompactionKind.values()) {
      var csid = compactable.getConfiguredService(ctype);
      var service = services.get(csid);
      if (service == null) {
        checkForConfigChanges(true);
        service = services.get(csid);
        if (service == null) {
          log.error(
              "Tablet {} returned non-existent compaction service {} for compaction type {}.  Check"
                  + " the table compaction dispatcher configuration. Attempting to fall back to "
                  + "{} service.",
              compactable.getExtent(), csid, ctype, DEFAULT_SERVICE);
          service = services.get(DEFAULT_SERVICE);
        }
      }

      if (service != null) {
        service.submitCompaction(ctype, compactable, compactablesToCheck::add);
      }
    }
  }

  public CompactionManager(Iterable<Compactable> compactables, ServerContext context,
      CompactionExecutorsMetrics ceMetrics) {
    this.compactables = compactables;

    this.currentCfg = new Config(context.getConfiguration());

    this.context = context;

    this.ceMetrics = ceMetrics;

    this.externalExecutors = new ConcurrentHashMap<>();

    this.runningExternalCompactions = new ConcurrentHashMap<>();

    Map<CompactionServiceId,CompactionService> tmpServices = new HashMap<>();

    currentCfg.planners.forEach((serviceName, plannerClassName) -> {
      try {
        tmpServices.put(CompactionServiceId.of(serviceName),
            new CompactionService(serviceName, plannerClassName,
                currentCfg.getRateLimit(serviceName),
                currentCfg.options.getOrDefault(serviceName, Map.of()), context, ceMetrics,
                this::getExternalExecutor));
      } catch (RuntimeException e) {
        log.error("Failed to create compaction service {} with planner:{} options:{}", serviceName,
            plannerClassName, currentCfg.options.getOrDefault(serviceName, Map.of()), e);
      }
    });

    this.services = Map.copyOf(tmpServices);

    this.maxTimeBetweenChecks =
        context.getConfiguration().getTimeInMillis(Property.TSERV_MAJC_DELAY);

    ceMetrics.setExternalMetricsSupplier(this::getExternalMetrics);
  }

  public void compactableChanged(Compactable compactable) {
    compactablesToCheck.add(compactable);
  }

  private synchronized void checkForConfigChanges(boolean force) {
    try {
      final long secondsSinceLastCheck =
          TimeUnit.SECONDS.convert(System.nanoTime() - lastConfigCheckTime, TimeUnit.NANOSECONDS);
      if (!force && (secondsSinceLastCheck < 1)) {
        return;
      }

      lastConfigCheckTime = System.nanoTime();

      var tmpCfg = new Config(context.getConfiguration());

      if (!currentCfg.equals(tmpCfg)) {
        Map<CompactionServiceId,CompactionService> tmpServices = new HashMap<>();

        tmpCfg.planners.forEach((serviceName, plannerClassName) -> {

          try {
            var csid = CompactionServiceId.of(serviceName);
            var service = services.get(csid);
            if (service == null) {
              tmpServices.put(csid,
                  new CompactionService(serviceName, plannerClassName,
                      tmpCfg.getRateLimit(serviceName),
                      tmpCfg.options.getOrDefault(serviceName, Map.of()), context, ceMetrics,
                      this::getExternalExecutor));
            } else {
              service.configurationChanged(plannerClassName, tmpCfg.getRateLimit(serviceName),
                  tmpCfg.options.getOrDefault(serviceName, Map.of()));
              tmpServices.put(csid, service);
            }
          } catch (RuntimeException e) {
            throw new RuntimeException("Failed to create or update compaction service "
                + serviceName + " with planner:" + plannerClassName + " options:"
                + tmpCfg.options.getOrDefault(serviceName, Map.of()), e);
          }
        });

        var deletedServices =
            Sets.difference(currentCfg.planners.keySet(), tmpCfg.planners.keySet());

        for (String serviceName : deletedServices) {
          services.get(CompactionServiceId.of(serviceName)).stop();
        }

        this.services = Map.copyOf(tmpServices);

        HashSet<CompactionExecutorId> activeExternalExecs = new HashSet<>();
        services.values().forEach(cs -> cs.getExternalExecutorsInUse(activeExternalExecs::add));
        // clean up an external compactors that are no longer in use by any compaction service
        externalExecutors.keySet().retainAll(activeExternalExecs);

      }
    } catch (RuntimeException e) {
      log.error("Failed to reconfigure compaction services ", e);
    }
  }

  public void start() {
    log.debug("Started compaction manager");
    Threads.createThread("Compaction Manager", () -> mainLoop()).start();
  }

  public CompactionServices getServices() {
    var serviceIds = services.keySet();

    return new CompactionServices() {
      @Override
      public Set<CompactionServiceId> getIds() {
        return serviceIds;
      }
    };
  }

  public boolean isCompactionQueued(KeyExtent extent, Set<CompactionServiceId> servicesUsed) {
    return servicesUsed.stream().map(services::get).filter(Objects::nonNull)
        .anyMatch(compactionService -> compactionService.isCompactionQueued(extent));
  }

  public int getCompactionsRunning() {
    return services.values().stream().mapToInt(cs -> cs.getCompactionsRunning(CType.INTERNAL)).sum()
        + runningExternalCompactions.size();
  }

  public int getCompactionsQueued() {
    return services.values().stream().mapToInt(cs -> cs.getCompactionsQueued(CType.INTERNAL)).sum()
        + externalExecutors.values().stream()
            .mapToInt(ee -> ee.getCompactionsQueued(CType.EXTERNAL)).sum();
  }

  public ExternalCompactionJob reserveExternalCompaction(String queueName, long priority,
      String compactorId, ExternalCompactionId externalCompactionId) {
    log.debug("Attempting to reserve external compaction, queue:{} priority:{} compactor:{}",
        queueName, priority, compactorId);

    ExternalCompactionExecutor extCE = getExternalExecutor(queueName);
    var ecJob = extCE.reserveExternalCompaction(priority, compactorId, externalCompactionId);
    if (ecJob != null) {
      runningExternalCompactions.put(ecJob.getExternalCompactionId(),
          new ExtCompInfo(ecJob.getExtent(), extCE.getId()));
      log.debug("Reserved external compaction {}", ecJob.getExternalCompactionId());
    }
    return ecJob;
  }

  ExternalCompactionExecutor getExternalExecutor(CompactionExecutorId ceid) {
    return externalExecutors.computeIfAbsent(ceid, id -> new ExternalCompactionExecutor(id));
  }

  ExternalCompactionExecutor getExternalExecutor(String queueName) {
    return getExternalExecutor(CompactionExecutorIdImpl.externalId(queueName));
  }

  public void registerExternalCompaction(ExternalCompactionId ecid, KeyExtent extent,
      CompactionExecutorId ceid) {
    runningExternalCompactions.put(ecid, new ExtCompInfo(extent, ceid));
  }

  public void commitExternalCompaction(ExternalCompactionId extCompactionId,
      KeyExtent extentCompacted, Map<KeyExtent,Tablet> currentTablets, long fileSize,
      long entries) {
    var ecInfo = runningExternalCompactions.get(extCompactionId);
    if (ecInfo != null) {
      Preconditions.checkState(ecInfo.extent.equals(extentCompacted),
          "Unexpected extent seen on compaction commit %s %s", ecInfo.extent, extentCompacted);
      Tablet tablet = currentTablets.get(ecInfo.extent);
      if (tablet != null) {
        tablet.asCompactable().commitExternalCompaction(extCompactionId, fileSize, entries);
        compactablesToCheck.add(tablet.asCompactable());
      }
      runningExternalCompactions.remove(extCompactionId);
    }
  }

  public void externalCompactionFailed(ExternalCompactionId ecid, KeyExtent extentCompacted,
      Map<KeyExtent,Tablet> currentTablets) {
    var ecInfo = runningExternalCompactions.get(ecid);
    if (ecInfo != null) {
      Preconditions.checkState(ecInfo.extent.equals(extentCompacted),
          "Unexpected extent seen on compaction commit %s %s", ecInfo.extent, extentCompacted);
      Tablet tablet = currentTablets.get(ecInfo.extent);
      if (tablet != null) {
        tablet.asCompactable().externalCompactionFailed(ecid);
        compactablesToCheck.add(tablet.asCompactable());
      }
      runningExternalCompactions.remove(ecid);
    }
  }

  public List<TCompactionQueueSummary> getCompactionQueueSummaries() {
    return externalExecutors.values().stream().flatMap(ece -> ece.summarize())
        .collect(Collectors.toList());
  }

  public static class ExtCompMetric {
    public CompactionExecutorId ceid;
    public int running;
    public int queued;
  }

  public Collection<ExtCompMetric> getExternalMetrics() {
    Map<CompactionExecutorId,ExtCompMetric> metrics = new HashMap<>();

    externalExecutors.forEach((eeid, ece) -> {
      ExtCompMetric ecm = new ExtCompMetric();
      ecm.ceid = eeid;
      ecm.queued = ece.getCompactionsQueued(CType.EXTERNAL);
      metrics.put(eeid, ecm);
    });

    runningExternalCompactions.values().forEach(eci -> {
      var ecm = metrics.computeIfAbsent(eci.executor, id -> {
        var newEcm = new ExtCompMetric();
        newEcm.ceid = id;
        return newEcm;
      });

      ecm.running++;
    });

    return metrics.values();
  }

  public void compactableClosed(KeyExtent extent, Set<CompactionServiceId> servicesUsed,
      Set<ExternalCompactionId> ecids) {
    runningExternalCompactions.keySet().removeAll(ecids);
    servicesUsed.stream().map(services::get).filter(Objects::nonNull)
        .forEach(compService -> compService.compactableClosed(extent));
  }
}
