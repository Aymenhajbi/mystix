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

## Amendement 1 — 2026-10-02 : directions IN/OUT, partenaires, flux déclarés

### Contexte
Un client n'a pas un seul flux de factures : il **émet** (OUT) vers ses clients et la DGI, et il **reçoit** (IN) les factures de ses fournisseurs, avec plusieurs types de partenaires. Le modèle initial ne savait représenter que l'émission API → UBL 2.1.

### Décision
1. **Direction** : `exchange_flow.direction` vaut `OUT` (le client émet) ou `IN` (le client reçoit). Le catalogue est **par direction** (`GET /api/v1/flows/catalog` → `{ out, in, mappings }`) ; une option d'une direction est refusée dans l'autre (`FLOW_OPTION_UNAVAILABLE`).
2. **Partenaires** : table `partner` (V8) par société, type `CUSTOMER`, `SUPPLIER`, `TAX_AUTHORITY`, `LOGISTICS`, `BANK`, `OTHER` ; ICE (15 chiffres) et GLN (clé GS1) optionnels et contrôlés ; nom unique par société. API `/api/v1/partners`. Un flux peut viser un partenaire (`partner_id`) ou tous (`NULL`) ; un partenaire d'une autre société est refusé (`PARTNER_NOT_FOUND`).
3. **Flux déclarés** : la décision 4 est **remplacée**. Un flux peut utiliser des options prévues pour cartographier dès maintenant les échanges réels du client. Il est alors *déclaré* : `executable = false`, `mapping_id` / `mapping_version` à `NULL` (V8 lève le `NOT NULL`), il reste en brouillon. Le passage à `ACTIVE` et la création d'une version de mapping sont refusés (`FLOW_NOT_EXECUTABLE`, 409). Seuls les flux OUT à source `API` reçoivent des factures.
4. **Exécutable** = toutes les options disponibles **et** un mapping implémenté pour (direction, format source, format cible). Aujourd'hui : OUT, API, JSON canonique → UBL 2.1, réponse API.

### Option écartée par cet amendement
| Option | Pourquoi écartée |
| --- | --- |
| Garder le refus des options prévues | Empêche de décrire les flux IN et multi-partenaires d'un client avant le Lot 6 ; le statut *déclaré* et le blocage d'activation suffisent à ne rien promettre |

### Conséquences
- Livré : directions, partenaires, flux déclarés (API + portail fr/ar), tests d'isolation.
- Prévu : exécution des flux IN (réception AS2/SFTP, UBL/CII/EDIFACT → IDoc/JSON/CSV) au Lot 6, avec leurs mappings.
- Le portail montre IN en bleu et OUT en violet, et marque « Déclaré » tout flux non exécutable.
