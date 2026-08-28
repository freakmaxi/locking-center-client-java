package com.freakmaxi.lockingcenter;

import static com.freakmaxi.lockingcenter.Assert.assertBytes;
import static com.freakmaxi.lockingcenter.Assert.assertThrows;

/** Unit tests for the wire encoding: exact bytes for every action. */
final class PacketTest {
    private PacketTest() {
    }

    static void run(TestRunner runner) {
        runner.test("lock without source", () -> assertBytes("lock",
                new byte[] {1, 10, 108, 111, 99, 107, 105, 110, 103, 45, 109, 101, 0},
                Packet.prepare(MutexAction.LOCK, "locking-me", null)));

        runner.test("lock with empty source is the same as no source", () -> assertBytes("lock",
                new byte[] {1, 10, 108, 111, 99, 107, 105, 110, 103, 45, 109, 101, 0},
                Packet.prepare(MutexAction.LOCK, "locking-me", "")));

        runner.test("lock with source", () -> assertBytes("lock",
                new byte[] {1, 3, 'k', 'e', 'y', 8, '1', '0', '.', '0', '.', '0', '.', '4'},
                Packet.prepare(MutexAction.LOCK, "key", "10.0.0.4")));

        runner.test("unlock", () -> assertBytes("unlock",
                new byte[] {2, 10, 108, 111, 99, 107, 105, 110, 103, 45, 109, 101},
                Packet.prepare(MutexAction.UNLOCK, "locking-me", null)));

        runner.test("unlock ignores the source", () -> assertBytes("unlock",
                new byte[] {2, 3, 'k', 'e', 'y'},
                Packet.prepare(MutexAction.UNLOCK, "key", "10.0.0.4")));

        runner.test("reset by key", () -> assertBytes("reset by key",
                new byte[] {3, 10, 108, 111, 99, 107, 105, 110, 103, 45, 109, 101},
                Packet.prepare(MutexAction.RESET_BY_KEY, "locking-me", null)));

        runner.test("reset by source", () -> assertBytes("reset by source",
                new byte[] {4, 8, '1', '0', '.', '0', '.', '0', '.', '9'},
                Packet.prepare(MutexAction.RESET_BY_SOURCE, null, "10.0.0.9")));

        runner.test("reset by source without source", () -> assertBytes("reset by source",
                new byte[] {4, 0},
                Packet.prepare(MutexAction.RESET_BY_SOURCE, null, null)));

        runner.test("try lock without source", () -> assertBytes("try lock",
                new byte[] {5, 10, 108, 111, 99, 107, 105, 110, 103, 45, 109, 101, 0},
                Packet.prepare(MutexAction.TRY_LOCK, "locking-me", null)));

        runner.test("try lock with source", () -> assertBytes("try lock",
                new byte[] {5, 3, 'k', 'e', 'y', 8, '1', '0', '.', '0', '.', '0', '.', '4'},
                Packet.prepare(MutexAction.TRY_LOCK, "key", "10.0.0.4")));

        runner.test("size prefix is the UTF-8 byte count, not the char count", () -> {
            // "café" is 4 chars but 5 UTF-8 bytes (é = C3 A9)
            assertBytes("lock café",
                    new byte[] {1, 5, 'c', 'a', 'f', (byte) 0xC3, (byte) 0xA9, 0},
                    Packet.prepare(MutexAction.LOCK, "café", null));
            // "ключ" is 4 chars but 8 UTF-8 bytes
            assertBytes("unlock ключ",
                    new byte[] {2, 8,
                            (byte) 0xD0, (byte) 0xBA, (byte) 0xD0, (byte) 0xBB,
                            (byte) 0xD1, (byte) 0x8E, (byte) 0xD1, (byte) 0x87},
                    Packet.prepare(MutexAction.UNLOCK, "ключ", null));
        });

        runner.test("127 byte key is the largest that encodes", () -> {
            String key = "k".repeat(127);
            byte[] packet = Packet.prepare(MutexAction.LOCK, key, null);
            assertBytes("first bytes", new byte[] {1, 127, 'k'}, new byte[] {packet[0], packet[1], packet[2]});
            Assert.assertTrue("length", packet.length == 1 + 1 + 127 + 1);
            assertThrows("128 bytes", IllegalArgumentException.class,
                    () -> Packet.prepare(MutexAction.LOCK, "k".repeat(128), null));
        });
    }
}
