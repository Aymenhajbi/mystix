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

    SELLER_ICE_MISMATCH(HttpStatus.BAD_REQUEST, Stage.VALIDATION, false,
            new LocalizedText("L'ICE du vendeur n'est pas celui de votre société.",
                    "ICE البائع ليس ICE شركتك."),
            new LocalizedText("Retirez l'ICE vendeur (il sera repris de votre société) ou corrigez-le. Un intégrateur qui émet pour plusieurs entités peut désactiver cette règle dans les paramètres de l'environnement.",
                    "احذف ICE البائع (سيؤخذ من شركتك) أو صحّحه. يمكن للمُدمِج الذي يُصدر لعدة كيانات تعطيل هذه القاعدة في إعدادات البيئة.")),
    VAT_RATE_UNKNOWN(HttpStatus.BAD_REQUEST, Stage.VALIDATION, false,
            new LocalizedText("Un taux de TVA n'est pas en vigueur à la date d'émission de la facture.",
                    "سعر ضريبة على القيمة المضافة غير ساري في تاريخ إصدار الفاتورة."),
            new LocalizedText("Utilisez un taux en vigueur à cette date (voir le référentiel TVA). Si le référentiel est en retard, l'environnement peut désactiver ce contrôle dans ses paramètres.",
                    "استعمل سعراً سارياً في هذا التاريخ (انظر مرجع الضريبة). إذا كان المرجع متأخراً، يمكن للبيئة تعطيل هذه المراقبة في إعداداتها.")),
    INVOICE_NOT_PENDING_VALIDATION(HttpStatus.CONFLICT, Stage.API, false,
            new LocalizedText("Cette facture n'est pas en attente de validation.",
                    "هذه الفاتورة ليست في انتظار المصادقة."),
            new LocalizedText("Seule une facture antidatée en attente peut être validée ou refusée, une seule fois.",
                    "لا يمكن المصادقة أو الرفض إلا على فاتورة بتاريخ سابق في الانتظار، ومرة واحدة فقط.")),
    STOCK_INSUFFICIENT(HttpStatus.CONFLICT, Stage.API, false,
            new LocalizedText("Stock insuffisant pour appliquer ce mouvement.",
                    "المخزون غير كافٍ لتطبيق هذه الحركة."),
            new LocalizedText("Vérifiez la position de l'article : rien n'a été appliqué pour cet événement.",
                    "تحقّق من وضعية المادة: لم يُطبَّق أي شيء من هذا الحدث.")),
    STOCK_EVENT_CONFLICT(HttpStatus.CONFLICT, Stage.API, false,
            new LocalizedText("Cette clé d'idempotence a déjà servi pour un autre contenu.",
                    "مفتاح عدم التكرار هذا استُعمل لمحتوى آخر."),
            new LocalizedText("Utilisez une nouvelle clé pour un nouvel événement, ou renvoyez exactement le même contenu.",
                    "استعمل مفتاحاً جديداً لحدث جديد، أو أعد إرسال المحتوى نفسه تماماً.")),
    VAT_RATE_EXISTS(HttpStatus.CONFLICT, Stage.API, false,
            new LocalizedText("Ce taux existe déjà dans le référentiel pour cette date de début.",
                    "هذا السعر موجود مسبقاً في المرجع لتاريخ البداية هذا."),
            new LocalizedText("Modifiez la date de début ou consultez le référentiel.",
                    "غيّر تاريخ البداية أو راجع المرجع.")),
    INVOICE_RULES_VIOLATED(HttpStatus.UNPROCESSABLE_CONTENT, Stage.VALIDATION, false,
            new LocalizedText("La facture ne respecte pas les règles de la norme EN 16931.",
                    "الفاتورة لا تحترم قواعد المعيار EN 16931."),
            new LocalizedText("Consultez les règles en échec, corrigez la facture puis renvoyez-la.",
                    "راجع القواعد غير المستوفاة، صحّح الفاتورة ثم أعد إرسالها.")),

    INVOICE_NUMBER_CONFLICT(HttpStatus.CONFLICT, Stage.API, false,
            new LocalizedText("Une autre facture porte déjà ce numéro pour cette société.",
                    "توجد فاتورة أخرى بنفس الرقم لهذه الشركة."),
            new LocalizedText("Utilisez un nouveau numéro, ou renvoyez la facture d'origine à l'identique.",
                    "استخدم رقماً جديداً، أو أعد إرسال الفاتورة الأصلية دون أي تغيير.")),

    INVOICE_NOT_FOUND(HttpStatus.NOT_FOUND, Stage.API, false,
            new LocalizedText("Facture introuvable.",
                    "الفاتورة غير موجودة."),
            new LocalizedText("Vérifiez l'identifiant de la facture et la société utilisée.",
                    "تحقّق من معرّف الفاتورة ومن الشركة المستخدمة.")),

    INVOICE_SCHEMA_INVALID(HttpStatus.INTERNAL_SERVER_ERROR, Stage.GENERATION, false,
            new LocalizedText("Le document UBL produit n'est pas valide. L'erreur vient de Mystix.",
                    "مستند UBL الناتج غير صالح. الخطأ صادر عن Mystix."),
            new LocalizedText("Contactez le support en indiquant le numéro de facture.",
                    "اتصل بالدعم مع ذكر رقم الفاتورة.")),

    FLOW_NOT_FOUND(HttpStatus.NOT_FOUND, Stage.API, false,
            new LocalizedText("Flux introuvable pour cette société.",
                    "التدفق غير موجود لهذه الشركة."),
            new LocalizedText("Vérifiez l'identifiant du flux et l'environnement client choisi.",
                    "تحقّق من معرّف التدفق ومن بيئة الزبون المختارة.")),

    FLOW_NOT_ACTIVE(HttpStatus.CONFLICT, Stage.API, false,
            new LocalizedText("Aucun flux actif ne peut recevoir cette facture.",
                    "لا يوجد تدفق نشط يمكنه استقبال هذه الفاتورة."),
            new LocalizedText("Activez le flux dans le portail (environnement client > flux), puis renvoyez la facture.",
                    "فعّل التدفق في البوابة (بيئة الزبون > التدفقات)، ثم أعد إرسال الفاتورة.")),

    FLOW_OPTION_UNAVAILABLE(HttpStatus.BAD_REQUEST, Stage.API, false,
            new LocalizedText("Cette combinaison de canal et de format n'est pas encore disponible.",
                    "هذا المزيج من القناة والصيغة غير متاح بعد."),
            new LocalizedText("Choisissez une option marquée disponible ; les autres arrivent avec les lots indiqués.",
                    "اختر خياراً متاحاً؛ الخيارات الأخرى قادمة مع الدفعات المشار إليها.")),

    FLOW_NAME_TAKEN(HttpStatus.CONFLICT, Stage.API, false,
            new LocalizedText("Un flux porte déjà ce nom pour cette société.",
                    "يوجد تدفق بنفس الاسم لهذه الشركة."),
            new LocalizedText("Choisissez un autre nom.",
                    "اختر اسماً آخر.")),

    MAPPING_VERSION_NOT_FOUND(HttpStatus.NOT_FOUND, Stage.API, false,
            new LocalizedText("Version de mapping introuvable pour ce flux.",
                    "إصدار الربط غير موجود لهذا التدفق."),
            new LocalizedText("Vérifiez le numéro de version.",
                    "تحقّق من رقم الإصدار.")),

    MAPPING_NOT_EDITABLE(HttpStatus.CONFLICT, Stage.API, false,
            new LocalizedText("Seul un brouillon peut être modifié.",
                    "لا يمكن تعديل إلا المسودة."),
            new LocalizedText("Créez une nouvelle version à partir de celle-ci.",
                    "أنشئ إصداراً جديداً انطلاقاً من هذا الإصدار.")),

    MAPPING_RULES_INVALID(HttpStatus.BAD_REQUEST, Stage.MAPPING, false,
            new LocalizedText("Les règles de mapping sont invalides.",
                    "قواعد الربط غير صالحة."),
            new LocalizedText("Corrigez les règles signalées.",
                    "صحّح القواعد المشار إليها.")),

    MAPPING_NOT_TESTED(HttpStatus.CONFLICT, Stage.VALIDATION, false,
            new LocalizedText("Cette version n'a pas été testée avec ses règles actuelles.",
                    "لم يُختبر هذا الإصدار بقواعده الحالية."),
            new LocalizedText("Lancez le rejeu à blanc, puis publiez si tout est vert.",
                    "شغّل التجربة دون إرسال، ثم انشر إذا كانت كل النتائج سليمة.")),

    MAPPING_TEST_FAILED(HttpStatus.CONFLICT, Stage.VALIDATION, false,
            new LocalizedText("Le rejeu à blanc a échoué : la publication est bloquée.",
                    "فشلت التجربة دون إرسال: النشر ممنوع."),
            new LocalizedText("Corrigez les règles jusqu'à ce que toutes les factures de test passent.",
                    "صحّح القواعد إلى أن تنجح كل فواتير الاختبار.")),

    MAPPING_RULE_REJECTED(HttpStatus.UNPROCESSABLE_CONTENT, Stage.MAPPING, false,
            new LocalizedText("Une règle du flux a refusé une valeur de la facture.",
                    "رفضت قاعدة من قواعد التدفق قيمة في الفاتورة."),
            new LocalizedText("Ajoutez la valeur à la table de correspondance du flux, ou corrigez la facture.",
                    "أضف القيمة إلى جدول المطابقة في التدفق، أو صحّح الفاتورة.")),

    PARTNER_NOT_FOUND(HttpStatus.NOT_FOUND, Stage.API, false,
            new LocalizedText("Partenaire introuvable pour ce client.",
                    "الشريك غير موجود لهذا الزبون."),
            new LocalizedText("Vérifiez le partenaire choisi.",
                    "تحقّق من الشريك المختار.")),

    PARTNER_NAME_TAKEN(HttpStatus.CONFLICT, Stage.API, false,
            new LocalizedText("Un partenaire porte déjà ce nom pour ce client.",
                    "يوجد شريك بنفس الاسم لهذا الزبون."),
            new LocalizedText("Choisissez un autre nom.",
                    "اختر اسماً آخر.")),

    FLOW_NOT_EXECUTABLE(HttpStatus.CONFLICT, Stage.API, false,
            new LocalizedText("Ce flux est déclaré mais ses briques ne sont pas encore livrées : il ne peut pas être activé.",
                    "هذا التدفق مُعلن لكن مكوّناته لم تُسلَّم بعد: لا يمكن تفعيله."),
            new LocalizedText("Gardez-le en brouillon ; il pourra être activé quand les options prévues seront disponibles.",
                    "احتفظ به كمسودة؛ يمكن تفعيله عندما تصبح الخيارات المبرمجة متاحة.")),

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
