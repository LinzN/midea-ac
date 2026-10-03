package de.mirranet.midea.ac;

import java.util.Arrays;
import java.util.Objects;

/** Tiny assertion helper so the tests run without JUnit (Maven Central is not required). */
public final class Check {

    static int passed;
    static int failed;

    private Check() {
    }

    public static void eq(String name, Object expected, Object actual) {
        boolean ok = expected instanceof byte[] e && actual instanceof byte[] a
                ? Arrays.equals(e, a) : Objects.equals(expected, actual);
        record(name, ok, "expected <" + expected + "> but was <" + actual + ">");
    }

    public static void near(String name, double expected, Double actual) {
        record(name, actual != null && Math.abs(expected - actual) < 1e-9,
                "expected <" + expected + "> but was <" + actual + ">");
    }

    public static void ok(String name, boolean condition) {
        record(name, condition, "condition false");
    }

    private static void record(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("  ok   " + name);
        } else {
            failed++;
            System.out.println("  FAIL " + name + ": " + detail);
        }
    }
}
