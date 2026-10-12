# Plan de débit et d'optimisation — Mystix

- Date : 2026-10-12
- Statut : **Proposé** (à discuter avec le fondateur)
- Mesures : `scripts/bench/invoice-bench.js` et `scripts/bench/invoice-sustained.js`, factures synthétiques uniquement

Ce document distingue **mesuré** (chiffres obtenus sur le poste de dev, reproductibles), **hypothèse** (à valider par
mesure) et **prévu** (travaux proposés, rien de livré).

## 1. Ce qui a été mesuré

### Conditions
- Poste : AMD Ryzen 7 5800H (8 cœurs, 16 threads), 16 Go, Windows 11.
- Backend en profil `dev`, lancé par `spring-boot:run` avec `-XX:TieredStopAtLevel=1` (JIT limité : défavorable).
  PostgreSQL 16 en Docker sur le même poste, client de charge sur le même poste.
- Chemin mesuré : `POST /api/v1/invoices`, **synchrone de bout en bout** : lecture JSON, canonique, calculs, contrôle
  des taux TVA datés, génération UBL 2.1, validation XSD, validation EN 16931 (Schematron CEN 1.3.16), stockage des
  trois artefacts, clearance **simulée**, journal.
- Aucune erreur sur l'ensemble des séries (toutes les factures acceptées).

### Séries courtes (2 à 5 s chacune, extrapolées à l'heure)

| Lignes / facture | Appels simultanés | Factures / heure | Lignes / heure | p50 | p95 |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 3 | 1 | 66 702 | 200 106 | 53 ms | 69 ms |
| 3 | 8 | 462 805 | 1 388 415 | 59 ms | 86 ms |
| 3 | 32 | 653 181 | 1 959 542 | 171 ms | 228 ms |
| 20 | 1 | 53 138 | 1 062 762 | 68 ms | 81 ms |
| 20 | 8 | 389 763 | 7 795 261 | 71 ms | 88 ms |
| 20 | 32 | 510 531 | 10 210 624 | 211 ms | 288 ms |
| 100 | 1 | 29 128 | 2 912 822 | 122 ms | 149 ms |
| 100 | 8 | 181 531 | 18 153 087 | 154 ms | 174 ms |
| 100 | 32 | 244 708 | 24 470 775 | 456 ms | 539 ms |
| 500 | 8 | 52 335 | 26 167 523 | 547 ms | 562 ms |

### Série soutenue (60 s)
20 lignes par facture, 16 appels simultanés : **9 364 factures en 60,1 s**, soit **≈ 156 factures/s, ≈ 560 000
factures/heure, ≈ 11,2 millions de lignes/heure**, p50 101 ms, p95 123 ms, p99 140 ms, **0 échec**.

### Lecture
- Le coût fixe par facture (≈ 50 ms en série) domine jusqu'à ~20 lignes ; au-delà, le coût par ligne prend le dessus
  (100 lignes : 122 ms ; 500 lignes : ≈ 550 ms sous charge).
- Le débit monte presque linéairement de 1 à 8 appels simultanés, puis plafonne : de 8 à 32 appels, +30 à +40 % de
  débit pour une latence ×3. Le poste est saturé en CPU vers 8 à 16 appels.
- Le débit en lignes croît avec la taille des factures : ~26 millions de lignes/heure à 500 lignes.

## 2. Limites de ces chiffres (à ne pas présenter comme une capacité de production)
- **Hypothèse** : la clearance DGI réelle ajoutera une latence réseau et des quotas inconnus (spécifications non
  publiées). Elle sera probablement le facteur limitant, pas Mystix.
- Un seul environnement client, un seul flux, base presque vide au départ (≈ 13 000 factures de banc ajoutées dans la
  base de dev par ces mesures).
- Client de charge, application et base sur la même machine : ils se partagent le CPU.
- Les canaux AS2 et SFTP ne sont pas mesurés (non livrés) ; les interchanges EDIFACT stock non plus (à ajouter au banc).

## 3. Où sont les limites dans l'architecture actuelle
1. **Tout est synchrone** dans la requête HTTP : un pic se traduit directement en latence, il n'y a ni file, ni
   reprise, ni priorité (la file PostgreSQL `SKIP LOCKED` de l'ADR-0003 est prévue, non livrée).
2. **Pas d'isolation par flux ni par client** : tous les flux partagent le même pool de threads. Un client qui envoie
   un gros lot ralentit les autres (« voisin bruyant »).
3. **Pas de mesure par étape** : on connaît le temps total, pas la part de la validation Schematron, du XSD ou de
   l'écriture en base. Les validateurs XSD et Schematron sont déjà compilés une seule fois au démarrage.
4. **Artefacts en base** : RAW, CANONICAL et OUT sont stockés dans PostgreSQL ; le volume de la base grossit vite
   (stockage objet S3 prévu, Lot 4).
5. **Gros fichiers** : les interchanges et factures sont lues entièrement en mémoire ; adapté jusqu'à quelques
   milliers de lignes, pas à des fichiers de plusieurs centaines de Mo.

## 4. Plan proposé (prévu, par ordre de valeur)

### Étape 1 — Mesurer finement (quelques jours)
- Timers Micrometer par étape (`parse`, `canonical`, `xsd`, `en16931`, `persist`, `clearance`) étiquetés par flux et
  par environnement client ; export OpenTelemetry (Lot 8).
- Banc de charge dans le dépôt, rejoué chaque nuit sur une machine fixe, avec seuils : régression de plus de 10 % =
  alerte. Ajouter un scénario EDIFACT stock et un scénario 1 000 lignes.
- Lancer le backend de banc avec les options de production (JIT complet, `-Xms` = `-Xmx`, G1) pour un chiffre honnête.

### Étape 2 — Gains rapides sur le chemin synchrone
- Pool de connexions Hikari dimensionné sur les cœurs (aujourd'hui valeurs par défaut).
- Écritures groupées (`batchUpdate`) pour les lignes, les artefacts et le journal ; une seule transaction par facture.
- Mesurer puis paralléliser XSD et EN 16931 quand la facture dépasse un seuil de lignes (les deux contrôles sont
  indépendants).
- Compression des artefacts stockés, puis bascule vers le stockage objet (Lot 4).

### Étape 3 — Traitement asynchrone par flux (multi-flux, le cœur du sujet)
- **Accusé immédiat** : l'API répond `202 Accepted` avec l'identifiant du message ; le traitement se fait dans la file
  PostgreSQL (`FOR UPDATE SKIP LOCKED`, ADR-0003), avec reprises, file des messages en échec et rejeu.
- **Une file logique par flux** : chaque flux a sa concurrence maximale, sa priorité et son quota. Un ordonnanceur
  équitable sert les flux à tour de rôle, pour qu'un gros lot d'un client ne bloque pas les autres.
- **Contre-pression** : au-delà du quota d'un flux, `429 Too Many Requests` avec `Retry-After`, plutôt qu'une latence
  qui explose.
- **Mode synchrone conservé** pour les clients qui en ont besoin (portail, petits volumes), sur un pool séparé.
- Indicateurs par flux dans le cockpit : file d'attente, débit, latence, taux d'échec.

### Étape 4 — Gros volumes
- Lecture en flux (StAX pour l'XML, découpage par segment pour l'EDIFACT) au lieu d'un chargement complet.
- Lots découpés en sous-transactions (par exemple 1 000 lignes) pour les inventaires et grosses factures.
- Partitionnement mensuel des tables de messages et de journal ; purge et archivage selon la durée de conservation.

### Étape 5 — Montée en charge horizontale
- Plusieurs instances applicatives sans état derrière un répartiteur ; les travailleurs de la file se répartissent les
  flux naturellement grâce à `SKIP LOCKED`.
- Réplique en lecture pour le portail et le cockpit.
- Clearance DGI : appels groupés ou asynchrones selon ce que les spécifications permettront (`TODO(DGI-SPEC)`).

## 5. Objectifs à valider ensemble (hypothèses)
- API synchrone : p95 < 300 ms jusqu'à 100 lignes.
- Par instance de production (4 vCPU) : au moins 100 000 factures/heure de 20 lignes, hors clearance réelle.
- Aucun flux ne doit voir sa latence doubler à cause d'un autre flux (isolation, étape 3).
- Ces chiffres sont des cibles de travail, pas des engagements commerciaux, tant qu'ils ne sont pas mesurés sur
  l'infrastructure de production.
