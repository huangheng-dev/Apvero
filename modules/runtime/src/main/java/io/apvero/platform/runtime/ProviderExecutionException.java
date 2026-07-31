package io.apvero.platform.runtime;

public final class ProviderExecutionException extends RuntimeException {
    private final String code;
    private final ProviderFailureDisposition disposition;

    public ProviderExecutionException(
            String code,
            ProviderFailureDisposition disposition,
            Throwable cause) {
        super(code, cause);
        this.code = code;
        this.disposition = disposition;
    }

    public ProviderExecutionException(
            String code,
            ProviderFailureDisposition disposition) {
        this(code, disposition, null);
    }

    public String code() {
        return code;
    }

    public ProviderFailureDisposition disposition() {
        return disposition;
    }
}
