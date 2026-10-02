# ADR-0007 — Environnements clients et flux d'échange

- Statut : **Accepté** (console opérateur sans authentification : provisoire)
- Date : 2026-10-02
- Lot : 3 (portail), 7 (Mapping Studio)

## Contexte
Chaque client de Mystix a ses propres flux : sources, formats, mappings et destinations différents. Le fondateur veut naviguer environnement client → type de flux → mapping, pour contrôler, modifier ou créer un flux. Jusqu'ici, un seul circuit implicite existait (API JSON → UBL 2.1).

## Décision
1. **Flux = ressource de premier niveau par société** (`exchange_flow`) : type de document, canal et format source, format et canal cible, mapping (id + version), statut `DRAFT` / `ACTIVE` / `PAUSED`.
2. **Provisionnement** : chaque nouvelle société reçoit le flux « Factures API vers UBL 2.1 » actif (événement `CompanyRegistered`). La migration V6 crée ce flux pour les sociétés existantes et y rattache leurs factures.
3. **Entrée d'une facture** : le flux nommé par `X-Mystix-Flow-Id`, sinon le premier flux API actif de la société. Un flux en brouillon ou en pause refuse (`FLOW_NOT_ACTIVE`).
4. **Catalogue honnête** : les options sont marquées disponibles ou prévues (avec le lot). Un flux ne peut utiliser que des options disponibles ; le mapping est choisi d'après le couple source/cible parmi les mappings implémentés.
5. **Console opérateur** : `GET /api/v1/admin/environments` (toutes sociétés, lecture seule) n'existe que si `mystix.admin.enabled=true`, vrai dans le profil `dev` seulement. Le portail choisit l'environnement actif par un cookie, en repli sur `MYSTIX_PORTAL_COMPANY_ID`.

## Options écartées
| Option | Pourquoi écartée |
| --- | --- |
| Flux implicite déduit du format reçu | Ne permet ni plusieurs flux par client, ni la pause d'un flux |
| Permettre des flux avec options prévues | Promettrait un circuit qui ne s'exécute pas |
| Console opérateur toujours active | Lecture inter-sociétés sans authentification |

## Conséquences
- L'édition de mapping (palier 3) se fera par flux : nouvelle version de mapping, publiée sur un flux, bloquée si une fixture échoue.
- Au Lot 8, l'environnement vient du principal authentifié ; la console opérateur exige le rôle opérateur.
