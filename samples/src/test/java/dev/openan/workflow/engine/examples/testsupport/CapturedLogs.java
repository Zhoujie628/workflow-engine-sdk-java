/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.examples.testsupport;

import java.beans.PropertyChangeListener;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;

/** Request-local test evidence, unaffected by production log rotation or Spring logging reconfiguration. */
public final class CapturedLogs implements AutoCloseable {
  private final LoggerContext context = (LoggerContext) LogManager.getContext(false);
  private final StringBuffer content = new StringBuffer();
  private final AbstractAppender appender = new AbstractAppender(
      "test-capture-" + java.util.UUID.randomUUID(), null,
      PatternLayout.newBuilder().withPattern("%d %-5level [%t] %logger{1} - %msg%n").build(),
      true, Property.EMPTY_ARRAY) {
    @Override public void append(LogEvent event) {
      content.append(getLayout().toSerializable(event));
    }
  };
  private final PropertyChangeListener reconfigured = event -> {
    if ("config".equals(event.getPropertyName())) attach();
  };

  /** Begins capturing without replacing any existing console or protocol appender. */
  public CapturedLogs() {
    context.addPropertyChangeListener(reconfigured);
    attach();
  }

  private void attach() {
    if (context.getConfiguration().getRootLogger().getAppenders().get(appender.getName()) == appender) return;
    appender.start();
    context.getConfiguration().getRootLogger().addAppender(appender, null, null);
    context.updateLoggers();
  }

  /** Position for reading only the events produced by the next operation. */
  public int length() { return content.length(); }

  /** Returns formatted log events after the given position. */
  public String since(int offset) { return content.substring(offset); }

  /** Detaches test observation; production logging remains enabled. */
  @Override public void close() {
    context.removePropertyChangeListener(reconfigured);
    context.getConfiguration().getRootLogger().removeAppender(appender.getName());
    context.updateLoggers();
    appender.stop();
  }
}
