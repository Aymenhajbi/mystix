# ADR-0013 — Authentification du portail et de l'API

- Statut : **Proposé** (2026-10-07)
- Date : 2026-10-07
- Lot : 8 (plateforme de production), avancé pour retirer la société « de dev » du portail

## Contexte
Jusqu'ici, le portail désignait la société par `MYSTIX_PORTAL_COMPANY_ID` ou par un cookie, et l'API faisait confiance à l'en-tête `X-Mystix-Company-Id`. N'importe quel appelant pouvait donc lire les données de n'importe quelle société. Le Lot 8 prévoit OIDC ; en attendant, il faut des comptes locaux sûrs, sans dépendance nouvelle côté backend.

## Décision
1. **Comptes locaux** (`app_user`, migration V15) :
   - e-mail unique en minuscules ;
   - mot de passe haché en **PBKDF2-HMAC-SHA256, 600 000 itérations**, sel de 16 octets, via le JDK seul ;
   - 12 caractères au minimum.
2. **Rôles** :
   - `platform_role = OPERATOR` : voit toutes les sociétés et seul accède à `/api/v1/admin/**` ;
   - `company_membership` (`ADMIN`, `MEMBER`) : rattache un utilisateur à une ou plusieurs sociétés.
3. **Sessions opaques** (`auth_session`) :
   - jeton aléatoire de 32 octets remis au client ; **seul son SHA-256 est stocké** ;
   - durée absolue 12 h, fermeture après 2 h d'inactivité, révocation à la déconnexion ;
   - la session porte la **société active** ; le changement de société passe par `PUT /api/v1/auth/session/company`.
4. **Protection contre la force brute** :
   - verrouillage du compte 15 min après 5 échecs ;
   - même erreur et même temps de réponse pour un e-mail inconnu et un mauvais mot de passe (hachage factice).
5. **Filtre** `AuthFilter` :
   - exige `Authorization: Bearer` quand `mystix.auth.required=true` (défaut ; `false` en profil `dev` et dans les tests existants) ;
   - remplace `X-Mystix-Company-Id` par la société active ; une société demandée et non accessible renvoie `AUTH_FORBIDDEN` (403).
6. **Première installation** : `GET`/`POST /api/v1/auth/setup` crée le premier opérateur, puis se ferme (`AUTH_SETUP_DONE`, 409). La création est sérialisée par un verrou de table.
7. **Portail** :
   - jeton dans un cookie `mystix-session` HttpOnly, `SameSite=Lax`, `Secure` en production, jamais lisible en JavaScript ;
   - le proxy Next.js renvoie vers `/{lang}/login?next=…` sans cookie ; `next` n'accepte qu'un chemin du portail (pas de redirection ouverte) ;
   - pages `login` et `setup` hors du shell, avec une scène three.js décorative (`LoginArt`, `aria-hidden`, désactivée sous 961 px et avec `prefers-reduced-motion`).

## Livré
- Backend : V15, `PasswordHasher`, `AuthService`, `AuthFilter`, `AuthController`, codes d'erreur fr/ar. Test `AuthApiTests` : parcours complet, isolation entre sociétés, verrouillage.
- Portail : connexion, première installation, menu compte et déconnexion, changement de société par la session.

## Prévu (non livré)
- OIDC (Lot 8), qui remplacera ou complétera les comptes locaux.
- Écran d'administration des utilisateurs (l'API `POST /api/v1/auth/users` existe, réservée aux opérateurs).
- Réinitialisation du mot de passe (aujourd'hui : par l'administrateur).
- Jetons d'API pour les intégrations machine à machine ; double authentification.
- Purge des sessions expirées.

## Options écartées
| Option | Pourquoi écartée |
| --- | --- |
| Spring Security | Dépendance lourde pour un besoin couvert par un filtre ; à revoir avec OIDC |
| JWT signé sans état | Révocation à la déconnexion impossible sans liste noire ; jeton opaque plus simple |
| bcrypt / Argon2 | Bibliothèque externe ; PBKDF2 au niveau recommandé par l'OWASP est dans le JDK |
| Jeton en `localStorage` | Exposé à tout script injecté ; cookie HttpOnly préféré |
