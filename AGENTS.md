# AGENTS.md — Mystix

> Règles d'architecture et de conduite pour **tout agent de code** (Claude Code, Codex, Copilot…) et tout humain qui contribue.
> Les règles propres à Claude Code sont dans `CLAUDE.md`, qui importe ce fichier.

## 1. Produit

Mystix est une plateforme marocaine d'**intégration EDI**, de **facturation électronique** et de **clearance DGI**.
Elle reçoit des documents métier (IDoc SAP, EDIFACT/EANCOM, XML, CSV/Excel, saisie portail), les valide, les transforme
et les émet (UBL 2.1, CII, EANCOM) vers des partenaires (AS2, SFTP, API) et vers la DGI.

Utilisateurs : TPE/PME via un portail web, grands comptes et éditeurs via API et connecteurs, intégrateurs via le Mapping Studio.

## 2. Stack (imposée, ne pas proposer d'alternative)

| Couche | Choix |
| --- | --- |
| Backend | Java 21, Spring Boot 4.1, Maven (wrapper `mvnw`) — dossier `backend/` |
| Base | PostgreSQL 16, migrations **Flyway** (`backend/src/main/resources/db/migration`) |
| File de travail | Table PostgreSQL + `FOR UPDATE SKIP LOCKED` derrière `MessageQueue` (ADR-0003) |
| Frontend | Next.js 16 (App Router), TypeScript strict, npm — dossier `frontend/` |
| Stockage d'artefacts | API S3 (MinIO en dev, profil `storage`) — Lot 4 |
| Tests backend | JUnit 5, AssertJ, Testcontainers PostgreSQL |

## 3. Architecture

### 3.1 Chaîne de traitement (ADR-0002)
Aucun format n'est transformé directement en un autre :

```
RAW ─Parser→ PARSED ─CanonicalMapper→ CANONICAL (versionné, EN 16931 + Maroc + GS1) ─Generator→ OUT
```

- Chaque étape stocke son artefact (RAW / PARSED / CANONICAL / OUT) avec la version du canonique.
- Le canonique est aligné EN 16931 (références `BT-x` en Javadoc), étendu Maroc (ICE, IF, RC, patente, CNSS)
  et GS1 (GLN, GTIN, SSCC, lot, DLC, poids net).
- Toute évolution du canonique = nouvelle version. Jamais de modification silencieuse.

### 3.2 Modules (packages sous `ma.mystix`)
Monolithe modulaire. Un package de premier niveau = un module ; un module n'accède pas aux tables d'un autre.

| Package | Rôle |
| --- | --- |
| `tenant` | Sociétés (`company`), utilisateurs, rôles |
| `invoice` | API facture : JSON → canonique → UBL 2.1, contrôles XSD et EN 16931 |
| `message` | Messages, artefacts, machine d'états, historique |
| `canonical` | Modèle canonique versionné (pur Java, sans Spring) |
| `format.*` | Parsers, mappers, generators par format (`ubl`, `cii`, `idoc`, `edifact`, `csv`) |
| `referential` | Référentiels datés : TVA, devises, unités, pays |
| `clearance` | `ClearanceGateway` + implémentation **simulée** (ADR-0001) |
| `queue` | `MessageQueue`, outbox, retry, DLQ |
| `connector.*` | AS2, SFTP, webhooks |
| `shared` | Erreurs structurées, identifiants, horloge, utilitaires transverses |

### 3.3 Invariants
- **Multi-tenant** : toute table métier porte `company_id NOT NULL` ; toute requête filtre par `company_id` ;
  chaque nouvel accès aux données a un test d'isolation (tenant A ne voit jamais B).
- **Idempotence** : même entrée = même résultat, sans doublon (clé naturelle ou `Idempotency-Key`).
- **Erreurs structurées** : `errorCode`, `stage`, `retryable`, `userMessage` (fr, ar), `suggestedAction`.
  Pas d'exception brute renvoyée à l'utilisateur.
- **Temps** : `Clock` injecté, stockage en `TIMESTAMPTZ`, fuseau métier `Africa/Casablanca`.
- **Montants** : `BigDecimal` uniquement, arrondi explicite, jamais `double`.
- **Fixtures EDI** : octet pour octet (`.gitattributes`). Un CRLF/LF change le SHA-256, le MIC, la signature.

## 4. Règles fiscales DGI — non négociables

- Les spécifications techniques DGI **ne sont pas publiées**. Ne **jamais** inventer un format, un champ, un code
  retour, une règle de calcul ou un taux.
- Tout paramètre fiscal est **configurable** et marqué `TODO(DGI-SPEC)` dans le code.
- Les taux de TVA viennent du référentiel daté (`referential`), jamais d'une constante.
- La clearance est **simulée** (`MYSTIX_CLEARANCE_MODE=simulated`) et affichée comme telle dans l'UI, l'API et la doc.

## 5. Base de données

- Une migration appliquée est **immuable**. Toute correction = nouvelle migration `V<n>__<sujet>.sql`.
- Clés primaires `UUID` (générées côté application), `created_at`/`updated_at` en `TIMESTAMPTZ`.
- Contraintes en base (NOT NULL, FK, UNIQUE, CHECK) plutôt qu'en code seul.

## 6. Sécurité

- Jamais de secret, `.env`, clé privée, keystore ou certificat privé dans git, un log ou une conversation.
- Données de test **synthétiques**. Aucune donnée réelle de client, ni d'un ancien employeur du fondateur.
- Ports de dev liés à `127.0.0.1`.

## 7. Git et livraison

- Jamais de commit direct sur `main`. Une branche par tâche : `feat/<lot>-<sujet>`, `fix/…`, `chore/…`.
- Conventional Commits en anglais. PR avec `.github/pull_request_template.md`.
- Terminé = `docs/pmo/definition-of-done.md` coché.
- Nouvelle dépendance : nommée et justifiée dans la PR.
- Aucun test supprimé ou désactivé pour faire passer la CI.

## 8. Commandes du projet

Vérifiées le 2026-09-29 sur Windows 11 (PowerShell, depuis la racine du dépôt) :

| Action | Commande |
| --- | --- |
| Vérifier le poste | `powershell -ExecutionPolicy Bypass -File scripts\check-env.ps1` |
| Démarrer PostgreSQL | `scripts\dev-up.ps1` |
| Arrêter les services | `scripts\dev-down.ps1` |
| Backend : build + tests | `cd backend; .\mvnw.cmd -B verify` (Docker requis pour Testcontainers) |
| Backend : lancer | `scripts\run-backend.ps1` (charge `.env`, profil `dev`, port 8080) |
| Frontend : installer | `cd frontend; npm ci` |
| Frontend : vérifier | `cd frontend; npm run lint; npm run typecheck; npm run build` |
| Frontend : lancer | `cd frontend; npm run dev` (http://localhost:3000, redirige vers `/fr` ou `/ar`) |
| Logs métier (par société) | Portail `/fr/logs`, ou `GET /api/v1/logs?level=ERROR` (en-tête `X-Mystix-Company-Id`) |
| Logs serveur | `backend/logs/mystix-backend.log` (rotation quotidienne et à 10 Mo) ; chercher le `X-Request-Id` affiché dans le portail |
| Portail : configurer | `Copy-Item frontend\.env.example frontend\.env.local`, puis `MYSTIX_PORTAL_COMPANY_ID` = id d'une société (provisoire, avant authentification) |

## 9. Documentation

- Décision d'architecture → ADR dans `docs/pmo/adr/`.
- Statut d'un lot → `docs/pmo/roadmap.md`, avec la date.
- Toujours distinguer **livré**, **prévu** et **hypothèse**. Ne rien survendre, surtout dans les documents commerciaux.
