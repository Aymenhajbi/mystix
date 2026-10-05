# ADR-0010 — Référentiel TVA daté et contrôle des taux à l'émission

- Statut : **Accepté** pour le mécanisme (décision du fondateur, 2026-10-05). **Données initiales : proposées, en attente de validation.**
- Date : 2026-10-05
- Lot : 2 (référentiel daté), 3 (portail)

## Contexte
AGENTS.md impose que les taux de TVA viennent d'un référentiel daté, jamais d'une constante. Jusqu'ici, le taux arrivait dans la requête sans aucun contrôle, et le portail le faisait saisir à la main. La réforme engagée par la loi de finances 2024 a fait évoluer les taux par produit, année après année, jusqu'à la convergence de 2026 : le taux applicable dépend donc de la **date d'émission**.

## Décision
1. **Table `vat_rate`** (V10), donnée de plateforme commune à tous les environnements, donc **sans `company_id`** : c'est une exception assumée à l'invariant multi-tenant, car ce référentiel n'est pas une donnée métier d'un client.
   - Colonnes : pays, catégorie EN 16931 (S, Z, E, O), taux, `valid_from`, `valid_to` (nul = toujours en vigueur), référence légale obligatoire.
   - Un taux est en vigueur le jour J si `valid_from ≤ J ≤ valid_to`.
2. **Alimentation** : par une migration de données relue et validée, ou par l'API opérateur `POST /api/v1/admin/vat-rates` (console admin, désactivée hors dev ; rôle opérateur au Lot 8). Jamais en dur dans le code.
3. **Lecture** : `GET /api/v1/referential/vat-rates?country=MA&date=AAAA-MM-JJ`.
4. **Contrôle à l'émission**, actif par défaut et désactivable par environnement (`company.enforce_vat_rates`, panneau « Paramètres de l'environnement ») :
   - Une ligne S doit porter un taux S en vigueur, à la date d'émission, dans le pays du vendeur. Sinon : `VAT_RATE_UNKNOWN` (400, champ `lines[i].vat.ratePercent`), avec la liste des taux en vigueur.
   - **Référentiel vide pour la date ou le pays : aucun contrôle.** Un référentiel incomplet ne bloque jamais.
   - Le test à blanc (ADR-0008) n'applique pas ce contrôle : les règles de mapping ne touchent pas la TVA, et les échantillons stockés y sont déjà passés à leur réception.
5. **Portail** : le taux d'une ligne S se choisit dans la liste des taux en vigueur à la date d'émission (liste rechargée si la date change), sans présélection. Si le contrôle est désactivé ou si le référentiel est vide, le taux se saisit librement, avec les taux en vigueur proposés.

6. **Factures antidatées** (décision du fondateur, 2026-10-05) :
   - **Définition** : une facture est antidatée quand sa date d'émission est antérieure au jour de sa réception (Africa/Casablanca). C'est déduit de faits stockés (`issue_date`, `created_at`), sans seuil inventé, et cela ne change jamais après coup.
   - **Taux** : la facture est contrôlée sur les taux en vigueur **à sa date d'émission**, comme la loi les applique. Par exemple, une facture émise en 2025 garde les taux de 2025, même reçue en 2026.
   - **Détection** : champ `backdated` dans l'API (liste et détail), badge « Antidatée » et note avec les dates d'émission et de réception dans le portail. À la réception, un log WARN `INVOICE_BACKDATED` indique les taux appliqués.
   - **Taux hors période** : blocage `VAT_RATE_UNKNOWN`. Le motif commence par « backdated invoice (issued …, received …) » et l'erreur est journalisée.
   - **Référentiel sans taux pour la date** : la facture passe, mais un log WARN `VAT_RATES_UNCHECKED` le signale. Rien ne passe en silence, et un référentiel incomplet ne bloque pas.
   - Le formulaire du portail annonce la facture antidatée dès qu'une date passée est saisie.

## Options écartées
| Option | Pourquoi écartée |
| --- | --- |
| Taux par produit (nomenclature) | Mystix ne connaît pas la nature fiscale des articles ; ce serait une règle DGI inventée. Le contrôle porte sur l'ensemble des taux en vigueur. |
| Avertissement seul | Décision du fondateur : refus par défaut, désactivable |
| Taux codés en dur | Interdit par AGENTS.md (§4) |

## Données initiales proposées (non chargées : à valider par le fondateur)
**Non vérifiées sur le texte officiel** : le site de la DGI (tax.gov.ma) répondait 503 le 2026-10-05. Sources consultées :
- [maroc.ma](https://www.maroc.ma/fr/actualites/la-dgi-publie-ledition-2026-du-code-general-des-impots), officiel : le CGI 2026 intègre la LF n° 50-25 (dahir n° 1-25-67 du 10/12/2025) ;
- [Upsilon Consulting](https://www.upsilon-consulting.com/reforme-tva-maroc-2024-2026/) et [Sage Maroc](https://www.sage.com/fr-ma/blog/loi-de-finances-2024-ce-qui-change-avec-la-tva/), secondaires, qui citent les notes circulaires 735, 736 et 737 et le CGI art. 99 et 247.

Ensembles de taux S en vigueur par période, déduits du calendrier produit par produit :

| Période | Taux S en vigueur | Origine (selon sources secondaires) |
| --- | --- | --- |
| 2024-01-01 → 2024-12-31 | 7, 8, 10, 11, 12, 13, 14 (?), 16, 20 | 7 : pâtes ; 8 : sucre raffiné ; 11 : location de compteurs ; 12 : énergies renouvelables, courtage d'assurance ; 13 : transport urbain et routier ; 16 : électricité, transport non urbain |
| 2025-01-01 → 2025-12-31 | 7, 9, 10, 12, 14 (?), 15, 18, 20 | 9 : sucre ; 12 : transport urbain ; 15 : compteurs ; 18 : électricité, transport non urbain |
| 2026-01-01 → (en vigueur) | **10, 20** | CGI art. 99-A (20 %) et 99-B (10 %) après convergence |

**Points à confirmer avant chargement** :
1. Restait-il des opérations à **14 %** en 2024 et 2025 ? Les sources ne le disent pas.
2. Le **7 %** est-il resté en vigueur jusqu'au 31/12/2025 ? Il l'est pour les pâtes, si la NC 737 est confirmée.
3. Le texte exact de l'art. 99 du CGI 2026.

Une fois les valeurs validées, elles seront chargées par une migration de données dédiée, avec la référence légale de chaque ligne.

## Conséquences
- Un environnement qui facture avant la date de début du référentiel n'est pas contrôlé, comme pour un référentiel vide.
- Les catégories E et O (motif d'exonération) restent à faire, au Lot 3.
- `TODO(DGI-SPEC)` : le contrôle porte sur des taux légaux publiés ; le format et les règles de la clearance DGI restent non publiés (ADR-0001).
