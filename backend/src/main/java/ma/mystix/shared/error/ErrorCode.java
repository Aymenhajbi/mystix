package ma.mystix.shared.error;

import org.springframework.http.HttpStatus;

/**
 * Catalogue of structured errors returned to users. Each entry carries everything the API needs:
 * HTTP status, pipeline stage, whether a retry can succeed, and French/Arabic texts.
 */
public enum ErrorCode {

    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, Stage.API, false,
            new LocalizedText("Les données envoyées sont invalides.",
                    "البيانات المرسلة غير صالحة."),
            new LocalizedText("Corrigez les champs signalés puis renvoyez la requête.",
                    "صحّح الحقول المشار إليها ثم أعد إرسال الطلب.")),

    COMPANY_NOT_FOUND(HttpStatus.NOT_FOUND, Stage.API, false,
            new LocalizedText("Société introuvable.",
                    "الشركة غير موجودة."),
            new LocalizedText("Vérifiez l'identifiant de la société.",
                    "تحقّق من معرّف الشركة.")),

    COMPANY_ICE_ALREADY_EXISTS(HttpStatus.CONFLICT, Stage.API, false,
            new LocalizedText("Une société avec cet ICE existe déjà.",
                    "توجد شركة مسجّلة بهذا الرقم ICE."),
            new LocalizedText("Utilisez la société existante ou vérifiez l'ICE saisi.",
                    "استخدم الشركة الموجودة أو تحقّق من رقم ICE المُدخل.")),

    INVOICE_REJECTED(HttpStatus.BAD_REQUEST, Stage.MAPPING, false,
            new LocalizedText("La facture contient des données incohérentes.",
                    "تحتوي الفاتورة على بيانات غير متّسقة."),
            new LocalizedText("Corrigez les champs signalés (identifiants, montants, dates) puis renvoyez la facture.",
                    "صحّح الحقول المشار إليها (المعرّفات، المبالغ، التواريخ) ثم أعد إرسال الفاتورة.")),

    INVOICE_RULES_VIOLATED(HttpStatus.UNPROCESSABLE_CONTENT, Stage.VALIDATION, false,
            new LocalizedText("La facture ne respecte pas les règles de la norme EN 16931.",
                    "الفاتورة لا تحترم قواعد المعيار EN 16931."),
            new LocalizedText("Consultez les règles en échec, corrigez la facture puis renvoyez-la.",
                    "راجع القواعد غير المستوفاة، صحّح الفاتورة ثم أعد إرسالها.")),

    INVOICE_SCHEMA_INVALID(HttpStatus.INTERNAL_SERVER_ERROR, Stage.GENERATION, false,
            new LocalizedText("Le document UBL produit n'est pas valide. L'erreur vient de Mystix.",
                    "مستند UBL الناتج غير صالح. الخطأ صادر عن Mystix."),
            new LocalizedText("Contactez le support en indiquant le numéro de facture.",
                    "اتصل بالدعم مع ذكر رقم الفاتورة.")),

    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, Stage.API, false,
            new LocalizedText("Ressource introuvable.",
                    "المورد غير موجود."),
            new LocalizedText("Vérifiez l'adresse et la méthode de la requête.",
                    "تحقّق من عنوان الطلب وطريقته.")),

    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, Stage.API, false,
            new LocalizedText("Opération non autorisée sur cette ressource.",
                    "هذه العملية غير مسموح بها على هذا المورد."),
            new LocalizedText("Vérifiez la méthode HTTP utilisée.",
                    "تحقّق من طريقة HTTP المستخدمة.")),

    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, Stage.API, true,
            new LocalizedText("Erreur interne. Réessayez plus tard.",
                    "خطأ داخلي. أعد المحاولة لاحقاً."),
            new LocalizedText("Si le problème persiste, contactez le support en indiquant l'heure de l'erreur.",
                    "إذا استمرت المشكلة، اتصل بالدعم مع ذكر وقت حدوث الخطأ."));

    private final HttpStatus status;
    private final Stage stage;
    private final boolean retryable;
    private final LocalizedText userMessage;
    private final LocalizedText suggestedAction;

    ErrorCode(HttpStatus status, Stage stage, boolean retryable,
              LocalizedText userMessage, LocalizedText suggestedAction) {
        this.status = status;
        this.stage = stage;
        this.retryable = retryable;
        this.userMessage = userMessage;
        this.suggestedAction = suggestedAction;
    }

    public HttpStatus status() {
        return status;
    }

    public Stage stage() {
        return stage;
    }

    public boolean retryable() {
        return retryable;
    }

    public LocalizedText userMessage() {
        return userMessage;
    }

    public LocalizedText suggestedAction() {
        return suggestedAction;
    }
}
