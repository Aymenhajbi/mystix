# ADR-0006 — Stockage des factures, idempotence et contexte société

- Statut : **Accepté** (contexte société par en-tête : provisoire)
- Date : 2026-10-02
- Lot : 1 (stockage), 5 (Idempotency-Key), 8 (authentification)

## Contexte
`POST /api/v1/invoices` doit conserver chaque facture acceptée avec ses artefacts (ADR-0002), supporter les renvois (réseau instable, ERP qui rejoue), et isoler les sociétés. L'authentification n'existe pas encore.

## Décision
1. **Clé d'idempotence naturelle** : `(company_id, invoice_number)`, contrainte `UNIQUE` en base. Le numéro de facture est déjà unique par émetteur.
   - Même numéro, même contenu canonique (SHA-256 du JSON canonique déterministe) → `200`, `X-Mystix-Replayed: true`, même UBL, même identifiant.
   - Même numéro, contenu différent → `409 INVOICE_NUMBER_CONFLICT`.
   - Deux envois simultanés : la contrainte `UNIQUE` tranche, le perdant relit et applique la règle ci-dessus.
   - La comparaison porte sur le **canonique**, pas sur le JSON brut : un renvoi reformaté (espaces, fins de ligne) reste un renvoi.
2. **Artefacts immuables** `RAW` (corps reçu, octet pour octet), `CANONICAL` (JSON + version), `OUT` (UBL), avec SHA-256, en `BYTEA` jusqu'à l'ArtifactStore S3 (Lot 4).
3. **Contexte société** : en-tête `X-Mystix-Company-Id`, société existante obligatoire, ICE vendeur égal à l'ICE de la société. **Provisoire** : remplacé par la société du principal authentifié au Lot 8 (OIDC). Ne pas exposer cette API hors du poste de dev avant.
4. Toutes les requêtes filtrent par `company_id` ; une facture d'une autre société répond `404` (pas `403`, pour ne pas révéler son existence).

## Options écartées
| Option | Pourquoi écartée |
| --- | --- |
| En-tête `Idempotency-Key` seul | Ne protège pas d'un ERP qui renvoie la même facture avec une nouvelle clé ; reste prévu au Lot 5 pour les autres ressources |
| Hash du corps brut | Un renvoi reformaté serait vu comme un conflit |
| Stocker aussi les factures rejetées | Pas de numéro fiable quand le JSON est invalide ; les rejets sont journalisés. À revoir avec le cockpit |

## Conséquences
- Statut unique `VALIDATED` pour l'instant ; les statuts de clearance (simulée) viendront par une nouvelle migration.
- Tests d'isolation entre sociétés obligatoires sur chaque nouvelle requête (`InvoiceApiTests`).
