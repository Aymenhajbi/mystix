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

## Conséquences
- Le Lot 9 se limite à un nouvel adaptateur + tests contractuels, sans toucher au runtime.
- L'UI affiche « clearance simulée » tant que le mode est `simulated`.
