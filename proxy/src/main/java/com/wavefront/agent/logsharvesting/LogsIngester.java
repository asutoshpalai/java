package com.wavefront.agent.logsharvesting;

import com.google.common.annotations.VisibleForTesting;

import com.wavefront.agent.config.ConfigurationException;
import com.wavefront.agent.config.LogsIngestionConfig;
import com.wavefront.agent.config.MetricMatcher;
import com.wavefront.agent.handlers.ReportableEntityHandlerFactory;
import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.Counter;
import com.yammer.metrics.core.Metric;
import com.yammer.metrics.core.MetricName;

import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import wavefront.report.TimeSeries;

/**
 * Consumes log messages sent to {@link #ingestLog(LogsMessage)}. Configures and starts the periodic flush of
 * consumed metric data to Wavefront.
 *
 * @author Mori Bellamy (mori@wavefront.com)
 */
public class LogsIngester {
  protected static final Logger logger = Logger.getLogger(LogsIngester.class.getCanonicalName());
  private static final ReadProcessor readProcessor = new ReadProcessor();
  private final FlushProcessor flushProcessor;
  // A map from "true" to the currently loaded logs ingestion config.
  @VisibleForTesting
  final LogsIngestionConfigManager logsIngestionConfigManager;
  private final Counter unparsed, parsed;
  private final Supplier<Long> currentMillis;
  private final MetricsReporter metricsReporter;
  private EvictingMetricsRegistry evictingMetricsRegistry;

  /**
   * @param handlerFactory              factory for point handlers and histogram handlers
   * @param logsIngestionConfigSupplier supplied configuration object for logs harvesting. May be reloaded. Must return
   *                                    "null" on any problems, as opposed to throwing
   * @param prefix                      all harvested metrics start with this prefix
   * @param currentMillis               supplier of the current time in millis
   * @throws ConfigurationException if the first config from logsIngestionConfigSupplier is null
   */
  public LogsIngester(ReportableEntityHandlerFactory handlerFactory,
                      Supplier<LogsIngestionConfig> logsIngestionConfigSupplier,
                      String prefix, Supplier<Long> currentMillis) throws ConfigurationException {
    logsIngestionConfigManager = new LogsIngestionConfigManager(
        logsIngestionConfigSupplier,
        removedMetricMatcher -> evictingMetricsRegistry.evict(removedMetricMatcher));
    LogsIngestionConfig logsIngestionConfig = logsIngestionConfigManager.getConfig();

    this.evictingMetricsRegistry = new EvictingMetricsRegistry(
        logsIngestionConfig.expiryMillis, true, currentMillis);

    // Logs harvesting metrics.
    this.unparsed = Metrics.newCounter(new MetricName("logsharvesting", "", "unparsed"));
    this.parsed = Metrics.newCounter(new MetricName("logsharvesting", "", "parsed"));
    this.currentMillis = currentMillis;
    this.flushProcessor = new FlushProcessor(currentMillis, logsIngestionConfig.useWavefrontHistograms,
        logsIngestionConfig.reportEmptyHistogramStats);

    // Continually flush user metrics to Wavefront.
    this.metricsReporter = new MetricsReporter(
        evictingMetricsRegistry.metricsRegistry(), flushProcessor, "FilebeatMetricsReporter", handlerFactory, prefix);
  }

  public void start() {
    this.metricsReporter.start(
        this.logsIngestionConfigManager.getConfig().aggregationIntervalSeconds,
        TimeUnit.SECONDS);
  }

  public void flush() {
    this.metricsReporter.run();
  }

  @VisibleForTesting
  MetricsReporter getMetricsReporter() {
    return metricsReporter;
  }

  public void ingestLog(LogsMessage logsMessage) {
    LogsIngestionConfig logsIngestionConfig = logsIngestionConfigManager.getConfig();

    boolean success = false;
    for (MetricMatcher metricMatcher : logsIngestionConfig.counters) {
      success |= maybeIngestLog(evictingMetricsRegistry::getCounter, metricMatcher, logsMessage);
    }

    for (MetricMatcher metricMatcher : logsIngestionConfig.gauges) {
      success |= maybeIngestLog(evictingMetricsRegistry::getGauge, metricMatcher, logsMessage);
    }

    for (MetricMatcher metricMatcher : logsIngestionConfig.histograms) {
      success |= maybeIngestLog(evictingMetricsRegistry::getHistogram, metricMatcher, logsMessage);
    }

    if (success) {
      parsed.inc();
    } else {
      unparsed.inc();
    }
  }

  private boolean maybeIngestLog(
      BiFunction<MetricName, MetricMatcher, Metric> metricLoader, MetricMatcher metricMatcher,
      LogsMessage logsMessage) {
    Double[] output = {null};
    TimeSeries timeSeries = metricMatcher.timeSeries(logsMessage, output);
    if (timeSeries == null) return false;
    MetricName metricName = TimeSeriesUtils.toMetricName(timeSeries);
    Metric metric = metricLoader.apply(metricName, metricMatcher);
    try {
      metric.processWith(readProcessor, metricName, new ReadProcessorContext(output[0]));
    } catch (Exception e) {
      logger.log(Level.SEVERE, "Could not process metric " + metricName.toString(), e);
    }
    return true;
  }
}
