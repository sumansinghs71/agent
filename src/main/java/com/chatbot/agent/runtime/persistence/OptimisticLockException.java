package com.chatbot.agent.runtime.persistence;

/**
 * A conditional update matched no row, meaning something else changed the record first.
 *
 * <p>Not an error condition in itself - it is the mechanism working. The caller re-reads and
 * decides. What it must never do is retry the write blindly, which would reintroduce exactly the
 * lost update the version column exists to prevent.
 */
public class OptimisticLockException extends RuntimeException {
    public OptimisticLockException(String message) {
        super(message);
    }
}
