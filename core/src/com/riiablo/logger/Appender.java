package com.riiablo.logger;

public interface Appender {
  void append(LogEvent event);
  Layouter layout();
}
