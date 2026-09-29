## Lot / tâche
Lot : <!-- 0..9 --> · Branche : `feat/<lot>-<sujet>` · Issue : #

## Avant / Après
**Avant :**

**Après :**

## Comment
<!-- Fichiers touchés, choix techniques, dépendances ajoutées (à justifier). -->

## Tests (dire exactement ce qui a été lancé)
- [ ] Backend build + tests : `commande` → résultat
- [ ] Frontend lint + build : `commande` → résultat
- [ ] Non-régression fixtures (IN → OUT attendu) : résultat
- Non vérifié :

## Checklist Mystix
- [ ] Aucune migration appliquée modifiée (nouvelle migration uniquement)
- [ ] Aucun test supprimé ou désactivé
- [ ] Aucun secret, `.env`, clé ou certificat privé ajouté
- [ ] Règles DGI : rien d'inventé, paramétrable, marqué `TODO(DGI-SPEC)`
- [ ] Isolation tenant (`company_id`) vérifiée sur les nouvelles requêtes
- [ ] Erreurs structurées (`errorCode`, `stage`, `retryable`, `suggestedAction`)
- [ ] Textes UI en français (+ arabe si écran utilisateur), tokens de design, pas de style ad hoc
- [ ] Doc / ADR mis à jour si décision d'architecture
