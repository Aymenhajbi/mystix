# Definition of Done — Mystix

Une tâche est **terminée** seulement si toutes les cases applicables sont cochées dans la PR.

## Code
- [ ] Plan court validé avant de coder (fichiers, tests, risques)
- [ ] Branche dédiée, Conventional Commits en anglais
- [ ] Backend : build + tests verts (commande exacte citée dans la PR)
- [ ] Frontend : lint + typecheck + build verts
- [ ] CI verte sur le dernier commit
- [ ] Aucune migration appliquée modifiée ; aucun test supprimé ou désactivé
- [ ] Toute nouvelle dépendance est nommée et justifiée dans la PR

## Métier / EDI
- [ ] Fixture IN → OUT attendu ajoutée pour tout changement de parsing, mapping ou génération
- [ ] Sortie validée contre le XSD (UBL 2.1, CII, EANCOM selon le cas)
- [ ] Isolation tenant (`company_id`) testée sur les nouvelles requêtes
- [ ] Erreurs structurées : `errorCode`, `stage`, `retryable`, `userMessage` fr/ar, `suggestedAction`
- [ ] Rejeu idempotent (même entrée = même résultat, pas de doublon)

## Fiscal / conformité
- [ ] Aucune règle DGI inventée : tout paramètre fiscal est configurable et marqué `TODO(DGI-SPEC)`
- [ ] Taux de TVA lus depuis le référentiel daté, jamais en dur
- [ ] Clearance présentée comme **simulée** dans l'UI, l'API et la doc

## Sécurité
- [ ] Aucun secret, `.env`, clé privée ou certificat privé commité (CI `secrets` + `guardrails` verte)
- [ ] Données de test synthétiques ou anonymisées

## UI (si écran)
- [ ] Tokens de design uniquement ; `#8500DE` pour la marque, vert/orange/rouge pour les états
- [ ] FR + AR (RTL), clair + sombre, navigation clavier
- [ ] Confirmation dans la page avant toute action irréversible (rejeu, publication)

## Documentation
- [ ] `roadmap.md` à jour si le statut d'un lot change
- [ ] ADR écrit si décision d'architecture
- [ ] Compte rendu honnête : ce qui passe, ce qui échoue, ce qui n'a pas été vérifié
