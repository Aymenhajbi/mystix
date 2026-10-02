# ADR-0001 — Clearance DGI simulée derrière `ClearanceGateway`

- Statut : Accepté (décision déjà prise, formalisée ici)
- Date : 2026-09-29
- Lot : 1 (interface), 9 (adaptateur réel)

## Contexte
Le Maroc adopte un modèle de clearance (CTC) : la facture est validée par la plateforme DGI avant d'être légalement valable. Les spécifications techniques officielles (API, format de réponse, signature) **ne sont pas publiées** au 29/09/2026. La LF 2027 n'est pas votée.

## Décision
Toute interaction avec la DGI passe par l'interface `ClearanceGateway`. Seule une implémentation **simulée** existe. Son format de réponse est fictif et n'est jamais présenté comme officiel (UI, API, documents commerciaux). Tout paramètre fiscal est configurable et marqué `TODO(DGI-SPEC)`.

## Options écartées
| Option | Pourquoi écartée |
| --- | --- |
| Implémenter un format supposé « probable » | Invention de règles fiscales ; dette si la DGI publie autre chose |
| Attendre la publication pour construire le flux | Bloque le portail PME et le pilote Sahel |

## Implémentation (2026-10-02)
- `ma.mystix.clearance` : `ClearanceGateway`, `SimulatedClearanceGateway`. Mode `mystix.clearance.mode` (`MYSTIX_CLEARANCE_MODE`) : seul `simulated` démarre ; toute autre valeur arrête l'application au démarrage.
- Appel synchrone après stockage de la facture `VALIDATED` → `CLEARED` (référence `SIMULATED-<20 hex du SHA-256 de l'UBL>`, déterministe) ou `CLEARANCE_REJECTED` (simulé par motif configurable sur le numéro). Une panne de la passerelle laisse `VALIDATED` et trace `CLEARANCE_ERROR` ; la reprise viendra avec la file du Lot 4.
- Historique append-only `invoice_status_event` ; API : en-têtes `X-Mystix-Status`, `X-Mystix-Clearance-Reference`, `X-Mystix-Clearance-Simulated: true`, bloc `clearance.simulated` dans `GET /api/v1/invoices/{id}`.
- Le format de la référence, les motifs de refus et les statuts sont **fictifs** : `TODO(DGI-SPEC)`.

## Conséquences
- Le Lot 9 se limite à un nouvel adaptateur + tests contractuels, sans toucher au runtime.
- L'UI affiche « clearance simulée » tant que le mode est `simulated`.
