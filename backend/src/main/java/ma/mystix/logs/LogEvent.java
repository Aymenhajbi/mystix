package ma.mystix.logs;

/** What happened, with its default level. */
public enum LogEvent {
    INVOICE_ACCEPTED(LogLevel.INFO),
    INVOICE_REPLAYED(LogLevel.INFO),
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
