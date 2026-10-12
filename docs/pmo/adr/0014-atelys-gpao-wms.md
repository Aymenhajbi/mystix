# ADR-0014 — Atelys : GPAO et WMS sur la plateforme Mystix

- Statut : **Proposé** (2026-10-12)
- Date : 2026-10-12
- Lot : 11 (nouveau)
- Origine : spécification d'intégration GPAO + WMS pour un prospect industriel du bois (palettes, dés moulés, valorisation de la sciure). Aucune donnée du prospect n'est dans le dépôt : tout est fictif et configurable.

## Contexte
Mystix fait l'EDI, la facture électronique et la clearance. Un industriel demande en plus la gestion de production (ordres de fabrication, nomenclatures, consommations, rendements, rebuts, sous-produits) et la gestion d'entrepôts (multi-sites, lots, emplacements, QR codes, réceptions, inventaires, transferts, expéditions). Le module `stock` (ADR-0011) décrit le stock tel que les partenaires EDI le déclarent (article et emplacement en texte, sans lots ni référentiel) : il ne peut pas être le système de référence d'un atelier.

## Décision
1. **Un produit distinct, Atelys, « powered by Mystix »** : même plateforme, même authentification (ADR-0013), mêmes environnements clients, même base. Nom, accent orange et navigation propres ; le passage Mystix ↔ Atelys se fait d'un clic dans la barre du haut. Mystix garde l'EDI ; le raccordement EDI → Atelys (un DESADV qui crée une réception, par exemple) est prévu, pas livré.
2. **Deux modules** :
   - `ma.mystix.wms` : unités et conversions explicites, produits, sites (chacun avec son emplacement TRANSIT), entrepôts, emplacements, lots, registre des mouvements, réservations, réceptions, transferts, inventaires, expéditions, audit ;
   - `ma.mystix.gpao` : nomenclatures versionnées et gammes, ordres de fabrication, consommations et retours, déclarations, résidus, écarts, tableau de bord, données de démonstration.
   La GPAO n'écrit jamais dans les tables du WMS : tout effet de stock passe par les services `Ledger`, `Lots` et `Reservations`. Elle lit les référentiels du WMS (produits, lots, emplacements) en jointure, en lecture seule : les deux modules forment un seul contexte, Atelys.
3. **Stock** (base de l'unité de stock du produit) :
   - registre append-only `wms_movement`, jamais modifié ni supprimé ; une correction est un contre-mouvement ;
   - solde par lot et emplacement `wms_balance`, mis à jour dans la même transaction, **jamais négatif** (contrainte en base et mise à jour conditionnelle) ;
   - physique, bloqué (lot non libéré ou emplacement QUARANTINE), réservé, **disponible = physique − bloqué − réservé**, en transit à part ;
   - réservations et contrôles de disponibilité d'un même produit sérialisés par un verrou consultatif de transaction.
   Tout produit a des lots : un produit non suivi par lot a un lot unique « SANS-LOT », toujours libéré.
4. **Unités** : une quantité saisie dans une autre unité que l'unité de stock n'est acceptée qu'avec une conversion explicite déclarée sur le produit (`UNIT_INCOMPATIBLE` sinon). Aucune conversion implicite (pas de m³ → planches supposé).
5. **Idempotence** : réception, transfert, inventaire et expédition confirmés deux fois ne font rien la seconde fois ; consommation avec clé optionnelle ; déclaration de production avec clé obligatoire et empreinte du contenu (`REPLAY_CONFLICT` si la même clé arrive avec un autre contenu).
6. **Production** :
   - besoin = quantité × qté planifiée / qté de référence × (1 + perte %) ;
   - consommation au-delà du besoin + tolérance (`mystix.atelys.consumption-tolerance-percent`, 10 % par défaut) : justification obligatoire ;
   - la quantité conforme crée un lot du produit fabriqué à l'emplacement de sortie, au statut qualité du produit (en attente ou libéré), relié à chaque lot consommé (généalogie) ;
   - résidus avec un seul devenir chacun : WASTE (détruit, pas de stock), RECOVERED (mesuré, entre en stock en lot de sous-produit), PENDING (en attente de qualification, pas de stock). Pas de double comptage ;
   - indicateurs : réalisation = produit / planifié, taux de rebut = rebut / produit, écart = (consommé − théorique pour la quantité produite) / théorique ; null si le dénominateur est nul.
7. **Droits** : actions sensibles (valider une nomenclature, libérer, bloquer ou rejeter un lot, valider un inventaire, clôturer un OF) réservées à un opérateur de la plateforme ou à un ADMIN de l'environnement, et tracées dans `atelys_audit`. Les rôles métier fins (magasinier, contrôleur qualité, opérateur) sont à valider : non livrés.
8. **QR code** : il contient l'identifiant public aléatoire du lot (`MX…`), sans secret ni donnée personnelle. La recherche accepte la saisie clavier et les douchettes USB ; le scan caméra n'est pas livré.
9. **Démonstration** : `POST /api/v1/atelys/demo`, actif seulement en profil dev (`mystix.atelys.demo-enabled`), crée des données marquées « démo » et « fictif ».

## Livré (2026-10-12)
- Migrations V17 (WMS) et V18 (GPAO), API `/api/v1/wms/**` et `/api/v1/gpao/**`.
- Application Atelys (`/{lang}/atelys`), français et arabe : tableau de bord, OF (liste, détail avec consommation, déclaration, retours, écarts, gamme, historique), nomenclatures, stocks, lot (QR, qualité, généalogie amont et aval, mouvements, audit), réceptions, transferts, inventaires, expéditions, traçabilité, paramètres.
- Tests : `AtelysApiTests` (scénario de référence complet et invariants : rejeux, transferts, lots bloqués, réservations, concurrence, isolation, démonstration).

## Prévu (non livré)
- Raccordement EDI → Atelys (DESADV → réception, ORDERS → réservation client, INVRPT → inventaire).
- Rôles métier et périmètre par site ; inventaires tournants ; FEFO/FIFO configurables.
- Scan caméra, étiquettes imprimables au format des imprimantes du client.
- Remontées machine (OPC UA, MQTT…) : interfaces d'extension seulement, après étude des équipements.
- Coût de revient, ordonnancement, prévision de la demande.

## Points à valider avec le client
Nomenclatures et pertes réelles, unités et conversions (m³, planches, kg), statuts qualité et contrôles obligatoires, destinations des résidus, sites et emplacements, règles de clôture d'un OF.

## Options écartées
| Option | Pourquoi écartée |
| --- | --- |
| Étendre le module `stock` (ADR-0011) | Il reflète le stock déclaré par les partenaires EDI, sans lots ni référentiel ; le mélanger au système de référence de l'atelier brouillerait les deux |
| Application séparée (autre dépôt, autre base) | Double authentification, double exploitation, pas de partage des environnements clients |
| Stock calculé à la volée depuis les mouvements | Lent sur gros volumes et ne protège pas des retraits concurrents ; le solde projeté avec contrainte le fait |
