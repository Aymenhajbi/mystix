# Poste de développement Mystix (Windows 11)

> Statut : **prévu**. Les commandes backend/frontend ci-dessous sont des hypothèses tant que l'audit Lot 0 n'a pas rempli la section « Commandes du projet » de `CLAUDE.md`. Remplacer les lignes marquées `HYPOTHÈSE` par les commandes réelles.

## 1. Prérequis (état constaté le 29/09/2026)

| Outil | État | Action |
| --- | --- | --- |
| Git 2.55, Docker 29.6, Node 24 / npm 11, OpenSSL 3.5 | OK | — |
| **JDK 21** | **absent** | `winget install EclipseAdoptium.Temurin.21.JDK`, puis rouvrir le terminal |
| Maven / Gradle | non requis | le wrapper du dépôt (`mvnw` / `gradlew`) suffit |

Vérification : `powershell -ExecutionPolicy Bypass -File scripts\check-env.ps1`

Réglage Git recommandé une fois pour toutes (les fixtures EDI sont aussi protégées par `.gitattributes`) :

```powershell
git config --global core.autocrlf input
```

## 2. Services locaux

```powershell
Copy-Item .env.example .env        # puis mettre des mots de passe locaux jetables
scripts\dev-up.ps1                 # PostgreSQL 16 sur 127.0.0.1:5432 (base + file de travail)
scripts\dev-up.ps1 -Storage        # + MinIO (à partir du Lot 4)
scripts\dev-down.ps1               # arrêt, données conservées
scripts\dev-down.ps1 -Purge        # arrêt + suppression des volumes (demande confirmation)
```

Les ports sont liés à `127.0.0.1` uniquement : rien n'est exposé sur le réseau local.

## 3. Backend (Spring Boot, Java 21)

```powershell
cd backend                                   # HYPOTHÈSE : dossier à confirmer
.\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=dev   # HYPOTHÈSE si Maven
.\gradlew.bat bootRun --args='--spring.profiles.active=dev' # HYPOTHÈSE si Gradle
```

Spring Boot lit `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` depuis l'environnement (relaxed binding). Sous PowerShell, `.env` n'est pas chargé automatiquement : soit l'IDE le charge (IntelliJ : plugin EnvFile ; VS Code : `envFile` dans `launch.json`), soit un `application-dev.yml` local non commité pointe vers `localhost:5432`.

## 4. Frontend (Next.js)

```powershell
cd frontend          # HYPOTHÈSE : dossier à confirmer
npm ci
npm run dev          # http://localhost:3000
```

## 5. Partenaire AS2 simulé (Lot 6)

Le dossier `tools/as2-test/` (Node.js) sert de cible locale sur `http://127.0.0.1:4080/as2`.
**Ne jamais committer `tools/as2-test/certs/`** : il contient des clés privées. Régénérer les clés localement avec OpenSSL ; `.gitignore` et la CI (`guardrails`) bloquent ce dossier.

## 6. Règles qui s'appliquent au poste

- `.env`, clés, keystores : jamais commités, jamais affichés dans un log ou une conversation avec un agent.
- Fixtures EDI : octet pour octet. Un changement CRLF/LF modifie SHA-256, MIC et signature.
- Données de test : anonymisées ou synthétiques. Aucune donnée réelle d'un client, ni d'un ancien employeur.
