package com.coolxer.securecommunication;

import java.time.Duration;

/** Per-operation execution controls. Cancellation uses thread interruption or Future.cancel. */
public final class ExecutionOptions {
    private final Duration timeout;

    private ExecutionOptions(Builder builder) {
        if (builder.timeout != null && (builder.timeout.isZero() || builder.timeout.isNegative())) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        this.timeout = builder.timeout;
    }

    public static Builder builder() { return new Builder(); }
    public static ExecutionOptions defaults() { return builder().build(); }
    public Duration getTimeout() { return timeout; }

    public static final class Builder {
        private Duration timeout;
        public Builder timeout(Duration value) { this.timeout = value; return this; }
        public ExecutionOptions build() { return new ExecutionOptions(this); }
    }
}
