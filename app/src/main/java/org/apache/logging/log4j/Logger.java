package org.apache.logging.log4j;

public interface Logger {
    default void trace(String message) {}
    default void trace(String message, Object... params) {}
    default void trace(String message, Throwable t) {}
    default boolean isTraceEnabled() { return false; }

    default void debug(String message) {}
    default void debug(String message, Object... params) {}
    default void debug(String message, Throwable t) {}
    default boolean isDebugEnabled() { return false; }

    default void info(String message) {}
    default void info(String message, Object... params) {}
    default void info(String message, Throwable t) {}
    default boolean isInfoEnabled() { return false; }

    default void warn(String message) {}
    default void warn(String message, Object... params) {}
    default void warn(String message, Throwable t) {}
    default boolean isWarnEnabled() { return false; }

    default void error(String message) {}
    default void error(String message, Object... params) {}
    default void error(String message, Throwable t) {}
    default boolean isErrorEnabled() { return false; }

    default void fatal(String message) {}
    default void fatal(String message, Object... params) {}
    default void fatal(String message, Throwable t) {}
    default boolean isFatalEnabled() { return false; }

    default void log(Level level, String message) {}
    default void log(Level level, String message, Object... params) {}
    default void log(Level level, String message, Throwable t) {}
    default boolean isEnabled(Level level) { return false; }
}