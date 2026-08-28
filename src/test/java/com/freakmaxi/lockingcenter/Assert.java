package com.freakmaxi.lockingcenter;

import java.util.Arrays;

/** Minimal assertions so the tests run with nothing but the JDK. */
final class Assert {
    private Assert() {
    }

    static void assertTrue(String what, boolean condition) {
        if (!condition) {
            throw new AssertionError(what + ": expected true");
        }
    }

    static void assertFalse(String what, boolean condition) {
        if (condition) {
            throw new AssertionError(what + ": expected false");
        }
    }

    static void assertBytes(String what, byte[] expected, byte[] actual) {
        if (!Arrays.equals(expected, actual)) {
            throw new AssertionError(what + ": expected " + Arrays.toString(expected) + " but got " + Arrays.toString(actual));
        }
    }

    static <T extends Throwable> T assertThrows(String what, Class<T> type, Runnable body) {
        try {
            body.run();
        } catch (Throwable t) {
            if (type.isInstance(t)) {
                return type.cast(t);
            }
            throw new AssertionError(what + ": expected " + type.getSimpleName() + " but got " + t, t);
        }
        throw new AssertionError(what + ": expected " + type.getSimpleName() + " but nothing was thrown");
    }
}
