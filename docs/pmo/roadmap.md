# Roadmap Mystix — pilotage par lots

> Source : `docs/BRIEF-PROJET-MYSTIX.md` §8–9 (29/09/2026). Légende statut : **Livré** (dans le code, à confirmer par l'audit), **Prévu** (planifié, rien de livré), **Hypothèse** (dépend d'une information non disponible).
> Aucune date n'est fixée ici : les dates se posent après l'audit Lot 0, quand la charge réelle est connue.

## Vue d'ensemble

| Lot | Contenu | Statut | Dépend de | Critère de sortie (vérifiable) |
| --- | --- | --- | --- | --- |
| 0 | Audit lecture seule → `docs/audit-2026.md` | Prévu (en cours) | JDK 21 installé | Audit relu ; « Commandes du projet » de `CLAUDE.md` remplie et exécutée une fois avec succès |
| — | Socle DevOps : compose dev, `.env.example`, CI, `.gitattributes` | Prévu (kit prêt) | Lot 0 (chemins, build tool) | CI verte sur `main` ; `dev-up.ps1` démarre PostgreSQL ; backend démarre contre cette base |
| 1 | Cœur runtime : interfaces Parser / CanonicalMapper / Generator, machine d'états, erreurs structurées, ConfigurationSnapshot | Prévu | Lot 0, socle | Tests de non-régression IDoc INVOIC02 → UBL 2.1 écrits **avant** refactoring, verts avant et après, sortie identique octet pour octet |
| 2 | Modèle canonique marocain + GS1, référentiel TVA daté, CII, doublons métier | Prévu | Lot 1 | GTIN/GLN/SSCC validés (mod 10) ; taux TVA lus depuis un référentiel daté, aucun taux en dur ; CII généré et validé XSD |
| 3 | Portail PME + design system | **Partiel** (2026-10-05) : portail fr/ar, cockpit, logs, saisie de facture (S et Z, vendeur = société active) → UBL 2.1 + clearance simulée. Reste : E/O avec motif, aperçu des totaux, audit WCAG | Lot 2 | Une TPE crée, valide (clearance simulée) et télécharge une facture UBL ; FR + AR (RTL) ; WCAG 2.2 AA sur les écrans du parcours |
| 4 | Outbox, file PostgreSQL (`SKIP LOCKED`), retry, DLQ, ArtifactStore S3 | Prévu | Lot 1 ; ADR-0003 (file PostgreSQL, acceptée) | Un message en échec transitoire est rejoué automatiquement ; un échec permanent arrive en DLQ ; artefacts immuables dans MinIO |
| 5 | API publique, Idempotency-Key, webhooks, clés API | Prévu | Lot 4 | Double POST avec même clé = une seule facture ; webhook signé et rejouable |
| 6 | Connecteurs : EDIFACT/EANCOM entrant, SFTP, AS2, SAP, ACK/NACK | Prévu | Lots 2 et 4 | AS2 : les 9 contrôles du test factice passent contre `tools/as2-test` via la bibliothèque retenue (ADR-0004) |
| 7 | Mapping Studio | **Partiel** (2026-10-02) : versions de mapping par flux, test à blanc bloquant, éditeur glisser-déposer (ADR-0008). Reste : diff et rollback | Lots 1, 2 | Publication bloquée si une fixture échoue ; diff et rollback fonctionnels |
| 8 | Plateforme de production : OTel, OIDC, Vault, sauvegardes, CI/CD | Prévu | Lots 4–5 | Restauration de sauvegarde testée ; clés privées hors base |
| 10 | Stock : registre de mouvements, 5 états (disponible, réservé, transit, quarantaine, consignation), ATP, réconciliation INVRPT (ADR-0011) | **Partiel** (2026-10-05) : moteur et API sur événements canoniques. Reste : parseurs DESADV/RECADV/ORDERS/INVRPT (Lot 6), valorisation, écrans | Lots 1, 6 (EDIFACT) | Un snapshot INVRPT aligne le stock sans écraser les flux postérieurs ; écart tracé |
| 9 | Adaptateur DGI réel | **Hypothèse** | Publication des spécifications DGI | Non planifiable avant publication. Jusque-là : `ClearanceGateway` simulée (ADR-0001) |

## Chemin critique proposé

`Lot 0 → socle DevOps → Lot 1 (non-régression d'abord) → Lot 2 → { Lot 3 ∥ Lot 4 } → Lot 6 (AS2/EANCOM) → Lot 5 → Lot 7 → Lot 8`

Justification : le Lot 3 (portail PME) porte l'objectif des 80 % ; le Lot 6 porte le prospect Sahel (poids variable, SSCC, EANCOM). Les deux dépendent du canonique (Lot 2). Arbitrage à faire par le fondateur si Sahel signe un pilote : avancer Lot 4 + 6 avant Lot 3.

## Jalon commercial (hypothèse)

| Jalon | Condition | Ce qui peut être promis |
| --- | --- | --- |
| Pilote Sahel 3 mois (proposé, non signé) | Lots 1–2 livrés, Lot 6 partiel | Un flux (à choisir avec le client), clearance **simulée**. AS2 et EANCOM ne sont **pas livrés** à date : ne pas les promettre avant le critère de sortie du Lot 6 |

## Cadence

- Une branche par tâche (`feat/<lot>-<sujet>`), PR avec le modèle `.github/pull_request_template.md`.
- Revue hebdomadaire (30 min) : statut par lot, risques (`risks.md`), décisions (`adr/`).
- Mise à jour de ce fichier à chaque changement de statut, avec la date.
