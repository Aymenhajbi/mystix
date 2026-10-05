# ADR-0011 — Module stock : registre de mouvements, cinq états, réconciliation INVRPT

- Statut : **Accepté** (2026-10-05) : modèle par partition validé par le fondateur. Qualificatifs EDIFACT encore à vérifier avant le Lot 6.
- Date : 2026-10-05
- Lot : 10 (nouveau, stock)

## Contexte
Le fondateur veut un module qui fasse converger le flux physique (mouvements d'entrepôt), le flux de propriété (transferts de risques) et le flux d'information EDI (DESADV, RECADV, ORDERS, INVRPT). L'image de stock se décline par article (SKU) et par emplacement, en cinq états : Disponible, Réservé, En transit, Quarantaine, Consignation.

## Décision
1. **Registre append-only** (`stock_movement`) : chaque mouvement porte l'article, l'emplacement, l'état de départ, l'état d'arrivée (`NULL` = entrée ou sortie du périmètre), la quantité (> 0, `NUMERIC(18,3)`), l'événement métier d'origine et sa date d'effet (`occurred_at`).
   - Les **positions** (`stock_position`) sont une projection mise à jour dans la même transaction, par incrément atomique.
   - Le registre est la vérité : une position se recalcule toujours depuis les mouvements.
2. **Événements métier canoniques** (`POST /api/v1/stock/events`), idempotents par `idempotencyKey` (même clé et même contenu : même résultat ; contenu différent : 409). Les parseurs EDIFACT arriveront au Lot 6 et produiront ces mêmes événements (ADR-0002).

   | Événement | Message EDI d'origine | Mouvement |
   | --- | --- | --- |
   | `SHIPMENT_NOTICE_IN` | DESADV fournisseur | → En transit |
   | `RECEIPT` (référence au DESADV) | RECADV | Transit → Disponible (accepté), Transit → Quarantaine (refusé), Transit → sortie (manquant) ; écart avec le DESADV → **alerte litige fournisseur** |
   | `ORDER` | ORDERS client | Disponible → Réservé ; refus si le disponible est insuffisant (`STOCK_INSUFFICIENT`) |
   | `SHIPMENT_OUT` | DESADV client | Réservé → sortie |
   | `STATUS_CHANGE` | INVRPT événementiel | état → état (ex. Disponible → Quarantaine) |
   | `ADJUSTMENT` | INVRPT événementiel (casse, vol) | entrée ou sortie d'un état |
   | `SNAPSHOT` | INVRPT de rapprochement | réconciliation, voir 4 |
3. **Formules — modèle par partition (arbitrage 1, validé par le fondateur le 2026-10-05)** : les cinq états partitionnent la quantité.
   - **Stock physique (On-Hand)** = Disponible + Réservé + Quarantaine.
   - **ATP** = Disponible + En transit. Le Réservé est déjà déduit, puisqu'une réservation fait passer la quantité de Disponible à Réservé.
   - La spécification écrivait « ATP = (Disponible + Transit) − Réservé », ce qui soustrairait le réservé deux fois avec la formule du stock physique. Le modèle par partition est le seul cohérent avec les règles ORDERS (« retirer de l'ATP, ajouter au Réservé ») et DESADV sortant (« diminuer le Réservé et le stock physique »).
   - Hypothèse : tout le stock en transit annoncé par un DESADV compte dans l'ATP. Un filtre « transit validé » (date d'arrivée, transporteur) reste à préciser.
   - La Consignation est hors stock physique et hors ATP : c'est du stock à nous chez le client.
4. **Réconciliation INVRPT (snapshot)** : Δ = Stock_INVRPT − Stock théorique à l'instant du snapshot, où le stock théorique est **la somme des mouvements dont la date d'effet est ≤ `asOf`**.
   - Ainsi, les flux intégrés après l'instant du snapshot (ORDERS, RECADV arrivés dans l'intervalle) ne sont ni écrasés ni comptés deux fois. C'est la traduction exacte de « Flux en cours non intégrés ».
   - Le snapshot couvre ses emplacements : un couple article/état absent du snapshot y vaut 0.
   - Si Δ ≠ 0 : la ligne va dans la table des écarts (`stock_variance`), un **mouvement correctif** daté de `asOf` aligne le stock sur le WMS (nouvelle référence), et le taux d'exactitude (lignes conformes / lignes comparées) est enregistré.
5. **Multi-tenant** : toutes les tables portent `company_id` ; chaque requête filtre dessus.

## Qualificatifs EDIFACT (arbitrage 2, à vérifier avant le Lot 6)
La spécification cite QTY+12 (expédiée), QTY+21 (commandée), QTY+48 (reçue), QTY+124, QTY+145, QTY+192, LOC+35 et le segment INV.
- **Cohérents à première lecture** : 12, 21, 48, et 145 au sens de stock réel.
- **À vérifier sur la liste de codes UN/EDIFACT 6063 et 3227 (D96A, D01B) et sur le guide EANCOM** : QTY+124, QTY+192, LOC+35, et QTY+145 employé comme « quantité d'ajustement ».

Rien de cela n'est codé dans cet incrément : les événements canoniques en sont indépendants.

## Prévu (non livré)
- Parseurs DESADV, RECADV, ORDERS, INVRPT (Lot 6).
- Valorisation selon le transfert de propriété (Incoterms).
- Annulation de commande.
- Réservation partielle ou reliquat.
- Écrans du portail.
- Alertes poussées (webhook).

## Options écartées
| Option | Pourquoi écartée |
| --- | --- |
| Table de stock à valeur unique | Interdite par la spécification ; ne distingue pas les états |
| Écraser le stock par le snapshot | Perd les flux intégrés après l'instant du snapshot |
| Positions seules, sans registre | Aucune réconciliation datée possible, aucun audit |
