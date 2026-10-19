# Brief projet — Mystix

> Document de référence à déposer dans les **connaissances du Projet Claude** (claude.ai → Projets).
> Il donne le contexte métier, marché et technique. Le fichier `CLAUDE.md` du dépôt donne les règles de travail de Claude Code en local.
> Dernière mise à jour : 29/09/2026.

---

## Instructions du projet (à coller dans « Instructions » du Projet Claude)

```text
Tu assistes le fondateur de Mystix, une plateforme marocaine d'intégration EDI, de facturation électronique et de clearance DGI.

- Réponds en français. Le code, les noms techniques et les commits restent en anglais.
- Le fondateur est un intégrateur EDI expérimenté (iXPath, IBM Sterling B2B Integrator, OpenText, Generix, SAP, AS2/SFTP, XSLT). Parle-lui en expert, sans vulgariser.
- Stack imposée : backend Java 21 / Spring Boot, frontend Next.js / TypeScript, PostgreSQL. Ne propose pas de changer de stack.
- Le code existe déjà : toute proposition part de l'existant et avance par petits incréments testés.
- Règles fiscales DGI : ne jamais inventer. Les spécifications officielles ne sont pas publiées ; tout est configurable et marqué TODO(DGI-SPEC).
- Distingue toujours ce qui est livré, ce qui est prévu et ce qui est une hypothèse. Ne survends rien, surtout dans les documents commerciaux.
- Ne réutilise jamais d'informations confidentielles sur les clients des anciens employeurs du fondateur.
- Pour tout sujet réglementaire ou tarifaire récent, vérifie les sources et cite-les.
```

---

## 1. Vision

Mystix est un **Enterprise Integration Runtime multi-tenant** pour le Maroc : échanges EDI, facturation électronique, clearance DGI et synchronisation de stock.

**Ambition** : permettre à **80 % des entreprises marocaines** de respecter l'obligation de facturation électronique, du grand compte sous SAP à l'auto-entrepreneur, avec une plateforme moderne, pas un moteur EDI hérité des années 2010.

**Promesse** : chaque document est traçable de l'entrée à la sortie, chaque erreur est expliquée avec une action, chaque correction est versionnée, testée et rejouable.

---

## 2. Contexte réglementaire (à confirmer, sources secondaires)

- Modèle **clearance / CTC** : chaque facture est transmise à la plateforme DGI et validée avant d'être légalement valable. Rejet immédiat en cas d'erreur (ICE, montants, TVA…).
- Formats annoncés : **UBL 2.1** et **CII**.
- Déploiement par vagues : grandes entreprises en 2026, moyennes en 2026, **PME, TPE et auto-entrepreneurs à partir de 2027**.
- Une facture non conforme risque de ne plus ouvrir droit à déduction de TVA pour l'acheteur : l'acheteur exigera des factures conformes.
- **Les spécifications techniques officielles de la DGI ne sont pas publiées.** Dans Mystix, la clearance est **simulée** derrière une interface `ClearanceGateway`.
- La Loi de Finances 2027 n'était pas votée au 29/09/2026 (projet déposé en général fin octobre).
- Identifiants : **ICE** (15 chiffres), IF, RC. Taux de TVA : référentiel daté, jamais codé en dur. Conservation : probablement 10 ans, à confirmer.

---

## 3. Marché et segments

| Segment | Besoin | Canal Mystix |
| --- | --- | --- |
| Grands comptes (SAP, grande distribution, industrie) | EDI avec les enseignes + conformité DGI + supervision | IDoc, EDIFACT/EANCOM, UBL/CII, AS2, SFTP, API |
| ETI et PME avec ERP (Sage, Odoo, logiciel local) | Conformité DGI sans projet lourd | Connecteurs ERP, import CSV/Excel guidé, API |
| TPE et auto-entrepreneurs (la majorité) | Émettre des factures conformes simplement | Portail web/mobile, modèles, import Excel/PDF |
| Éditeurs de logiciels | Ne pas développer eux-mêmes la connexion DGI | API REST, webhooks, marque blanche |

**Levier principal pour atteindre les 80 %** : le **programme fournisseurs** (WebEDI). Un grand compte connecté en EDI amène ses fournisseurs sur le portail Mystix ; ils transforment les commandes reçues en factures conformes.

---

## 4. Concurrence

| Acteur | Positionnement | Différence Mystix |
| --- | --- | --- |
| **Weexa** (groupe d'origine marocaine : Eumatech, EDI Xperts, EDT) | Intégrateur EDI/EAI ; filiale **iXPath** (iPaaS pour PME) ; **EIMA** (facturation électronique, agréée en France) ; digitalise les entrepôts de LabelVie | Concurrent sérieux et installé. Mystix : conçu pour la DGI dès le départ, plateforme unique de la PME au grand compte, portail éleveurs/fournisseurs, abonnement plutôt que projet |
| Éditeurs EDI internationaux (Seeburger, OpenText, IBM Sterling, EDICOM, Comarch, Generix) | Plateformes complètes, projets longs et coûteux | Mise en service en semaines, équipe locale, coût d'entrée plus bas |
| Modules ERP (SAP DRC, extensions Sage X3/Odoo) | Conformité DGI depuis l'ERP | Ne couvrent ni l'EDI avec les enseignes ni les fournisseurs |
| Logiciels de facturation marocains (Hisab, Efatora, MySunday, Fawatir, InvoiceGenAI…) | PME, 0 à 600 MAD/mois | Mystix ne se bat pas sur ce prix ; sa valeur est l'EDI, l'intégration et l'offre éditeurs |

**Argument tiré de l'expérience du fondateur** : sur les plateformes du marché, le **mapping et la mise en forme XML** (ordre des éléments, namespaces, CDATA, échappement, formats) sont le premier poste de temps. Sur OpenText surtout ; iXPath était plus avancé mais restait manuel. Mystix y répond par des sorties générées depuis le schéma, des formats déclarés une fois, un aperçu en direct et des fixtures.

Règles : ne citer aucun client d'un ancien employeur ; ne pas dénigrer un concurrent sans fait vérifié ; comparer des approches, pas des entreprises.

---

## 5. Offre et tarifs (estimations, à valider auprès de prospects)

| Offre | Cible | Prix indicatif (MAD HT) |
| --- | --- | --- |
| Portail Essentiel | TPE, auto-entrepreneurs | 0 à 149 / mois |
| Portail Pro | PME | 249 à 499 / mois |
| Connect | PME/ETI avec ERP | 1 500 à 4 000 / mois + mise en service 5 000 à 15 000 |
| Enterprise EDI | Grands comptes | 10 000 à 30 000 / mois + mise en service 40 000 à 120 000 |
| Plateforme éditeurs | Éditeurs de logiciels | 0,30 à 1 MAD par facture validée, minimum 3 000 à 10 000 / mois |
| Programme fournisseurs | Grand compte qui équipe ses fournisseurs | 50 000 à 150 000 / an |

Options : partenaire EDI supplémentaire (400 à 1 000 / mois), mise en place d'un flux (3 000 à 10 000), dépassement (0,50 à 1,50 MAD / document), support premium (+15 à 20 %).

Principes : facturer au document et au partenaire, pas à l'utilisateur ; clearance DGI et archivage toujours inclus ; mise en service facturée pour Connect et Enterprise.

---

## 6. Prospect en cours : Abattoirs Sahel

- Complexe avicole intégré (Had Soualem, Berrechid) : fermes propres et éleveurs agrégés, deux abattoirs, produits élaborés, charcuterie, distribution. ISO 9001, FSSC 22000.
- Besoins probables : conformité DGI (grande entreprise, vague 2026), EDI avec les enseignes, **produits à poids variable** (GS1-128, poids net, lot, DLC), avis d'expédition avec SSCC, factures d'achat avec les éleveurs.
- Inconnues à lever : ERP (SAP ? Sage X3 ?), volumes, enseignes en EDI, prestataire actuel (Weexa via LabelVie ?), solution DGI envisagée.
- Proposition : fourchette Enterprise (mise en service 60 000 à 120 000, abonnement 15 000 à 25 000 / mois), portail éleveurs inclus, **pilote de 3 mois** sur un flux.
- Ne pas survendre : AS2 et EANCOM pas encore livrés, clearance DGI simulée.

---

## 7. Architecture cible (résumé)

```text
CONNECT -> INGEST -> DETECT -> PARSE -> NORMALIZE -> VALIDATE -> TRANSFORM -> ENRICH
        -> GENERATE -> VALIDATE OUTPUT -> CLEAR (DGI) -> ROUTE -> ACKNOWLEDGE -> MONITOR -> REPLAY

Connector != Parser != Mapper != Rules != Generator != ClearanceGateway != Delivery
```

- Aucun format n'est transformé directement en un autre : tout passe par un **modèle canonique versionné** aligné EN 16931 (`Invoice`, `CreditNote`, `Order`, `DespatchAdvice`, `StockMovement`), étendu pour le Maroc (ICE, IF, RC) et GS1 (GLN, GTIN, SSCC, lot, DLC, poids net).
- PostgreSQL = source de vérité ; artefacts immuables (cible S3/MinIO avec Object Lock) ; Redis limité au cache et aux verrous.
- Chaque message : `messageId`, `correlationId`, SHA-256, `configurationSnapshotId`, événements, artefacts RAW / PARSED / CANONICAL / OUT / ACK / CLEARANCE.
- Erreurs structurées : `errorCode`, `stage`, `retryable` (TRANSIENT / PERMANENT), `field`, `rootCause`, `userMessage` fr/ar, `suggestedAction`.
- L'IA propose (mappings, diagnostics) ; un humain valide ; le runtime déterministe exécute.

Détail complet : `AGENTS.md` dans le dépôt.

---

## 8. État actuel

**Livré** : multi-tenancy `company_id`, JWT, permissions dynamiques, rôles `SYSADMIN` et `ADMIN` ; factures, partenaires, clearance simulée, intégrations, stocks ; réception XML stock en 5 étapes ; IDoc INVOIC02 → XML canonique, EDIFACT D96A, UBL 2.1 ; artefacts IN/INTERMEDIATE/OUT en PostgreSQL ; messageId, correlationId, SHA-256, version de mapping, événements ; détection de doublon et replay explicite ; dashboard Next.js responsive.

**Pas encore livré** : interfaces Parser/Mapper/Generator séparées, object storage, file/retry/DLQ, ACK partenaire, **AS2**, **EANCOM**, lineage champ par champ, Mapping Studio, portail PME, clearance DGI réelle.

**Prototypes hors code** (espace de travail Claude, à rapatrier dans `docs/`) :
- maquette UI interactive (cockpit + Journal, environnement client, Mapping Studio) ;
- test AS2 factice Node.js + OpenSSL : 9/9 contrôles (signature, chiffrement, MDN signé, MIC, doublon, refus). Leçon : normaliser les fins de ligne en CRLF avant de signer, sinon MIC et signature échouent.

---

## 9. Roadmap

| Lot | Contenu | Remarque |
| --- | --- | --- |
| 0 | Audit du code existant → `docs/audit-2026.md` | Aucune modification |
| 1 | Cœur du runtime : interfaces, machine d'états, erreurs, ConfigurationSnapshot | Non-régression d'abord |
| 2 | Modèle canonique marocain + GS1, référentiel TVA daté, CII, doublons métier | |
| 3 | Portail PME + design system | Priorité produit (80 %) |
| 4 | Outbox, file, retry, DLQ, ArtifactStore S3 | RabbitMQ prévu ; une file PostgreSQL est une alternative valable |
| 5 | API publique, Idempotency-Key, webhooks, clés API | Offre éditeurs |
| 6 | Connecteurs : EDIFACT/EANCOM entrant, SFTP, **AS2**, SAP, ACK/NACK | AS2 via as2-lib (phax) ou Camel AS2 |
| 7 | Mapping Studio | Voir §10 |
| 8 | Plateforme de production : OTel, OIDC, Vault, sauvegardes, CI/CD | |
| 9 | Adaptateur DGI réel | Dès publication des spécifications |

Plus tard : certification **Drummond AS2** (≈ 10 à 30 k USD par campagne, estimation non vérifiée), adhésion / partenariat **GS1 Maroc**.

---

## 10. Décisions produit et UI

**Identité** : logo « mystix » (mot en minuscules), violet **#8500DE** ; version blanche sur fond sombre. Icône : le « m » blanc sur pastille violette. Police de la maquette : IBM Plex Sans + IBM Plex Mono. Neutres légèrement teintés violet ; vert / orange / rouge réservés aux états.

**Principes UX** :
- chaque écran répond en 3 secondes à « Où en est-on ? Qu'est-ce qui bloque ? Que dois-je faire ? » ;
- parler métier : documents, partenaires, montants en MAD, échéances, pas seulement messages et latences ;
- erreurs actionnables avec diagnostic, cause (dans Mystix, chez l'émetteur ou dans les données) et action ;
- confirmation dans la page avant toute action irréversible (rejeu, publication) ;
- français + arabe (RTL), clair et sombre, clavier (Ctrl K), accessibilité WCAG 2.2 AA.

**Écrans de référence** :
1. **Cockpit** : bandeau du jour (documents bloqués, montant bloqué, clearance DGI, à facturer) ; **Journal** des 20 derniers faits marquants tous clients (inspiré du suivi de messages Seeburger), filtrable par gravité et client ; échéances ; « où ça bloque » ; environnements clients.
2. **Environnement client** : flux IN/OUT en liste + détail ; étapes ; diagnostic ; fichiers IN / MAPPING XSL / CANONIQUE / OUT / DGI / MDN avec la ligne fautive surlignée ; rejouer, rejeu à blanc ; onglets partenaires, mappings, connecteurs et certificats.
3. **Mapping Studio** : source avec valeurs réelles | règles avec cible générée depuis le XSD, formats déclarés, **CDATA en option par champ**, références EN 16931 (BT-x) | sortie en direct validée XSD ; fixtures qui bloquent la publication ; diff avec la version publiée ; suggestion IA à accepter ou refuser.

---

## 11. Questions ouvertes

- Publication des spécifications DGI (API, format de réponse, signature) et contenu de la LF 2027.
- File de messages : RabbitMQ ou file PostgreSQL au Lot 4.
- Programme partenaires GS1 Maroc : existence, conditions, coût.
- Tarif Drummond réel (demande de devis).
- Retours terrain sur la grille tarifaire (Sahel et 10 prospects).
