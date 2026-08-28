package com.freakmaxi.lockingcenter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Client of a <a href="https://github.com/freakmaxi/locking-center">Locking-Center</a>
 * server, a mutex point that synchronizes access to shared resources between
 * different services.
 *
 * <p>An instance is safe to share between threads. Every call opens its own
 * short-lived TCP connection, there is no shared socket state and nothing to
 * close.
 */
public final class LockingCenter {
    private static final System.Logger LOG = System.getLogger(LockingCenter.class.getName());

    private static final long QUEUE_RETRY_MILLIS = 500;

    /** The largest key or source address a request can carry, in UTF-8 bytes. */
    public static final int MAX_VALUE_SIZE = Packet.MAX_VALUE_SIZE;

    private final String host;
    private final int port;
    private final String source;

    /**
     * Connects to the server at {@code address} ("host:port"), letting the
     * server identify this owner by the connection's peer address.
     *
     * @throws IOException if the server can not be reached
     * @throws IllegalArgumentException if the address is malformed
     */
    public LockingCenter(String address) throws IOException {
        this(address, null);
    }

    /**
     * Connects to the server at {@code address} ("host:port") with an explicit
     * source address that identifies this owner, so that everything it holds
     * can later be released with {@link #resetBySource(String)}.
     *
     * @param source the owner identity, at most 127 UTF-8 bytes, or {@code null}
     *               to let the server use the connection's peer address
     * @throws IOException if the server can not be reached
     * @throws IllegalArgumentException if the address is malformed or the
     *                                  source is too long
     */
    public LockingCenter(String address, String source) throws IOException {
        if (address == null || address.isEmpty()) {
            throw new IllegalArgumentException("address can not be empty");
        }
        int colon = address.lastIndexOf(':');
        if (colon <= 0 || colon == address.length() - 1) {
            throw new IllegalArgumentException("address must be in host:port form");
        }
        int parsedPort;
        try {
            parsedPort = Integer.parseInt(address.substring(colon + 1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("address must be in host:port form", e);
        }
        if (parsedPort < 1 || parsedPort > 65535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
        checkSource(source);

        this.host = address.substring(0, colon);
        this.port = parsedPort;
        this.source = source == null || source.isEmpty() ? null : source;

        ping();
    }

    /** Fails fast on a key that can never succeed, rather than letting the retry loops spin on it forever. */
    private static void checkKey(String key) {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("key can not be empty");
        }
        if (key.getBytes(StandardCharsets.UTF_8).length > MAX_VALUE_SIZE) {
            throw new IllegalArgumentException("key can not be longer than " + MAX_VALUE_SIZE + " bytes");
        }
    }

    private static void checkSource(String source) {
        if (source != null && source.getBytes(StandardCharsets.UTF_8).length > MAX_VALUE_SIZE) {
            throw new IllegalArgumentException("source address can not be longer than " + MAX_VALUE_SIZE + " bytes");
        }
    }

    /** Dials the server once, like the Go client, to make sure it is reachable. */
    private void ping() throws IOException {
        Socket socket = new Socket(host, port);
        socket.close();
    }

    /**
     * Sends one request over a fresh connection and reports whether the server
     * answered '+'. Any other answer, a closed connection or a connection
     * failure reads as false.
     *
     * <p>No read timeout is set on purpose: for a lock the server holds the
     * connection open for as long as the key is held by its current owner,
     * which is unbounded.
     */
    private boolean query(MutexAction action, String key, String source) {
        byte[] packet = Packet.prepare(action, key, source);
        try (Socket socket = new Socket(host, port)) {
            OutputStream out = socket.getOutputStream();
            out.write(packet);
            out.flush();

            InputStream in = socket.getInputStream();
            int answer = in.read();
            // read() returns -1 at the end of the stream, a closed connection
            // must read as a failure, not be cast to a byte.
            if (answer < 0) {
                LOG.log(System.Logger.Level.WARNING, "{0} failed: connection closed by server", action);
                return false;
            }
            if (answer == '-') {
                LOG.log(System.Logger.Level.WARNING, "{0} failed: remote server execution error", action);
                return false;
            }
            return answer == '+';
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "{0} failed: connection failure: {1}", action, e.getMessage());
            return false;
        }
    }

    /**
     * Retries a failed query after a fixed delay, so a down server or a
     * rejected request does not turn the loop into a busy wait. A successful
     * lock blocks on the server rather than spinning here, so the delay is only
     * ever paid on failure.
     */
    private void retry(MutexAction action, String key, String source) {
        while (!query(action, key, source)) {
            try {
                Thread.sleep(QUEUE_RETRY_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while retrying " + action, e);
            }
        }
    }

    /**
     * Acquires the key, waiting in the queue until it is free. Keeps trying
     * through connection failures, so it returns only once the key is held.
     *
     * @throws IllegalArgumentException if the key is empty or longer than 127 UTF-8 bytes
     */
    public void lock(String key) {
        checkKey(key);
        retry(MutexAction.LOCK, key, source);
    }

    /**
     * Attempts the lock once and returns immediately, unlike {@link #lock}
     * which blocks until the key is free.
     *
     * @return {@code true} when the key was acquired, {@code false} when it is
     *         held by somebody else or the server could not be reached
     * @throws IllegalArgumentException if the key is empty or longer than 127 UTF-8 bytes
     */
    public boolean tryLock(String key) {
        checkKey(key);
        return query(MutexAction.TRY_LOCK, key, source);
    }

    /**
     * Releases the key so the next queued request, if any, acquires it. Retries
     * every 500 ms until the server confirms.
     *
     * @throws IllegalArgumentException if the key is empty or longer than 127 UTF-8 bytes
     */
    public void unlock(String key) {
        checkKey(key);
        retry(MutexAction.UNLOCK, key, null);
    }

    /**
     * Blocks until the key is free and then releases it right away, without
     * keeping it. It is {@link #lock} followed by {@link #unlock}: a way to
     * pause until whoever holds the key is done, when there is no work of your
     * own to protect.
     *
     * @throws IllegalArgumentException if the key is empty or longer than 127 UTF-8 bytes
     */
    public void wait(String key) {
        lock(key);
        unlock(key);
    }

    /**
     * Force releases the key no matter who holds it and lets the queued requests
     * contend for it again. A lock is not tied to its connection, so a client
     * that crashes while holding a key leaves it locked; this is how an operator
     * or a supervisor clears such a stuck lock. Retries every 500 ms until the
     * server confirms.
     *
     * @throws IllegalArgumentException if the key is empty or longer than 127 UTF-8 bytes
     */
    public void resetByKey(String key) {
        checkKey(key);
        retry(MutexAction.RESET_BY_KEY, key, null);
    }

    /**
     * Force releases every key held by the owner identified by
     * {@code sourceAddr}, the address a client was constructed with. It is the
     * recovery path for a whole instance that went away, on Kubernetes
     * typically a crashed pod's IP. Retries every 500 ms until the server
     * confirms.
     *
     * @param sourceAddr the owner's source address, or {@code null} to let the
     *                   server fall back to this connection's peer address
     * @throws IllegalArgumentException if the source is longer than 127 UTF-8 bytes
     */
    public void resetBySource(String sourceAddr) {
        checkSource(sourceAddr);
        retry(MutexAction.RESET_BY_SOURCE, null, sourceAddr);
    }
}
