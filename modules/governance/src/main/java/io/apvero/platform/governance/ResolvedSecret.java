package io.apvero.platform.governance;

import java.util.Arrays;

public final class ResolvedSecret implements AutoCloseable {
    private final char[] value;

    public ResolvedSecret(char[] value) {
        this.value = value;
    }

    public char[] value() {
        return value;
    }

    @Override
    public void close() {
        Arrays.fill(value, '\0');
    }
}
