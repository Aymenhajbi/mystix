package ma.mystix.logs;

/** What happened, with its default level. */
public enum LogEvent {
    INVOICE_ACCEPTED(LogLevel.INFO),
    INVOICE_REPLAYED(LogLevel.INFO),
    /** Issued before the day it was received: checked with the rates of its issue date, held for validation. */
    INVOICE_BACKDATED(LogLevel.WARN),
    /** Accepted without a VAT rate check: the referential has no standard rate for the issue date. */
    VAT_RATES_UNCHECKED(LogLevel.WARN),
    INVOICE_VALIDATION_APPROVED(LogLevel.INFO),
    INVOICE_VALIDATION_REJECTED(LogLevel.WARN),
    INVOICE_REJECTED(LogLevel.ERROR),
    CLEARANCE_CLEARED(LogLevel.INFO),
    CLEARANCE_REJECTED(LogLevel.ERROR),
    CLEARANCE_ERROR(LogLevel.ERROR);

    private final LogLevel level;

    LogEvent(LogLevel level) {
        this.level = level;
    }

    public LogLevel level() {
        return level;
    }
}
