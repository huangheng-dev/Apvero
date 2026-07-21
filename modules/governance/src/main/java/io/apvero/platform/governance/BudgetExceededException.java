package io.apvero.platform.governance;

public final class BudgetExceededException extends RuntimeException {
    public BudgetExceededException() {
        super("The execution was rejected by a budget policy.");
    }
}
