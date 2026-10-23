package org.jack.backend.common.validation;

/** Frozen 1.0.0 wire formats shared by input domains. */
public final class ContractPatterns {
    private ContractPatterns() { }
    public static final String ID = "[A-Za-z0-9][A-Za-z0-9_.:-]{0,159}";
    public static final String RATIO = "(?:0(?:\\.[0-9]{0,9}[1-9])?|1)";
    public static final String DECIMAL = "(?!-0$)-?(?:0|[1-9][0-9]{0,17})(?:\\.[0-9]{0,9}[1-9])?";
    public static final String UTC_MILLIS = "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z";
}
