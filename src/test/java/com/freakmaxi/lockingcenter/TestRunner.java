package com.freakmaxi.lockingcenter;

/**
 * Runs the unit and integration tests with nothing but the JDK, exiting
 * non-zero on any failure. Maven's test phase invokes it too, so the same
 * tests run with and without Maven.
 *
 * <p>Environment:
 * <ul>
 * <li>{@code LOCKING_CENTER_ADDRESS}: server for the integration tests, default {@code 127.0.0.1:22119}</li>
 * <li>{@code LOCKING_CENTER_SKIP_INTEGRATION=1}: run only the unit tests</li>
 * </ul>
 */
public final class TestRunner {
    private int passed;
    private int failed;

    /** A test body that may throw a checked exception. */
    @FunctionalInterface
    interface Body {
        void run() throws Exception;
    }

    void test(String name, Body body) {
        long start = System.nanoTime();
        try {
            body.run();
            passed++;
            System.out.printf("  PASS  %s (%d ms)%n", name, (System.nanoTime() - start) / 1_000_000);
        } catch (Throwable t) {
            failed++;
            System.out.printf("  FAIL  %s: %s%n", name, t);
            t.printStackTrace(System.out);
        }
    }

    public static void main(String[] args) throws Exception {
        TestRunner runner = new TestRunner();

        System.out.println("Packet encoding");
        PacketTest.run(runner);

        System.out.println("Validation (local loopback listener, no server needed)");
        ValidationTest.run(runner);

        if (!"1".equals(System.getenv("LOCKING_CENTER_SKIP_INTEGRATION"))) {
            String address = System.getenv("LOCKING_CENTER_ADDRESS");
            if (address == null || address.isEmpty()) {
                address = "127.0.0.1:22119";
            }
            System.out.println("Integration against " + address);
            IntegrationTest.run(runner, address);
        } else {
            System.out.println("Integration tests skipped");
        }

        System.out.printf("%n%d passed, %d failed%n", runner.passed, runner.failed);
        System.exit(runner.failed == 0 ? 0 : 1);
    }
}
