# ADR-0019 : Atelys Device Hub — détection du terminal et intégrations de scan

- Statut : **proposé** (lot 12, 19/10/2026). Lot A livré dans `mobile/device-hub-core`, lot B en partie (`mobile/app`), lots C à E prévus.
- Contexte : ADR-0018 (pilote mobile WMS), ADR-0013 (authentification), ADR-0014 (Atelys WMS).

## 1. Audit (19/10/2026, branche `feat/12-device-hub`, issue de `feat/12-mobile-receipt`)

Constaté dans le dépôt et sur le poste, pas déduit des noms :

- `mobile/` n'existait pas : aucun code Android, aucune version Kotlin, AGP ou Gradle à reprendre.
- Poste : JDK 21.0.12 ; **pas de SDK Android, pas d'`adb`, pas de Gradle installé**. Gradle 8.14.3 est maintenant
  fourni par le wrapper de `mobile/` (somme SHA-256 de la distribution vérifiée).
- Backend : V27 (`wms_receipt.idempotency_key`) est la dernière migration ; elle n'est pas encore appliquée hors
  tests (la branche n'est pas fusionnée).
- Droits : `Access` (`auth/Access.java`) exige un opérateur plateforme ou un `ADMIN` de la société pour les actions
  sensibles ; c'est le contrôle à réutiliser pour l'administration des terminaux.
- Administration : le portail Atelys a une page `atelys/settings` ; aucune notion d'appareil, de terminal ou de
  profil n'existe côté backend ni côté frontend.
- Honeywell : le CK67 existe (terminal à clavier sous Android 14 selon les revendeurs, plateforme Mobility Edge ;
  sources ci-dessous). Le SDK *Data Collection* (`com.honeywell.aidc`) n'est pas sur Maven Central ; sa
  distribution et sa licence restent **à confirmer**. Le *Data Intent* est un réglage du profil scanner du terminal
  qui diffuse chaque lecture avec une action choisie par l'application ; noms exacts des extras **non vérifiés**.

| EXISTANT | À ÉTENDRE | MANQUANT | RISQUE | TEST DE VALIDATION |
| --- | --- | --- | --- | --- |
| `POST /wms/receipts` idempotent (V27) | — (le scanner n'y touche pas) | — | Aucun : le Device Hub n'appelle pas ce point | `AtelysApiTests` inchangé (7/7) |
| Session Bearer, `X-Mystix-Company-Id` | Identifier l'installation dans l'audit | Table des installations | Jeton terminal = jeton utilisateur (12 h / 2 h d'inactivité) | Lot D : test d'isolation société |
| `Access` : opérateur ou `ADMIN` | Droits sur profils et appareils | Endpoints d'administration | Modifier un profil change la recommandation de toute une flotte | Lot D : 403 pour un non-admin |
| Page `atelys/settings` | Rubrique « Terminaux et intégrations » | Écrans liste et fiche appareil | — | Lot D : lint, typecheck, build |
| — | — | Application Android | **SDK Android absent** : rien d'Android ne compile ici | Lot B après installation |
| — | — | Adaptateur Honeywell SDK | Artefact et licence non disponibles | Bloqué tant que le SDK n'est pas fourni |
| — | — | Recette sur CK67 | Aucun terminal disponible | Plan du §5, sur matériel réel |

## 2. Architecture

`mobile/device-hub-core` est du Kotlin/JVM pur, sans Android : toute la décision y vit et se teste sur le poste.
L'application Android (lot B) l'embarque et n'ajoute que la lecture de `android.os.Build`, la réception des
intents, la capture des touches et la persistance.

- `DeviceDescriptor` : ce qu'Android déclare (fabricant, marque, modèle, produit, version, API, build, version
  d'Atelys) et un `installationId` aléatoire. Pas d'IMEI, pas de numéro de série.
- `DeviceCapability` : capacités **détectées**, avec leur preuve ; une capacité absente est inconnue.
- `ScannerAdapter` : contrat commun (vérification de compatibilité pure, `open` qui réserve le matériel,
  `ScannerSession.close` idempotent, erreurs `ScannerErrorCode`). Chaque adaptateur déclare fabricants, constantes
  connues, niveau de confiance et terminaux réellement testés (liste vide aujourd'hui).
- `ScannerAdapterRegistry` : seulement les adaptateurs compilés dans la version signée ; rien n'est téléchargé.
- `IntegrationResolver` : déterministe, sans effet de bord ; renvoie état, adaptateur recommandé, confiance, raisons
  codées, prérequis, alternatives, test physique requis ou non, et une `pendingRecommendation` quand un profil plus
  récent diffère de la configuration validée (elle est montrée, jamais appliquée seule).
- `ValidatedConfiguration` : la configuration activée sur l'installation. Le constructeur refuse un test échoué ou
  simulé.
- `ScannerController` : une seule session ouverte, aucun double enregistrement, événements d'une session fermée
  ignorés, changement d'adaptateur refusé pendant une opération WMS (`TransactionGuard`), retour à l'adaptateur
  précédent si le nouveau ne s'ouvre pas. `ScanDeduplicator` écarte la seconde livraison d'une même lecture sans
  écarter deux lectures de la même étiquette.
- `KeyboardWedgeDecoder` : sépare les rafales du scanner de la frappe manuelle ; les caractères tapés à la main sont
  rendus au champ.
- `ScannerDiagnosticService` : test de lecture avec délai ; libère d'abord la session WMS ; ne garde que la longueur
  et la symbologie ; un résultat simulé ne produit jamais de configuration.

Règles de résolution, dans l'ordre :

1. API Android inférieure au minimum → `DEVICE_UNSUPPORTED`.
2. Configuration validée sur cette installation et ce modèle, adaptateur présent et non incompatible →
   `AUTO_SELECTED`.
3. Service de scan signalé indisponible → `SCANNER_UNAVAILABLE`.
4. Aucun adaptateur possible → `DEVICE_UNSUPPORTED`.
5. Profil validé qui désigne le meilleur candidat → `CONFIGURATION_REQUIRED` s'il demande un réglage, sinon
   `DIAGNOSTIC_REQUIRED`.
6. Tous les candidats demandent un réglage → `CONFIGURATION_REQUIRED`.
7. Sinon → `USER_SELECTION_REQUIRED`.

Hors `AUTO_SELECTED`, un test physique est toujours demandé.

Profils : portée installation > modèle > fabricant, puis validé avant expérimental, puis version la plus haute ;
un profil retiré est ignoré et signalé.

## 3. Lot A — livré le 19/10/2026

- Fichiers : `mobile/settings.gradle.kts`, `mobile/build.gradle.kts`, wrapper Gradle 8.14.3,
  `mobile/device-hub-core/**` (Kotlin 2.2.0, JDK 21, bytecode 17).
- Adaptateurs du cœur : `KeyboardWedgeAdapterBase` (confiance faible, toujours testé) et
  `HoneywellDataIntentAdapterBase` (Honeywell seulement, `NEEDS_CONFIGURATION` tant qu'une lecture ne l'a pas
  prouvé). Leur `open` sera écrit côté Android au lot B. Pas d'adaptateur SDK Honeywell : aucune classe imitant le
  SDK.
- Tests : 37 tests unitaires, 0 échec (`IntegrationResolverTest` 18, `ScannerControllerTest` 9,
  `KeyboardWedgeDecoderTest` 6, `ScannerDiagnosticServiceTest` 4). Ils utilisent des adaptateurs factices, dont
  chaque lecture est marquée simulée. **Ce ne sont pas des tests matériels.**
- Non livré du lot A : la détection Android réelle et l'écran de diagnostic (SDK Android requis).

## 3 bis. Lot B — livré en partie le 19/10/2026

- SDK Android installé sur le poste dans `C:devandroid-sdk` (outils en ligne de commande, plateforme 36, build-tools 35
  et 36, platform-tools), licences acceptées à la demande du fondateur. AGP 8.13.2, compileSdk/targetSdk 36,
  minSdk 26. Aucune dépendance Android ajoutée (pas d'AndroidX).
- `mobile/app` : `AndroidDeviceDetector` (`android.os.Build` et identifiant d'installation aléatoire),
  `HoneywellDataIntentAdapter` (récepteur enregistré pendant la session, exporté car émis par le service scanner),
  `KeyboardWedgeAdapter`, `PreferencesDeviceProfileRepository` (configuration validée gardée hors réseau),
  `DiagnosticActivity` (fr, ar) : terminal, décision et raisons, test par adaptateur, réinitialisation.
- Noms des extras Data Intent (`data`, `codeId`, `aimId`, `timestamp`) tirés d'exemples tiers :
  `TODO(HW-SPEC)`, à vérifier sur un CK67 ; l'adaptateur reste expérimental jusque-là.
- Vérifié : `./gradlew build` → APK debug et release (non signée), lint sans erreur, 42 tests unitaires, 0 échec.
- Non fait : écran de réception mobile ; exécution sur émulateur ; **aucun essai sur terminal Honeywell**.

## 4. Lots suivants (prévus)

- B (reste) : écran de réception via `POST /wms/receipts` avec `Idempotency-Key`, connexion, file d'opérations.
- C : choix guidé, persistance locale (Room ou DataStore), cache des profils, transition contrôlée.
- D : nouvelle migration (installations, profils, diagnostics), endpoints protégés par `Access`, audit, rubrique
  « Terminaux et intégrations » dans `atelys/settings`.
- E : régression, documentation, recette matérielle.

## 5. Recette matérielle à faire sur un CK67 réel

Version Android et numéro de build ; lecture par chaque mécanisme réellement configuré (Data Intent, clavier) ;
rafales de lectures rapides ; même étiquette lue deux fois ; arrêt et reprise de l'application ; changement
d'écran pendant une lecture ; service de scan désactivé ; absence de réseau ; réception complète jusqu'à la
confirmation. Chaque résultat est noté avec le terminal, la date et la version d'Atelys dans
`AdapterSupport.testedOnHardware`.

## Sources

- CK67, Android 14 et Mobility Edge : [Atlas RFID](https://atlasrfidstore.com/honeywell-ck67-mobile-computer),
  [ERS](https://www.ers-online.co.uk/p13616/honeywell-ck67-rugged-keypad-android-mobile-computer?page=5),
  [Jarltech](https://www.jarltech.com/en/honeywell-ck67) (revendeurs, pas Honeywell).
- Data Intent dans le profil scanner : [fulfillmenttools](https://docs.fulfillmenttools.com/documentation/apps/technical-requirements/honeywell-hardware-scanner-configuration)
  (documentation d'un éditeur tiers, à recouper avec Honeywell).
