# ADR-0010 — Référentiel TVA daté et contrôle des taux à l'émission

- Statut : **Accepté** (décisions du fondateur, 2026-10-05). Données de source secondaire, à confirmer sur le CGI.
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

## Données chargées (V12, 2026-10-05)
Sur demande du fondateur, les taux sont chargés d'après les **notes Grant Thornton Maroc sur les lois de finances 2021 à 2026** ([grantthornton.ma](https://www.grantthornton.ma/publications/lois-de-finances-annuelle/)). C'est une source **secondaire** : le site de la DGI, source primaire, ne répondait pas. Les notes ont été téléchargées pour lecture et ne sont pas versionnées dans le dépôt.

| Période | Taux S en vigueur | Ce que disent les notes |
| --- | --- | --- |
| 2021-01-01 → 2023-12-31 | 7, 10, 14, 20 | LF 2021 à 2023 : reclassements d'opérations seulement (panneaux solaires à 10 % en 2022 ; avocats, notaires… de 10 à 20 % en 2023) |
| 2024 | 7*, 8, 10, 11, 12, 13, 14*, 16, 20 | LF 2024 : sucre 8 % ; compteurs d'électricité 11 % ; électricité renouvelable et courtiers d'assurance 12 % ; transport 13 % ; électricité 16 % ; eau hors usage domestique et voiture économique 10 % |
| 2025 | 7*, 9, 10, 12, 14*, 15, 18, 20 | Calendrier LF 2024 : sucre 9 %, transport 12 %, compteurs 15 %, électricité 18 %, renouvelable et courtiers 10 % ; LF 2025 : levures sèches 20 % |
| Depuis 2026-01-01 | 10, 20 | Fin du calendrier LF 2024 ; la note LF 2026 ne modifie aucun taux |

\* *Taux antérieurs maintenus pour les opérations non visées par la LF 2024 : à confirmer.* Les notes ne disent pas si des opérations sont restées à 7 % ou 14 % en 2024-2025. L'historique est donc **permissif** : un taux en trop relâche le contrôle, un taux manquant bloquerait à tort, et toute facture antidatée passe de toute façon par la validation admin. Pour 2026, le contrôle est strict (10 et 20 %).

`TODO(DGI-SPEC)` : confirmer sur le CGI (art. 99, éditions 2024 à 2026) et les notes circulaires de la DGI. Toute correction se fera par une nouvelle migration, jamais en modifiant V12.

## Factures antidatées : validation administrateur (V11, décision du fondateur 2026-10-05)
- Une facture antidatée est **contrôlée et conservée**, puis mise en statut `PENDING_VALIDATION` : **pas de clearance automatique**.
- `POST /api/v1/invoices/{id}/validation` (`approve`, `comment`) :
  - si elle est validée, elle passe à `VALIDATED` puis part en clearance ;
  - si elle est refusée, elle passe à `VALIDATION_REJECTED` et s'arrête là.
- La décision est unique : une seconde décision renvoie `INVOICE_NOT_PENDING_VALIDATION` (409). Elle est tracée dans l'historique et dans les logs (`INVOICE_VALIDATION_APPROVED` / `INVOICE_VALIDATION_REJECTED`).
- **Dashboard** : un bandeau d'avertissement liste les factures à valider (émission, réception, client, montant). Le compteur « À traiter » les inclut, et la liste des factures a un filtre « À valider ».
- TODO(auth) : la décision sera réservée au rôle administrateur de l'environnement (Lot 8). D'ici là, toute personne qui accède au portail de l'environnement peut décider.

## Conséquences
- Un environnement qui facture avant la date de début du référentiel n'est pas contrôlé, comme pour un référentiel vide.
- Les catégories E et O (motif d'exonération) restent à faire, au Lot 3.
- `TODO(DGI-SPEC)` : le contrôle porte sur des taux légaux publiés ; le format et les règles de la clearance DGI restent non publiés (ADR-0001).
