package org.apache.logging.log4j;

import android.util.Log;

public class LogManager {

    public static final Logger NOOP = new Logger() {
        @Override
        public void error(String message) {
            Log.e("SYMJA_CAS", String.valueOf(message));
        }

        @Override
        public void error(String message, Throwable t) {
            Log.e("SYMJA_CAS", String.valueOf(message), t);
        }
    };

    public static Logger getLogger() {
        return NOOP;
    }

    public static Logger getLogger(Class<?> clazz) {
        return NOOP;
    }

    public static Logger getLogger(String name) {
        return NOOP;
    }

    public static Logger getLogger(Object obj) {
        return NOOP;
    }

    public static Logger getFormatterLogger() {
        return NOOP;
    }

    public static Logger getFormatterLogger(Class<?> clazz) {
        return NOOP;
    }

    public static Logger getFormatterLogger(String name) {
        return NOOP;
    }

    public static Logger getFormatterLogger(Object obj) {
        return NOOP;
    }
}