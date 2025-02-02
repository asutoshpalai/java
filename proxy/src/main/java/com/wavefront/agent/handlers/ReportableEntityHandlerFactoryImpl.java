package com.wavefront.agent.handlers;

import com.wavefront.api.agent.ValidationConfiguration;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Logger;

import javax.annotation.Nullable;

/**
 * Caching factory for {@link ReportableEntityHandler} objects. Makes sure there's only one handler
 * for each {@link HandlerKey}, which makes it possible to spin up handlers on demand at runtime,
 * as well as redirecting traffic to a different pipeline.
 *
 * @author vasily@wavefront.com
 */
public class ReportableEntityHandlerFactoryImpl implements ReportableEntityHandlerFactory {
  private static final Logger logger = Logger.getLogger(ReportableEntityHandlerFactoryImpl.class.getCanonicalName());

  private static final int SOURCE_TAGS_NUM_THREADS = 2;

  private Map<HandlerKey, ReportableEntityHandler> handlers = new HashMap<>();

  private final SenderTaskFactory senderTaskFactory;
  private final int blockedItemsPerBatch;
  private final int defaultFlushThreads;
  private final Supplier<ValidationConfiguration> validationConfig;

  /**
   * Create new instance.
   *
   * @param senderTaskFactory    SenderTaskFactory instance used to create SenderTasks for new handlers
   * @param blockedItemsPerBatch controls sample rate of how many blocked points are written into the main log file.
   * @param defaultFlushThreads  control fanout for SenderTasks.
   * @param validationConfig     Supplier for the ValidationConfiguration
   */
  public ReportableEntityHandlerFactoryImpl(final SenderTaskFactory senderTaskFactory,
                                            final int blockedItemsPerBatch,
                                            final int defaultFlushThreads,
                                            @Nullable final Supplier<ValidationConfiguration> validationConfig) {
    this.senderTaskFactory = senderTaskFactory;
    this.blockedItemsPerBatch = blockedItemsPerBatch;
    this.defaultFlushThreads = defaultFlushThreads;
    this.validationConfig = validationConfig;
  }

  public ReportableEntityHandler getHandler(HandlerKey handlerKey) {
    return handlers.computeIfAbsent(handlerKey, k -> {
      switch (handlerKey.getEntityType()) {
        case POINT:
        case HISTOGRAM:
          return new ReportPointHandlerImpl(handlerKey.getHandle(), blockedItemsPerBatch,
              senderTaskFactory.createSenderTasks(handlerKey, defaultFlushThreads), validationConfig);
        case SOURCE_TAG:
          return new ReportSourceTagHandlerImpl(handlerKey.getHandle(), blockedItemsPerBatch,
              senderTaskFactory.createSenderTasks(handlerKey, SOURCE_TAGS_NUM_THREADS));
        case TRACE:
          return new SpanHandlerImpl(handlerKey.getHandle(), blockedItemsPerBatch,
              senderTaskFactory.createSenderTasks(handlerKey, defaultFlushThreads), validationConfig);
        case TRACE_SPAN_LOGS:
          return new SpanLogsHandlerImpl(handlerKey.getHandle(), blockedItemsPerBatch,
              senderTaskFactory.createSenderTasks(handlerKey, defaultFlushThreads));
        default:
          throw new IllegalArgumentException("Unexpected entity type " + handlerKey.getEntityType().name() +
              " for " + handlerKey.getHandle());
      }
    });
  }

  public void shutdown() {
    //
  }
}
