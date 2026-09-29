# Audit du code Mystix — 2026 (Lot 0)

> Squelette pour `docs/audit-2026.md`. Audit en **lecture seule** : aucune modification du dépôt hormis ce fichier et la section « Commandes du projet » de `CLAUDE.md`.
> Chaque affirmation cite un chemin (`fichier:ligne`) ou la sortie d'une commande. Marquer « non vérifié » ce qui n'a pas été exécuté.

## 1. Synthèse (10 lignes max)

## 2. Structure
- Modules / dossiers, build tool (Maven/Gradle), versions Java, Spring Boot, Next.js
- Chemins réels à reporter dans `ci.yml` (`BACKEND_DIR`, `FRONTEND_DIR`), `dependabot.yml`, `.gitattributes` (fixtures)

## 3. Données
- Entités, migrations (Flyway/Liquibase ? chemin ?), isolation `company_id`

## 4. API
- Endpoints (méthode, chemin, rôle requis)

## 5. Flux existants
- IDoc INVOIC02 → XML canonique → UBL 2.1 : classes, XSLT, fixtures existantes
- EDIFACT D96A, réception XML stock, clearance simulée

## 6. Frontend
- Pages, appels API, variable d'URL de l'API, i18n, thème

## 7. Tests
- Nombre, type, couverture approximative, commande, résultat

## 8. Configuration
- Clés `application*.yml` (sans valeurs secrètes), variables d'environnement attendues, ports

## 9. Commandes réelles (à reporter dans `CLAUDE.md`)
| Action | Commande | Résultat |
| --- | --- | --- |

## 10. Écarts avec AGENTS.md

## 11. Risques (à reporter dans `docs/pmo/risks.md`)

## 12. Proposition d'ordre pour le Lot 1
