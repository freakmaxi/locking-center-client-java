package com.freakmaxi.lockingcenter;

/**
 * The request actions of the locking-center wire protocol. The code is the
 * first byte of every request.
 */
enum MutexAction {
    LOCK(1),
    UNLOCK(2),
    RESET_BY_KEY(3),
    RESET_BY_SOURCE(4),
    TRY_LOCK(5);

    private final int code;

    MutexAction(int code) {
        this.code = code;
    }

    int code() {
        return code;
    }

    /** Whether a request of this action carries a key. */
    boolean hasKey() {
        return this != RESET_BY_SOURCE;
    }

    /** Whether a request of this action carries a source address. */
    boolean hasSource() {
        return this == LOCK || this == TRY_LOCK || this == RESET_BY_SOURCE;
    }
}
