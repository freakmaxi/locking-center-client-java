package com.freakmaxi.lockingcenter;

import static com.freakmaxi.lockingcenter.Assert.assertFalse;
import static com.freakmaxi.lockingcenter.Assert.assertThrows;
import static com.freakmaxi.lockingcenter.Assert.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Integration tests against a live locking-center server. The address is read
 * from the {@code LOCKING_CENTER_ADDRESS} environment variable (default
 * {@code 127.0.0.1:22119}).
 */
final class IntegrationTest {
    private IntegrationTest() {
    }

    static void run(TestRunner runner, String address) throws Exception {
        LockingCenter client = new LockingCenter(address);
        LockingCenter other = new LockingCenter(address, "other-owner");

        String prefix = "java-it-" + System.nanoTime() + "/";

        runner.test("constructor rejects a malformed address and an over-long source", () -> {
            assertThrows("no port", IllegalArgumentException.class, () -> construct("localhost"));
            assertThrows("bad port", IllegalArgumentException.class, () -> construct("localhost:abc"));
            assertThrows("source", IllegalArgumentException.class, () -> construct(address, "s".repeat(128)));
        });

        runner.test("lock acquires", () -> {
            String key = prefix + "lock";
            client.lock(key);
            client.unlock(key);
        });

        runner.test("try lock on a free key returns true", () -> {
            String key = prefix + "trylock-free";
            assertTrue("try lock", client.tryLock(key));
            client.unlock(key);
        });

        runner.test("try lock on a held key returns false immediately", () -> {
            String key = prefix + "trylock-held";
            client.lock(key);
            try {
                long start = System.nanoTime();
                boolean got = other.tryLock(key);
                long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                assertFalse("try lock on held key", got);
                assertTrue("returned in " + elapsedMillis + "ms, expected < 1000ms", elapsedMillis < 1000);

                // the same owner does not get it either, a lock is per key
                assertFalse("try lock by the same owner", client.tryLock(key));
            } finally {
                client.unlock(key);
            }
        });

        runner.test("unlock releases so a following try lock succeeds", () -> {
            String key = prefix + "unlock";
            client.lock(key);
            assertFalse("held", other.tryLock(key));
            client.unlock(key);
            assertTrue("released", other.tryLock(key));
            other.unlock(key);
        });

        runner.test("lock blocks until the holder unlocks", () -> {
            String key = prefix + "queue";
            client.lock(key);
            CountDownLatch acquired = new CountDownLatch(1);
            Thread waiter = new Thread(() -> {
                other.lock(key);
                acquired.countDown();
            });
            waiter.start();
            try {
                assertFalse("still held", acquired.await(700, TimeUnit.MILLISECONDS));
                client.unlock(key);
                assertTrue("acquired after unlock", acquired.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            } finally {
                other.unlock(key);
            }
        });

        runner.test("wait returns once the key is free and does not hold it", () -> {
            String key = prefix + "wait";
            client.lock(key);
            CountDownLatch done = new CountDownLatch(1);
            Thread waiter = new Thread(() -> {
                other.wait(key);
                done.countDown();
            });
            waiter.start();
            try {
                assertFalse("still held", done.await(700, TimeUnit.MILLISECONDS));
                client.unlock(key);
                assertTrue("wait returned after unlock", done.await(5, TimeUnit.SECONDS));
                assertTrue("key is free after wait", client.tryLock(key));
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            } finally {
                client.unlock(key);
            }
        });

        runner.test("non-ASCII key round-trips", () -> {
            String key = prefix + "café-ключ";
            client.lock(key);
            assertFalse("held", other.tryLock(key));
            client.unlock(key);
            assertTrue("released", other.tryLock(key));
            other.unlock(key);
        });

        runner.test("reset by key releases a held key", () -> {
            String key = prefix + "reset-key";
            client.lock(key);
            assertFalse("held", other.tryLock(key));
            client.resetByKey(key);
            assertTrue("released by reset", other.tryLock(key));
            other.unlock(key);
        });

        runner.test("reset by source releases everything the owner held", () -> {
            String a = prefix + "reset-source-a";
            String b = prefix + "reset-source-b";
            other.lock(a);
            other.lock(b);
            assertFalse("a held", client.tryLock(a));
            assertFalse("b held", client.tryLock(b));
            client.resetBySource("other-owner");
            assertTrue("a released", client.tryLock(a));
            assertTrue("b released", client.tryLock(b));
            client.unlock(a);
            client.unlock(b);
        });

        runner.test("reset by source with no source answers success", () -> client.resetBySource(null));

        runner.test("client is safe to share between threads", () -> {
            String key = prefix + "concurrent";
            int threads = 8;
            int rounds = 5;
            int[] counter = {0};
            int[] maxInside = {0};
            int[] inside = {0};
            Thread[] workers = new Thread[threads];
            Throwable[] failure = {null};
            for (int i = 0; i < threads; i++) {
                workers[i] = new Thread(() -> {
                    try {
                        for (int r = 0; r < rounds; r++) {
                            client.lock(key);
                            try {
                                // no synchronization on purpose, the server lock is the only guard
                                int now = ++inside[0];
                                if (now > maxInside[0]) {
                                    maxInside[0] = now;
                                }
                                counter[0]++;
                                Thread.sleep(2);
                                inside[0]--;
                            } finally {
                                client.unlock(key);
                            }
                        }
                    } catch (Throwable t) {
                        failure[0] = t;
                    }
                });
                workers[i].start();
            }
            for (Thread w : workers) {
                try {
                    w.join(60_000);
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                }
            }
            if (failure[0] != null) {
                throw new AssertionError(failure[0]);
            }
            assertTrue("all rounds ran: " + counter[0], counter[0] == threads * rounds);
            assertTrue("never more than one inside, saw " + maxInside[0], maxInside[0] == 1);
        });
    }

    private static void construct(String address) {
        construct(address, null);
    }

    private static void construct(String address, String source) {
        try {
            new LockingCenter(address, source);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
