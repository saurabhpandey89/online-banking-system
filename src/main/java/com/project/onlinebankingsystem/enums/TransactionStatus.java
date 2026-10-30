package com.project.onlinebankingsystem.enums;

public enum TransactionStatus {
    PENDING,
    SUCCESS,
    FAILED,
    REVERSED,
    CANCELLED;

    public boolean isTerminal() {
        return this != PENDING;
    }

    public boolean canBeCancelled() {
        return this == PENDING;
    }

    public boolean canBeReversed() {
        return this == SUCCESS;
    }
}
