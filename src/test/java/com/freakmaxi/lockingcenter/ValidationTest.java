package com.freakmaxi.lockingcenter;

import static com.freakmaxi.lockingcenter.Assert.assertThrows;
import static com.freakmaxi.lockingcenter.Assert.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Key and source validation must happen before any network I/O. These tests need no locking-center server: a loopback
 * listener counts the connections the client makes, the constructor's ping must be the only one and a bad key or source
 * must not produce a single more.
 */
final class ValidationTest {
    private ValidationTest() {
    }

    /** A loopback listener that accepts and immediately closes, counting every connection. */
    private static final class CountingListener implements AutoCloseable {
        final ServerSocket socket;
        final AtomicInteger connections = new AtomicInteger();

        CountingListener() throws IOException {
            socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            Thread acceptor = new Thread(() -> {
                try {
                    while (true) {
                        socket.accept().close();
                        connections.incrementAndGet();
                    }
                } catch (IOException ignored) {
                    // listener closed
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();
        }

        String address() {
            return "127.0.0.1:" + socket.getLocalPort();
        }

        int connectionsAfterSettling() throws InterruptedException {
            Thread.sleep(200);
            return connections.get();
        }

        void awaitConnections(int expected) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (connections.get() < expected && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    private static LockingCenter construct(String address, String source) {
        try {
            return new LockingCenter(address, source);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void run(TestRunner runner) {
        runner.test("empty and over-long keys fail fast without any network call", () -> {
            try (CountingListener listener = new CountingListener()) {
                LockingCenter lc = new LockingCenter(listener.address());
                listener.awaitConnections(1);
                int afterPing = listener.connections.get();
                assertTrue("constructor ping is exactly one connection, saw " + afterPing, afterPing == 1);

                String longKey = "k".repeat(200);
                assertThrows("lock empty", IllegalArgumentException.class, () -> lc.lock(""));
                assertThrows("lock null", IllegalArgumentException.class, () -> lc.lock(null));
                assertThrows("lock 200 bytes", IllegalArgumentException.class, () -> lc.lock(longKey));
                assertThrows("try lock empty", IllegalArgumentException.class, () -> lc.tryLock(""));
                assertThrows("try lock 200 bytes", IllegalArgumentException.class, () -> lc.tryLock(longKey));
                assertThrows("unlock empty", IllegalArgumentException.class, () -> lc.unlock(""));
                assertThrows("unlock 200 bytes", IllegalArgumentException.class, () -> lc.unlock(longKey));
                assertThrows("wait empty", IllegalArgumentException.class, () -> lc.wait(""));
                assertThrows("wait 200 bytes", IllegalArgumentException.class, () -> lc.wait(longKey));
                assertThrows("reset by key empty", IllegalArgumentException.class, () -> lc.resetByKey(""));
                assertThrows("reset by key 200 bytes", IllegalArgumentException.class, () -> lc.resetByKey(longKey));
                assertThrows("reset by source 200 bytes", IllegalArgumentException.class,
                        () -> lc.resetBySource("s".repeat(200)));
                // 64 chars but 128 UTF-8 bytes: the byte count is what matters, not the char count
                assertThrows("64 two-byte chars", IllegalArgumentException.class, () -> lc.lock("é".repeat(64)));
                // 127 bytes is the limit itself and must pass validation (this one does reach the network)
                assertTrue("127 byte key passes validation", !lc.tryLock("k".repeat(127)));
                listener.awaitConnections(2);

                int extra = listener.connectionsAfterSettling() - afterPing - 1;
                assertTrue("bad keys must not touch the network, saw " + extra + " extra connections", extra == 0);
            }
        });

        runner.test("constructor rejects an over-long source before dialing", () -> {
            try (CountingListener listener = new CountingListener()) {
                assertThrows("128 byte source", IllegalArgumentException.class,
                        () -> construct(listener.address(), "s".repeat(128)));
                assertThrows("64 two-byte chars", IllegalArgumentException.class,
                        () -> construct(listener.address(), "é".repeat(64)));
                assertTrue("no connection for an invalid source", listener.connectionsAfterSettling() == 0);

                construct(listener.address(), "s".repeat(127));
                listener.awaitConnections(1);
                assertTrue("127 byte source is accepted and pings once", listener.connections.get() == 1);
            }
        });

        runner.test("constructor fails with IOException when the server is unreachable", () -> {
            int free;
            try (ServerSocket probe = new ServerSocket(0)) {
                free = probe.getLocalPort();
            }
            UncheckedIOException wrapped = assertThrows("unreachable", UncheckedIOException.class,
                    () -> construct("127.0.0.1:" + free, null));
            assertTrue("cause is an IOException", wrapped.getCause() instanceof IOException);
        });
    }
}
