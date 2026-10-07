# ADR-0012 — Lecture EDIFACT D96A des messages de stock (DESADV, RECADV, ORDERS, INVRPT)

- Statut : **Proposé** (2026-10-05)
- Date : 2026-10-05
- Lot : 6 (EDIFACT), au service du Lot 10 (stock, ADR-0011)

## Contexte
Le moteur de stock (ADR-0011) reçoit des événements canoniques. Il faut lire les messages UN/EDIFACT D96A qui les portent : DESADV, RECADV, ORDERS, INVRPT. La spécification fonctionnelle citait des qualificatifs. Ils ont été **vérifiés sur l'annuaire UNTDID D96A**, et quatre d'entre eux étaient faux.

## Sources des codes (vérifiés le 2026-10-05)
- Site officiel UNECE (service.unece.org/trade/untdid/d96a) : derrière une vérification anti-robots, non consultable automatiquement. Elle n'a pas été contournée.
- **Texte UNTDID D96A** repris tel quel dans le dépôt open source `fretlink/edi-parser` (`specification/references/D96A`). Il a servi pour les listes 6063, 3227, 3035, 1153, 2005, 1001, 4405 et 2379, et pour la structure du DESADV.
- Données dérivées de l'annuaire, dépôt `ediflow-lib/core` (`packages/edifact-d96a`) : structures RECADV, ORDERS, INVRPT, et listes 4501 et 7491. C'est une source secondaire, à reconfirmer sur l'annuaire officiel.

## Corrections apportées à la spécification
| Spécification | D96A | Conséquence |
| --- | --- | --- |
| QTY+48 = « reçue acceptée » | 48 = *Received quantity* ; **194 = *Received and accepted*** | Accepté = 194, sinon 48 moins les refusés |
| QTY+192 = « manquante » | **192 n'existe pas** en D96A ; 119 = *Short shipped*, 122 = *Short-landed goods* | Manquant = 119 + 122 |
| QTY+145 = « quantité d'ajustement » | 145 = *Actual stock* ; **191 = *Adjustment to inventory quantity*** | 145 → inventaire (snapshot) ; 191 → ajustement |
| LOC+35 = « lieu exact » | 35 = *Country of exportation/despatch* ; **18 = *Warehouse***, 14 = *Location of goods* | Emplacement = LOC+18, sinon LOC+14 |
| STS = statut qualité | 4405 (D96A) n'a **aucun** code « bloqué » ou « quarantaine » | La quarantaine se lit dans **INV** (7491 = 2, *Damaged product inventory*), pas dans STS |

## Décision
1. **Analyseur générique** (`ma.mystix.format.edifact`) :
   - UNA facultatif ; caractère de libération `?` ; jeu de caractères selon la syntaxe UNB (UNOA, UNOB → ASCII ; UNOC → ISO-8859-1 ; UNOY → UTF-8).
   - Contrôles UNT (nombre de segments) et UNZ (nombre de messages), avec la position du segment en erreur.
2. **Un message = un événement canonique** (ADR-0002, ADR-0011) :
   - Clé d'idempotence : `EDI:<émetteur UNB>:<référence UNB>:<référence UNH>`. Une interchange retransmise ne s'applique qu'une fois.
   - Le **message brut** (UNH…UNT) est conservé octet pour octet, avec son empreinte SHA-256 (`stock_edi_message`).
3. **Correspondances** :

   | Message (1001) | Événement | Quantités (6063) | Date (2005) |
   | --- | --- | --- | --- |
   | DESADV (351), direction IN | `SHIPMENT_NOTICE_IN` | 12 *Despatch quantity* | 11, sinon 137 |
   | DESADV (351), direction OUT | `SHIPMENT_OUT` | 12 | 11, sinon 137 |
   | RECADV (632) | `RECEIPT`, référence RFF+AAK (*Despatch advice number*), sinon RFF+DQ | accepté 194 (sinon 48 − refusés) ; refusé 195 + 196 + 124 ; manquant 119 + 122 | 50, sinon 137 |
   | ORDERS (220) | `ORDER` | 21 *Ordered quantity* | 137 |
   | INVRPT (35) avec QTY+191 | `ADJUSTMENT` : INV 4501 (1 sortie, 2 entrée) ; INV 7491 → état | 191 | 137 |
   | INVRPT (35) sans QTY+191 | `SNAPSHOT` : INV 7491 → état, 145 seul → Disponible | 145, 17 | 137 (instant de la photographie) |

   - **États selon 7491** : 1 → Disponible ; 2 → Quarantaine ; 4 → Réservé ; 3 (*Bonded*) → refusé, non pris en charge.
   - Un INVRPT qui mélange ajustements et photographie est refusé.
   - **Article** : LIN C212 7140, sinon PIA C212 7140.
   - **Emplacement** :
     - LOC+18, sinon LOC+14, au niveau du groupe QTY, sinon de la ligne, sinon de l'en-tête ;
     - sinon le GLN du NAD : DP puis ST pour les réceptions, SF pour les expéditions et commandes, WH pour les inventaires ;
     - sinon l'emplacement par défaut passé à l'API.
   - **Dates sans fuseau** (formats 102, 203, 204) : lues en Africa/Casablanca, fuseau métier.
4. **API** : `POST /api/v1/stock/edifact?location=…`, avec l'en-tête `X-Mystix-Flow-Id` (amendement 1), reçoit une interchange.
   - Chaque message est appliqué dans **sa propre transaction**, et la réponse donne le résultat message par message.
   - Une erreur de syntaxe de l'interchange la refuse en entier (`EDIFACT_INVALID`, 400).

## Amendement 1 — 2026-10-07 : sens du DESADV donné par le flux du client
Décision du fondateur : le sens d'un DESADV n'est plus passé en paramètre. Il vient du **flux IN/OUT de l'environnement client**, nommé par l'en-tête `X-Mystix-Flow-Id` (comme pour les factures).
- Flux **IN** : avis d'expédition d'un fournisseur (`SHIPMENT_NOTICE_IN`). Flux **OUT** : notre expédition vers un client (`SHIPMENT_OUT`).
- Le flux doit appartenir à l'environnement, sinon `FLOW_NOT_FOUND` (404). Sans flux, un DESADV est refusé, avec un motif qui nomme l'en-tête ; les RECADV, ORDERS et INVRPT n'en ont pas besoin.
- Une même interchange déjà appliquée ne peut pas changer de sens : renvoyée par l'autre flux, elle est refusée (`STOCK_EVENT_CONFLICT`).
- Reste à faire (Lot 6) : types de document « stock » dans le catalogue des flux, et flux ACTIF exigé comme pour les factures. Aujourd'hui, seul le sens du flux est lu.

## Hypothèses à confirmer
- Profils EANCOM des partenaires : les messages D96A EANCOM (UNH …:EAN00x) sont acceptés, sans contrôle des règles propres à EANCOM.
- Pas d'accusé de réception CONTRL ni APERAK à ce stade.
