package ma.mystix.canonical;

/** BT-3 Invoice type code, UNTDID 1001 subset handled by the canonical model. */
public enum InvoiceTypeCode {

    /** 380 Commercial invoice. */
    COMMERCIAL_INVOICE("380");

    private final String code;

    InvoiceTypeCode(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
