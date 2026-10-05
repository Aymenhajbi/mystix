# ADR-0009 — Identité du vendeur imposée par défaut, désactivable par environnement

- Statut : **Accepté** (décision du fondateur, 2026-10-05)
- Date : 2026-10-05
- Lot : 2 (règles du canonique marocain), 3 (portail, configurateur)

## Contexte
Une facture soumise par l'API sans ICE vendeur était acceptée, et elle obtenait sa clearance (simulée) : l'UBL n'avait alors pas de `PartyLegalEntity/CompanyID` vendeur (BT-30). Un ICE vendeur différent de celui de la société était refusé par une vérification codée en dur (`INVOICE_REJECTED`), qu'on ne pouvait pas désactiver. Or un intégrateur qui émet pour plusieurs entités juridiques depuis un même environnement a besoin de lever cette contrainte.

## Décision
1. **Règle par défaut, active** : le vendeur d'une facture est la société de l'environnement.
   - Un ICE vendeur absent est **complété** par l'ICE de la société.
   - Un ICE vendeur **différent** est **refusé** (`SELLER_ICE_MISMATCH`, 400, champ `seller.ice`), jamais remplacé en silence.
2. **Désactivable par environnement** : colonne `company.enforce_seller_ice` (V9, défaut `TRUE`), lue et modifiée par `GET/PUT /api/v1/companies/{id}/settings`. Seul l'environnement lui-même y a accès (en-tête `X-Mystix-Company-Id` égal à `{id}`, sinon 404). Le réglage se change dans le portail, panneau « Paramètres de l'environnement ».
3. La règle s'applique à l'intake, après les règles de flux (ADR-0008) et avant la validation des champs. La vérification codée en dur dans `InvoiceService` est supprimée : elle aurait rendu la désactivation inopérante.
4. Le test à blanc n'applique pas cette règle : les règles de mapping ne peuvent pas modifier l'ICE vendeur, et les échantillons stockés y sont déjà passés à leur réception.
5. Le portail envoie toujours l'ICE de la société, quel que soit le réglage.

## Options écartées
| Option | Pourquoi écartée |
| --- | --- |
| Remplacer tout ICE vendeur par celui de la société | Modifierait en silence un document reçu |
| Règle globale (configuration applicative) | Le besoin d'exception est propre à un environnement (intégrateur), pas à la plateforme |
| ICE vendeur obligatoire dans la requête | Casserait les émetteurs qui n'envoient pas leur propre ICE, alors que Mystix le connaît |

## Conséquences
- Contrat d'API : un ICE différent renvoie désormais `SELLER_ICE_MISMATCH`, au lieu de `INVOICE_REJECTED` ; le statut reste 400.
- Le RAW conserve la requête telle que reçue ; le canonique et l'UBL portent l'ICE complété.
- Au Lot 8, l'accès aux réglages passera par le rôle administrateur de l'environnement.
- Changer le réglage modifie le canonique d'une même requête : un renvoi après changement peut donner `INVOICE_NUMBER_CONFLICT` (comportement d'idempotence attendu, ADR-0006).
