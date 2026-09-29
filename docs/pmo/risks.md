# Registre des risques — Mystix

> Probabilité / impact : 1 (faible) à 3 (fort). Score = P × I. Revue hebdomadaire. Ouvert le 29/09/2026.

| ID | Risque | P | I | Score | Réponse | Déclencheur de revue |
| --- | --- | --- | --- | --- | --- | --- |
| R-01 | Spécifications DGI publiées tard ou très différentes de l'hypothèse (format, signature, API) | 3 | 3 | 9 | Tout derrière `ClearanceGateway` (ADR-0001) ; paramètres fiscaux configurables, `TODO(DGI-SPEC)` ; veille sources officielles | Publication DGI ou LF 2027 |
| R-02 | Refactoring Lot 1 casse le flux IDoc INVOIC02 → UBL 2.1 en production | 2 | 3 | 6 | Tests de non-régression octet pour octet **avant** toute extraction d'interface | Toute PR du Lot 1 |
| R-03 | Survente au prospect Sahel (AS2, EANCOM, clearance réelle) | 2 | 3 | 6 | Tableau livré/prévu/hypothèse dans chaque document commercial ; relecture fondateur | Chaque proposition envoyée |
| R-04 | Fuite de clé privée AS2 ou de secret (`tools/as2-test/certs/`, `.env`) | 2 | 3 | 6 | `.gitignore`, job CI `guardrails` + gitleaks ; régénération des clés de test ; Vault/KMS au Lot 8 | Tout ajout sous `tools/` ou `certs/` |
| R-05 | Corruption des fixtures EDI par conversion CRLF/LF (Windows) → SHA-256/MIC faux | 2 | 2 | 4 | `.gitattributes` (`-text` sur fixtures), `core.autocrlf=input` | Test de non-régression rouge sans changement de code |
| R-06 | Fuite de données entre tenants (`company_id`) | 1 | 3 | 3 | Tests d'isolation obligatoires (DoD) ; à auditer au Lot 0 | Nouvelle requête ou endpoint |
| R-07 | MinIO : images Docker communautaires plus publiées depuis fin 2025 | 3 | 1 | 3 | Tag figé en dev ; alternatives S3 compatibles à évaluer au Lot 4 (image Chainguard, autre stockage) ; code dépendant de l'API S3, pas de MinIO | Début du Lot 4 |
| R-08 | Fondateur seul : goulot sur revue et décisions | 3 | 2 | 6 | Lots courts, plan validé avant code, CI qui bloque, agents en lecture seule par défaut | Plus de 3 PR en attente |
| R-09 | Concurrence installée (Weexa/iXPath/EIMA) sur les grands comptes | 2 | 2 | 4 | Différenciation par l'approche (Mapping Studio, portail fournisseurs, DGI natif), pas de dénigrement | Retour terrain prospects |
| R-11 | File PostgreSQL saturée à fort volume (contention, vacuum) | 1 | 2 | 2 | Interface `MessageQueue` ; métriques profondeur/âge ; critères de réexamen de l'ADR-0003 | Test de charge ou volumes Sahel connus |
| R-10 | Grille tarifaire non validée | 3 | 2 | 6 | Tester auprès de Sahel + 10 prospects ; ajuster avant publication | Chaque retour prospect |

## Questions ouvertes (issues du brief §11)

| Question | Propriétaire | Bloque |
| --- | --- | --- |
| Spécifications DGI (API, format de réponse, signature) et contenu LF 2027 | Fondateur (veille) | Lot 9 |
| Programme partenaires GS1 Maroc : existence, conditions, coût | Fondateur | Communication GS1 |
| Tarif Drummond réel (devis) | Fondateur | Certification AS2 |
| Retours terrain grille tarifaire | Fondateur | Offre commerciale |
