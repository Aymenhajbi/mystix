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
    AUTH_REQUIRED(HttpStatus.UNAUTHORIZED, Stage.API, false,
            new LocalizedText("Connexion requise.",
                    "تسجيل الدخول مطلوب."),
            new LocalizedText("Connectez-vous pour accéder à Mystix.",
                    "سجّل الدخول للوصول إلى Mystix.")),
    AUTH_INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, Stage.API, false,
            new LocalizedText("Adresse e-mail ou mot de passe incorrect.",
                    "البريد الإلكتروني أو كلمة المرور غير صحيحة."),
            new LocalizedText("Vérifiez vos identifiants. Après plusieurs échecs, le compte est verrouillé quelques minutes.",
                    "تحقّق من بيانات الدخول. بعد عدة محاولات فاشلة يُقفل الحساب لبضع دقائق.")),
    AUTH_LOCKED(HttpStatus.LOCKED, Stage.API, false,
            new LocalizedText("Compte temporairement verrouillé après plusieurs échecs.",
                    "الحساب مقفل مؤقتاً بعد عدة محاولات فاشلة."),
            new LocalizedText("Réessayez dans quelques minutes.",
                    "أعد المحاولة بعد بضع دقائق.")),
    AUTH_SESSION_INVALID(HttpStatus.UNAUTHORIZED, Stage.API, false,
            new LocalizedText("Votre session a expiré.",
                    "انتهت صلاحية جلستك."),
            new LocalizedText("Reconnectez-vous.",
                    "سجّل الدخول من جديد.")),
    AUTH_FORBIDDEN(HttpStatus.FORBIDDEN, Stage.API, false,
            new LocalizedText("Accès non autorisé pour ce compte.",
                    "وصول غير مسموح لهذا الحساب."),
            new LocalizedText("Demandez à un administrateur de vous donner accès à cet environnement.",
                    "اطلب من مسؤول منحك الوصول إلى هذه البيئة.")),
    AUTH_SETUP_DONE(HttpStatus.CONFLICT, Stage.API, false,
            new LocalizedText("Le premier administrateur existe déjà.",
                    "المسؤول الأول موجود مسبقاً."),
            new LocalizedText("Connectez-vous avec un compte existant.",
                    "سجّل الدخول بحساب موجود.")),
    AUTH_EMAIL_TAKEN(HttpStatus.CONFLICT, Stage.API, false,
            new LocalizedText("Cette adresse e-mail est déjà utilisée.",
                    "هذا البريد الإلكتروني مستعمل مسبقاً."),
            new LocalizedText("Utilisez une autre adresse.",
                    "استعمل عنواناً آخر.")),
    EDIFACT_INVALID(HttpStatus.BAD_REQUEST, Stage.API, false,
            new LocalizedText("L'interchange EDIFACT est mal formée.",
                    "تبادل EDIFACT غير سليم البنية."),
            new LocalizedText("Corrigez le segment indiqué (UNB, UNH, UNT, UNZ, séparateurs) et renvoyez l'interchange.",
                    "صحّح المقطع المشار إليه (UNB وUNH وUNT وUNZ والفواصل) وأعد إرسال التبادل.")),
    EDIFACT_MAPPING_FAILED(HttpStatus.UNPROCESSABLE_CONTENT, Stage.MAPPING, false,
            new LocalizedText("Ce message EDIFACT ne peut pas devenir un mouvement de stock.",
                    "لا يمكن تحويل رسالة EDIFACT هذه إلى حركة مخزون."),
            new LocalizedText("Vérifiez le segment indiqué : qualifiant de quantité, article, emplacement ou date.",
                    "تحقّق من المقطع المشار إليه: مؤهِّل الكمية أو المادة أو الموقع أو التاريخ.")),
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
            new LocalizedText("Aucun flux actif ne peut recevoir ce document.",
                    "لا يوجد تدفق نشط يمكنه استقبال هذه الوثيقة."),
            new LocalizedText("Activez le flux dans le portail (environnement client > flux), puis renvoyez le document.",
                    "فعّل التدفق في البوابة (بيئة الزبون > التدفقات)، ثم أعد إرسال الوثيقة.")),

    FLOW_OPTION_UNAVAILABLE(HttpStatus.BAD_REQUEST, Stage.API, false,
            new LocalizedText("Cette combinaison de canal et de format n'est pas encore disponible.",
                    "هذا المزيج من القناة والصيغة غير متاح بعد."),
            new LocalizedText("Choisissez une option marquée disponible ; les autres arrivent avec les lots indiqués.",
                    "اختر خياراً متاحاً؛ الخيارات الأخرى قادمة مع الدفعات المشار إليها.")),

    FLOW_DOCUMENT_MISMATCH(HttpStatus.CONFLICT, Stage.API, false,
            new LocalizedText("Ce flux ne porte pas ce type de document.",
                    "هذا التدفق لا يحمل هذا النوع من الوثائق."),
            new LocalizedText("Envoyez le message par le flux de son type (DESADV, RECADV, ORDERS ou INVRPT).",
                    "أرسل الرسالة عبر تدفق نوعها (DESADV أو RECADV أو ORDERS أو INVRPT).")),

    FLOW_AMBIGUOUS(HttpStatus.CONFLICT, Stage.API, false,
            new LocalizedText("Plusieurs flux actifs peuvent recevoir ce document.",
                    "عدة تدفقات نشطة يمكنها استقبال هذه الوثيقة."),
            new LocalizedText("Nommez le flux avec l'en-tête X-Mystix-Flow-Id.",
                    "حدّد التدفق بواسطة الترويسة X-Mystix-Flow-Id.")),

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

    QUEUE_JOB_NOT_FOUND(HttpStatus.NOT_FOUND, Stage.API, false,
            new LocalizedText("Traitement introuvable pour ce client.",
                    "المعالجة غير موجودة لهذا الزبون."),
            new LocalizedText("Vérifiez l'identifiant du traitement.",
                    "تحقّق من معرّف المعالجة.")),

    QUEUE_JOB_NOT_DEAD(HttpStatus.CONFLICT, Stage.API, false,
            new LocalizedText("Seul un traitement en échec définitif peut être rejoué.",
                    "لا يمكن إعادة تشغيل إلا معالجة فشلت نهائياً."),
            new LocalizedText("Attendez la fin des reprises automatiques, puis rejouez si le traitement reste en échec.",
                    "انتظر انتهاء المحاولات التلقائية، ثم أعد التشغيل إذا بقيت المعالجة فاشلة.")),

    QUEUE_QUOTA_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, Stage.API, true,
            new LocalizedText("Trop de traitements en attente sur ce flux : la demande n'a pas été enregistrée.",
                    "توجد معالجات كثيرة قيد الانتظار في هذا التدفق: لم يُسجَّل الطلب."),
            new LocalizedText("Renvoyez la même demande après le délai indiqué par l'en-tête Retry-After.",
                    "أعد إرسال الطلب نفسه بعد المهلة المحددة في الترويسة Retry-After.")),

    CERTIFICATE_INVALID(HttpStatus.BAD_REQUEST, Stage.API, false,
            new LocalizedText("Ce fichier n'est pas un certificat X.509 lisible.",
                    "هذا الملف ليس شهادة X.509 قابلة للقراءة."),
            new LocalizedText("Fournissez le certificat public au format PEM (-----BEGIN CERTIFICATE-----) ou DER.",
                    "قدّم الشهادة العامة بصيغة PEM (-----BEGIN CERTIFICATE-----) أو DER.")),

    CERTIFICATE_PRIVATE_KEY_REFUSED(HttpStatus.BAD_REQUEST, Stage.API, false,
            new LocalizedText("Ce fichier contient une clé privée : il a été refusé et n'a pas été enregistré.",
                    "يحتوي هذا الملف على مفتاح خاص: تم رفضه ولم يُحفظ."),
            new LocalizedText("Ne déposez que le certificat public. La clé privée reste dans le coffre de secrets.",
                    "لا تودع إلا الشهادة العامة. يبقى المفتاح الخاص في خزنة الأسرار.")),

    CERTIFICATE_DUPLICATE(HttpStatus.CONFLICT, Stage.API, false,
            new LocalizedText("Ce certificat est déjà rattaché à ce canal.",
                    "هذه الشهادة مرتبطة بالفعل بهذه القناة."),
            new LocalizedText("Aucune action nécessaire.",
                    "لا حاجة لأي إجراء.")),

    CERTIFICATE_NOT_FOUND(HttpStatus.NOT_FOUND, Stage.API, false,
            new LocalizedText("Certificat introuvable sur ce canal.",
                    "الشهادة غير موجودة في هذه القناة."),
            new LocalizedText("Rechargez la fiche du canal.",
                    "أعد تحميل بطاقة القناة.")),

    CHANNEL_NOT_FOUND(HttpStatus.NOT_FOUND, Stage.API, false,
            new LocalizedText("Canal de communication introuvable pour ce client.",
                    "قناة الاتصال غير موجودة لهذا الزبون."),
            new LocalizedText("Vérifiez le canal choisi ; il a peut-être été supprimé.",
                    "تحقّق من القناة المختارة؛ ربما تم حذفها.")),

    CHANNEL_NAME_TAKEN(HttpStatus.CONFLICT, Stage.API, false,
            new LocalizedText("Un canal porte déjà ce nom pour ce client.",
                    "توجد قناة بنفس الاسم لهذا الزبون."),
            new LocalizedText("Choisissez un autre nom.",
                    "اختر اسماً آخر.")),

    CHANNEL_INCOMPLETE(HttpStatus.CONFLICT, Stage.API, false,
            new LocalizedText("Ce canal ne peut pas être actif sans référence d'identifiants.",
                    "لا يمكن تفعيل هذه القناة دون مرجع بيانات الاعتماد."),
            new LocalizedText("Renseignez l'alias du secret (mot de passe, clé ou certificat) dans le coffre, ou gardez le canal en brouillon.",
                    "أدخل اسم السر (كلمة المرور أو المفتاح أو الشهادة) في الخزنة، أو احتفظ بالقناة كمسودة.")),

    FLOW_NOT_EXECUTABLE(HttpStatus.CONFLICT, Stage.API, false,
            new LocalizedText("Ce flux est déclaré mais ses briques ne sont pas encore livrées : il ne peut pas être activé.",
                    "هذا التدفق مُعلن لكن مكوّناته لم تُسلَّم بعد: لا يمكن تفعيله."),
            new LocalizedText("Gardez-le en brouillon ; il pourra être activé quand les options prévues seront disponibles.",
                    "احتفظ به كمسودة؛ يمكن تفعيله عندما تصبح الخيارات المبرمجة متاحة.")),

    ATELYS_NOT_FOUND(HttpStatus.NOT_FOUND, Stage.API, false,
            new LocalizedText("Élément introuvable dans cet environnement.",
                    "العنصر غير موجود في هذه البيئة."),
            new LocalizedText("Vérifiez la référence (produit, site, emplacement, lot, nomenclature ou ordre de fabrication).",
                    "تحقّق من المرجع (منتج، موقع، مكان تخزين، دفعة، قائمة مكونات أو أمر تصنيع).")),

    ATELYS_CODE_TAKEN(HttpStatus.CONFLICT, Stage.API, false,
            new LocalizedText("Ce code ou ce numéro est déjà utilisé.",
                    "هذا الرمز أو الرقم مستعمل مسبقاً."),
            new LocalizedText("Choisissez un autre code, ou ouvrez l'élément existant.",
                    "اختر رمزاً آخر أو افتح العنصر الموجود.")),

    ATELYS_OPERATION_CONFLICT(HttpStatus.CONFLICT, Stage.API, false,
            new LocalizedText("Cet identifiant d'opération a déjà servi pour un autre contenu.",
                    "معرّف العملية هذا استُعمل لمحتوى آخر."),
            new LocalizedText("Renvoyez exactement la même opération, ou créez-en une nouvelle avec un nouvel identifiant.",
                    "أعد إرسال العملية نفسها تماماً، أو أنشئ عملية جديدة بمعرّف جديد.")),

    DEVICE_ADAPTER_UNKNOWN(HttpStatus.BAD_REQUEST, Stage.API, false,
            new LocalizedText("Cette méthode de scan n'existe pas dans l'application mobile.",
                    "طريقة المسح هذه غير موجودة في التطبيق المحمول."),
            new LocalizedText("Choisissez une méthode de la liste : clavier ou Data Intent Honeywell.",
                    "اختر طريقة من القائمة: لوحة المفاتيح أو Data Intent من Honeywell.")),

    GTIN_INVALID(HttpStatus.BAD_REQUEST, Stage.API, false,
            new LocalizedText("Ce code-barres n'est pas un GTIN valide (longueur ou clé de contrôle).",
                    "هذا الرمز الشريطي ليس GTIN صالحاً (الطول أو رقم التحقق)."),
            new LocalizedText("Saisissez ou scannez le GTIN à 8, 12, 13 ou 14 chiffres imprimé sur le produit.",
                    "أدخل أو امسح رمز GTIN المكوّن من 8 أو 12 أو 13 أو 14 رقماً المطبوع على المنتج.")),

    UNIT_INCOMPATIBLE(HttpStatus.BAD_REQUEST, Stage.API, false,
            new LocalizedText("Cette unité n'est pas convertible dans l'unité de stock du produit.",
                    "هذه الوحدة غير قابلة للتحويل إلى وحدة مخزون المنتج."),
            new LocalizedText("Saisissez la quantité dans l'unité de stock, ou déclarez la conversion du produit.",
                    "أدخل الكمية بوحدة المخزون، أو صرّح بمعامل تحويل المنتج.")),

    LOT_NOT_RELEASED(HttpStatus.CONFLICT, Stage.API, false,
            new LocalizedText("Ce lot n'est pas libéré : il ne peut être ni consommé ni expédié.",
                    "هذه الدفعة غير مُفرج عنها: لا يمكن استهلاكها ولا شحنها."),
            new LocalizedText("Faites libérer le lot par le contrôle qualité, ou choisissez un autre lot.",
                    "اطلب الإفراج عن الدفعة من مراقبة الجودة، أو اختر دفعة أخرى.")),

    STOCK_NOT_AVAILABLE(HttpStatus.CONFLICT, Stage.API, false,
            new LocalizedText("Stock disponible insuffisant (réservations et lots bloqués déduits).",
                    "المخزون المتاح غير كافٍ (بعد خصم الحجوزات والدفعات المحظورة)."),
            new LocalizedText("Vérifiez le stock disponible du lot et de l'emplacement : rien n'a été enregistré.",
                    "تحقّق من المخزون المتاح للدفعة ولمكان التخزين: لم يُسجَّل أي شيء.")),

    STATUS_TRANSITION_INVALID(HttpStatus.CONFLICT, Stage.API, false,
            new LocalizedText("Cette action n'est pas possible dans l'état actuel.",
                    "هذا الإجراء غير ممكن في الحالة الحالية."),
            new LocalizedText("Rechargez la page : l'élément a peut-être déjà changé d'état.",
                    "أعد تحميل الصفحة: ربما تغيّرت حالة العنصر.")),

    BOM_NOT_VALIDATED(HttpStatus.CONFLICT, Stage.API, false,
            new LocalizedText("La nomenclature de cet ordre n'est pas validée.",
                    "قائمة مكونات هذا الأمر غير مصادق عليها."),
            new LocalizedText("Validez la nomenclature avant de lancer l'ordre de fabrication.",
                    "صادق على قائمة المكونات قبل إطلاق أمر التصنيع.")),

    BOM_INVALID(HttpStatus.BAD_REQUEST, Stage.API, false,
            new LocalizedText("La nomenclature est incomplète ou incohérente.",
                    "قائمة المكونات ناقصة أو غير متّسقة."),
            new LocalizedText("Ajoutez au moins un composant, différent du produit fabriqué, avec une quantité positive.",
                    "أضف مكوناً واحداً على الأقل، مختلفاً عن المنتج المصنَّع، بكمية موجبة.")),

    REPLAY_CONFLICT(HttpStatus.CONFLICT, Stage.API, false,
            new LocalizedText("Cette clé a déjà servi pour une autre déclaration.",
                    "هذا المفتاح استُعمل لتصريح آخر."),
            new LocalizedText("Une déclaration renvoyée doit être identique ; pour une nouvelle déclaration, utilisez une nouvelle clé.",
                    "يجب أن يكون التصريح المُعاد مطابقاً؛ لتصريح جديد استعمل مفتاحاً جديداً.")),

    JUSTIFICATION_REQUIRED(HttpStatus.BAD_REQUEST, Stage.API, false,
            new LocalizedText("Un motif est obligatoire pour cet écart.",
                    "السبب إلزامي لهذا الفارق."),
            new LocalizedText("Indiquez le motif de l'écart, puis renvoyez.",
                    "أدخل سبب الفارق ثم أعد الإرسال.")),

    CHANNEL_IN_USE(HttpStatus.CONFLICT, Stage.API, false,
            new LocalizedText("Ce canal est utilisé par au moins un listener : il ne peut pas être supprimé.",
                    "هذه القناة مستعملة من طرف مستمع واحد على الأقل: لا يمكن حذفها."),
            new LocalizedText("Supprimez ou rattachez ailleurs les listeners du canal, ou désactivez le canal.",
                    "احذف مستمعي القناة أو اربطهم بقناة أخرى، أو عطّل القناة.")),

    ENTRY_POINT_NOT_FOUND(HttpStatus.NOT_FOUND, Stage.API, false,
            new LocalizedText("Point d'entrée introuvable pour cet environnement.",
                    "نقطة الدخول غير موجودة في هذه البيئة."),
            new LocalizedText("Vérifiez le point d'entrée choisi.",
                    "تحقّق من نقطة الدخول المختارة.")),

    ENTRY_POINT_CODE_TAKEN(HttpStatus.CONFLICT, Stage.API, false,
            new LocalizedText("Un point d'entrée porte déjà ce code dans cet environnement.",
                    "توجد نقطة دخول بنفس الرمز في هذه البيئة."),
            new LocalizedText("Choisissez un autre code.",
                    "اختر رمزاً آخر.")),

    LISTENER_NOT_FOUND(HttpStatus.NOT_FOUND, Stage.API, false,
            new LocalizedText("Listener introuvable pour cet environnement.",
                    "المستمع غير موجود في هذه البيئة."),
            new LocalizedText("Vérifiez le listener choisi ; il a peut-être été supprimé.",
                    "تحقّق من المستمع المختار؛ ربما تم حذفه.")),

    LISTENER_NAME_TAKEN(HttpStatus.CONFLICT, Stage.API, false,
            new LocalizedText("Un listener porte déjà ce nom dans cet environnement.",
                    "يوجد مستمع بنفس الاسم في هذه البيئة."),
            new LocalizedText("Choisissez un autre nom.",
                    "اختر اسماً آخر.")),

    LISTENER_KIND_UNAVAILABLE(HttpStatus.BAD_REQUEST, Stage.API, false,
            new LocalizedText("Ce type de listener n'est pas encore disponible : aucun moteur ne sait l'exécuter.",
                    "هذا النوع من المستمعات غير متاح بعد: لا يوجد محرك قادر على تشغيله."),
            new LocalizedText("Utilisez un listener HTTP entrant ; SFTP et AS2 arrivent dans un prochain lot.",
                    "استعمل مستمع HTTP وارداً؛ SFTP وAS2 في دفعة قادمة.")),

    LISTENER_NOT_READY(HttpStatus.CONFLICT, Stage.API, false,
            new LocalizedText("Le listener ne peut pas démarrer : des préconditions ne sont pas remplies.",
                    "لا يمكن تشغيل المستمع: بعض الشروط المسبقة غير مستوفاة."),
            new LocalizedText("Corrigez les points signalés (canal actif, point d'entrée, flux, clé d'API), puis relancez.",
                    "صحّح النقاط المشار إليها (قناة نشطة، نقطة دخول، تدفق، مفتاح API)، ثم أعد التشغيل.")),

    LISTENER_DISABLED(HttpStatus.CONFLICT, Stage.API, false,
            new LocalizedText("Ce listener est désactivé.",
                    "هذا المستمع معطّل."),
            new LocalizedText("Réactivez-le avant de le démarrer.",
                    "أعد تفعيله قبل تشغيله.")),

    LISTENER_START_FAILED(HttpStatus.BAD_GATEWAY, Stage.API, true,
            new LocalizedText("Le listener n'a pas démarré : son contrôle de santé a échoué.",
                    "لم يبدأ المستمع: فشل فحص سلامته."),
            new LocalizedText("Consultez le diagnostic du listener, corrigez la cause, puis relancez.",
                    "راجع تشخيص المستمع، صحّح السبب، ثم أعد التشغيل.")),

    LISTENER_POLL_FAILED(HttpStatus.BAD_GATEWAY, Stage.API, true,
            new LocalizedText("La scrutation du serveur distant a échoué.",
                    "فشل استطلاع الخادم البعيد."),
            new LocalizedText("Consultez le diagnostic du listener (connexion, clé du serveur, identifiants, répertoire).",
                    "راجع تشخيص المستمع (الاتصال، مفتاح الخادم، بيانات الدخول، المجلد).")),

    LISTENER_NOT_RUNNING(HttpStatus.SERVICE_UNAVAILABLE, Stage.API, true,
            new LocalizedText("Ce point de réception n'est pas en service.",
                    "نقطة الاستقبال هذه ليست في الخدمة."),
            new LocalizedText("Réessayez plus tard ou contactez l'administrateur de l'environnement.",
                    "أعد المحاولة لاحقاً أو اتصل بمسؤول البيئة.")),

    INBOUND_AUTH_FAILED(HttpStatus.UNAUTHORIZED, Stage.API, false,
            new LocalizedText("Clé d'API absente ou invalide.",
                    "مفتاح API مفقود أو غير صالح."),
            new LocalizedText("Envoyez la clé fournie par Mystix dans l'en-tête X-Mystix-Api-Key.",
                    "أرسل المفتاح الذي قدّمته Mystix في الترويسة X-Mystix-Api-Key.")),

    INBOUND_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, Stage.API, false,
            new LocalizedText("Le message dépasse la taille autorisée par ce point de réception.",
                    "تتجاوز الرسالة الحجم المسموح به في نقطة الاستقبال هذه."),
            new LocalizedText("Découpez l'envoi ou demandez une limite plus haute.",
                    "قسّم الإرسال أو اطلب حداً أعلى.")),

    INBOUND_UNSUPPORTED_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, Stage.API, false,
            new LocalizedText("Ce point de réception n'accepte pas ce type de contenu.",
                    "نقطة الاستقبال هذه لا تقبل هذا النوع من المحتوى."),
            new LocalizedText("Envoyez le format attendu par le point d'entrée (Content-Type: application/json).",
                    "أرسل الصيغة المنتظرة في نقطة الدخول (Content-Type: application/json).")),

    AS2_NOT_READY(HttpStatus.CONFLICT, Stage.API, false,
            new LocalizedText("Ce canal ne peut pas émettre en AS2 : des éléments manquent.",
                    "لا يمكن لهذه القناة الإرسال عبر AS2: تنقص بعض العناصر."),
            new LocalizedText("Complétez le canal (URL du partenaire, certificats, clé privée), puis réessayez.",
                    "أكمل القناة (عنوان الشريك، الشهادات، المفتاح الخاص)، ثم أعد المحاولة.")),

    AS2_SEND_FAILED(HttpStatus.BAD_GATEWAY, Stage.API, true,
            new LocalizedText("L'envoi AS2 vers le partenaire a échoué.",
                    "فشل الإرسال عبر AS2 إلى الشريك."),
            new LocalizedText("Vérifiez l'URL du partenaire et son accessibilité, puis renvoyez.",
                    "تحقّق من عنوان الشريك وإمكانية الوصول إليه، ثم أعد الإرسال.")),

    STORAGE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, Stage.API, true,
            new LocalizedText("Le stockage des documents est momentanément indisponible : rien n'a été enregistré.",
                    "تخزين الوثائق غير متاح مؤقتاً: لم يُسجَّل أي شيء."),
            new LocalizedText("Renvoyez la même demande dans quelques instants.",
                    "أعد إرسال الطلب نفسه بعد لحظات.")),

    STORAGE_CONFLICT(HttpStatus.CONFLICT, Stage.API, false,
            new LocalizedText("Un document archivé ne peut pas être remplacé.",
                    "لا يمكن استبدال وثيقة مؤرشفة."),
            new LocalizedText("Contactez le support en indiquant l'heure de l'erreur.",
                    "اتصل بالدعم مع ذكر وقت حدوث الخطأ.")),

    STORAGE_INTEGRITY(HttpStatus.INTERNAL_SERVER_ERROR, Stage.API, false,
            new LocalizedText("Le document archivé ne correspond plus à son empreinte : il n'est pas servi.",
                    "الوثيقة المؤرشفة لم تعد تطابق بصمتها: لن تُقدَّم."),
            new LocalizedText("Contactez le support en indiquant l'heure de l'erreur.",
                    "اتصل بالدعم مع ذكر وقت حدوث الخطأ.")),

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
