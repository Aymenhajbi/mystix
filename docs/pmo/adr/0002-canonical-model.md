# ADR-0002 — Modèle canonique versionné aligné EN 16931

- Statut : Accepté (décision issue d'AGENTS.md, formalisée ici)
- Date : 2026-09-29
- Lot : 1–2

## Contexte
Mystix reçoit IDoc, EDIFACT/EANCOM, XML stock, CSV/Excel, et produit UBL 2.1, CII, EANCOM. Des transformations directes format → format multiplient les mappings (N × M).

## Décision
Aucun format n'est transformé directement en un autre. Chaîne : `Parser → CanonicalMapper → modèle canonique versionné → Generator`. Le canonique (`Invoice`, `CreditNote`, `Order`, `DespatchAdvice`, `StockMovement`) est aligné EN 16931 (références BT-x), étendu Maroc (ICE, IF, RC) et GS1 (GLN, GTIN, SSCC, lot, DLC, poids net).

## Options écartées
| Option | Pourquoi écartée |
| --- | --- |
| XSLT direct IDoc → UBL (existant) | Conservé tel quel jusqu'aux tests de non-régression, puis remplacé par la chaîne ci-dessus |

## Conséquences
- Chaque message stocke les artefacts RAW / PARSED / CANONICAL / OUT et la version du canonique.
- Toute évolution du canonique = nouvelle version, jamais de modification silencieuse.
