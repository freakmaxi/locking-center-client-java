# Locking-Center Java Client

The Java connector for [Locking-Center](https://github.com/freakmaxi/locking-center), a mutex point that synchronizes
access to shared resources between different services. Lock a key before you touch the resource, do the work, unlock the
key. Only one caller holds a given key at a time, the rest queue up and are served in order.

- [Locking-Center Server](https://github.com/freakmaxi/locking-center)

The client has **no dependencies** beyond the JDK (Java 21 or newer).

## Installation

With Maven, build and install the artifact into your local repository:

```shell
mvn install
```

then depend on it:

```xml
<dependency>
    <groupId>com.freakmaxi</groupId>
    <artifactId>locking-center-client</artifactId>
    <version>1.0.0</version>
</dependency>
```

Without Maven, `./build.sh --no-test` produces `target/locking-center-client.jar` with a plain JDK; put it on your
classpath.

## Quick start

```java
import com.freakmaxi.lockingcenter.LockingCenter;

public class Main {
    public static void main(String[] args) throws Exception {
        LockingCenter m = new LockingCenter("localhost:22119");

        m.lock("locking-key");
        try {
            System.out.println("Hello from the locked area!");
        } finally {
            m.unlock("locking-key");
        }
    }
}
```

## Connecting

```java
// simplest form
LockingCenter m = new LockingCenter("localhost:22119");

// with a source address, which identifies this owner for crash recovery, see below
LockingCenter m = new LockingCenter("localhost:22119", "10.0.0.4");
```

The constructor dials the server once to make sure it is reachable and throws an `IOException` if it is not. A
malformed address or a source longer than 127 bytes throws an `IllegalArgumentException`. The returned object is safe
to keep and share across threads; every call opens its own short-lived connection.

## API

| Method | Blocks | Description |
| --- | --- | --- |
| `lock(key)` | yes | Acquires the key, waiting in the queue until it is free |
| `tryLock(key) boolean` | no | Acquires the key only if it is free right now, returns whether it did |
| `unlock(key)` | no | Releases the key |
| `wait(key)` | yes | Waits for the key to be free, then releases it again without holding it |
| `resetByKey(key)` | no | Force releases a key, whoever holds it (crash recovery) |
| `resetBySource(sourceAddr)` | no | Force releases everything a given owner held (crash recovery) |

### Locking

`lock` blocks until the key is free, then takes it. It keeps trying through connection failures, so it returns only
once the key is held.

```java
m.lock("orders/batch-7");
try {
    // ... exclusive work ...
} finally {
    m.unlock("orders/batch-7");
}
```

### Try locking

`tryLock` is the non-blocking form. It takes the key only if it is free at that moment and returns immediately, so you
decide what to do when somebody else holds it.

```java
if (m.tryLock("orders/batch-7")) {
    try {
        // ... exclusive work ...
    } finally {
        m.unlock("orders/batch-7");
    }
} else {
    // someone else holds it, skip, retry later, or do something else
}
```

`tryLock` returns `false` when the key is held by another owner **and** when the server cannot be reached, so a `false`
means only "you did not get the lock". If you need to tell the two apart, check reachability separately.

### Waiting

`wait` blocks until the key is free and then releases it immediately, without holding it. Use it to pause until whoever
holds the key is done.

```java
m.wait("migration-done"); // returns once the key is free
```

## Crash recovery: reset

A lock is not tied to its TCP connection, so a client that crashes while holding a key leaves that key locked. Nothing
releases it automatically. Reset is how an operator or a supervisor clears such a stuck lock.

```java
m.resetByKey("orders/batch-7"); // release this key, whoever holds it

m.resetBySource("10.0.0.9");    // release everything 10.0.0.9 held
```

`resetBySource` matches on the **source address**. Pass the source when you construct the client
(`new LockingCenter(address, source)`) so that each owner is identifiable; on Kubernetes, pass the pod IP. A `null`
source lets the server fall back to the connection's peer address.

## Keys

A key must be **between 1 and 127 UTF-8 bytes**. Keys are sent UTF-8 encoded and the limit is on the encoded size, so
a key of non-ASCII characters holds fewer characters than 127. An empty or over-long key is a programming error, so the
client throws an `IllegalArgumentException` right away, before touching the network, instead of hanging in the retry
loop.

## Behaviour to know

- **`lock`, `unlock` and the resets keep retrying until they succeed.** They do not throw on failure; a server that is
down just means the call keeps trying (with a 500 ms delay between attempts). Run a call on your own thread with a
timeout if you need to give up. Interrupting that thread makes the call stop with an `IllegalStateException` and the
interrupt flag set.
- **Do not put a read timeout on the connection for `lock`.** The server holds the connection open for as long as the
key is held by its current owner, which is unbounded. The client already accounts for this.
- **Every call is one short-lived TCP connection.** There is no pool to manage and nothing to close.
- **The client is safe for concurrent use** from many threads.

## Building and testing

```shell
# with Maven
mvn verify                                        # build + unit tests + integration tests against 127.0.0.1:22119
mvn verify -Dlockingcenter.address=127.0.0.1:29100
mvn verify -Dlockingcenter.skipIntegration=1      # unit tests only

# with a plain JDK
./build.sh
LOCKING_CENTER_ADDRESS=127.0.0.1:29100 ./build.sh
LOCKING_CENTER_SKIP_INTEGRATION=1 ./build.sh
```

The integration tests need a running Locking-Center server at the given address.

### Getting a server for the tests

The integration tests connect to a running server. Build one from the
[server repository](https://github.com/freakmaxi/locking-center) and start it on a free port;

```shell
go build -o lockd-server ./mutex
BIND_ADDRESS="127.0.0.1:29100" ./lockd-server &
LOCKING_CENTER_ADDRESS=127.0.0.1:29100 ./build.sh
```

Without `LOCKING_CENTER_ADDRESS` the tests default to `127.0.0.1:22119`; set `LOCKING_CENTER_SKIP_INTEGRATION=1` to
run the unit tests alone.

## License

[Apache License 2.0](LICENSE). The Locking-Center server itself is licensed separately under the GPL-3.0; the
clients are permissive so they can be embedded in any service.
