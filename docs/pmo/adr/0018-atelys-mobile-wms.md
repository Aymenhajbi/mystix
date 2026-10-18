# ADR-0018 : Atelys Mobile WMS sur terminal Honeywell (pilote CK67)

- Statut : **proposé** (lot 12, 18/10/2026)
- Contexte : ADR-0013 (authentification), ADR-0014 (Atelys WMS)

## 1. Audit de l'existant (18/10/2026, branche `feat/12-mobile-receipt`)

Sources lues : `wms/WmsController.java`, `wms/Receipts.java`, `wms/Lots.java`, `auth/AuthController.java`,
`auth/AuthFilter.java`, `auth/AuthService.java`, `db/migration/V17__atelys_wms.sql`.

| Besoin mobile | Existant | Manque | À modifier |
| --- | --- | --- | --- |
| Connexion | `POST /api/v1/auth/login` → jeton Bearer ; `GET /auth/me` ; session 12 h, inactivité 2 h (`mystix.auth.*`) | Jeton propre au terminal (révocable par appareil) | Rien pour le pilote : on réutilise la session |
| Société active | `X-Mystix-Company-Id` validé contre la session (`AuthFilter`) | — | — |
| Référentiels | `GET /wms/sites`, `/warehouses`, `/locations?siteId=`, `/products?q=`, `/units` | Recherche d'emplacement par code exact ; pas de date de version pour détecter un référentiel périmé | Prévu : filtre `code` sur `/locations` |
| Scan produit | `GET /wms/products?q=` (SKU, nom) | **Aucun GTIN/EAN sur `wms_product`** (V17) : un code-barres fournisseur ne se résout pas | Prévu : colonne GTIN (nouvelle migration), à valider avec toi |
| Scan lot | `GET /wms/lots/scan?code=` (code public QR ou n° de lot) | — | — |
| Réception | `POST /wms/receipts` (brouillon) puis `POST /wms/receipts/{id}/confirm` (lots + mouvements en une transaction, confirmer deux fois = sans effet) | Création **non idempotente** : un renvoi créait un second brouillon | **Livré dans cet incrément** : en-tête `Idempotency-Key` |
| Transfert | `POST /wms/transfers`, `/ship`, `/receive` | Idempotence de la création | Même schéma que la réception (prochain incrément) |
| Inventaire | `POST /wms/inventories`, `PUT /{id}/counts`, `/submit`, `/approve` | Idempotence de la création ; `PUT counts` est déjà rejouable | Même schéma |
| Préparation / expédition | `POST /wms/shipments`, `/confirm`, réservations | Pas de liste de préparation par emplacement | À cadrer : je n'invente pas de flux de picking |
| Audit | `GET /wms/audit`, acteur = utilisateur de la session | Identifiant du terminal dans l'audit | Prévu |

## 2. Décisions

1. **Application Android native en Kotlin**, dans `mobile/` du même dépôt. Pas de seconde stack serveur :
   le terminal appelle l'API REST existante, sans modèle WMS dupliqué côté serveur.
2. **Scan Honeywell, sans mécanisme Zebra.** Deux voies officielles Honeywell, à confirmer sur l'appareil :
   le *Data Collection SDK* (`com.honeywell.aidc`, `AidcManager` / `BarcodeReader`) ou l'intention de données
   configurée dans les réglages du scanner. Le module `scanner` expose une interface ; une saisie clavier
   (mode wedge) sert de repli et de support pour les tests. **Non vérifié** : modèle exact « CK67 », version
   Android et version du SDK disponibles sur le terminal du pilote.
3. **File d'opérations locale** (Room) : chaque opération reçoit un UUID à sa création sur le terminal, réutilisé
   tel quel à chaque renvoi dans `Idempotency-Key`. États affichés : `EN ATTENTE` → `ENVOYÉE` → `CONFIRMÉE` ou
   `REFUSÉE`. Une opération n'est affichée « confirmée » qu'après la réponse 200/201 du serveur ; une réponse
   perdue (délai dépassé) laisse l'opération « en attente » et le renvoi rejoue la même clé.
4. **Conflits** : 409 `ATELYS_OPERATION_CONFLICT` (même clé, autre contenu) ou référentiel périmé → l'opération
   passe en `REFUSÉE` avec le message structuré du serveur ; aucun mouvement n'est forcé.

## 3. Livré dans cet incrément

- Migration `V27__wms_receipt_idempotency.sql` : colonnes `idempotency_key`, `request_sha256`, index unique
  partiel `(company_id, idempotency_key)`.
- `POST /api/v1/wms/receipts` accepte `Idempotency-Key` (optionnel, 100 caractères max) :
  201 à la création, 200 au rejeu identique (même brouillon renvoyé), 409 `ATELYS_OPERATION_CONFLICT` si le
  contenu diffère. Les renvois concurrents attendent le premier (`ON CONFLICT DO NOTHING`) puis le rejouent.
- Test `AtelysApiTests.aMobileRetryWithTheSameOperationIdCreatesOneReceipt` : rejeu, conflit, 4 renvois
  concurrents, stock compté une seule fois après confirmation.
- Effet connu : lors de renvois concurrents, les perdants consomment un numéro `REC-…` ; la numérotation peut
  donc sauter. Les numéros ne sont pas des pièces fiscales.

## 4. Prévu (non livré)

- Squelette Android (`mobile/`) : modules UI, métier, scanner, client API, base locale, file de synchronisation.
  **Bloqué** : aucun SDK Android ni Gradle sur le poste (constaté le 18/10/2026) ; installation à valider.
- Écran réception de bout en bout sur le terminal ; idempotence transferts et inventaires ; GTIN produit.
- Tests matériels sur un vrai terminal Honeywell. Aucun test simulé ne vaut preuve matérielle.
