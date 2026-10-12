# ADR-0015 — Canaux de communication partagés par Mystix et Atelys

- Statut : **Proposé** (2026-10-12)
- Date : 2026-10-12
- Lots : 6 (transports), 11 (Atelys)

## Contexte
Un environnement client échange avec ses partenaires par AS2, SFTP, FTP/FTPS, HTTPS ou webhook. Jusqu'ici, un flux nommait son canal par un code (`AS2`, `SFTP`…) sans aucun paramètre de connexion. Atelys a aussi besoin de canaux (un fournisseur qui dépose ses DESADV, un transporteur qu'on notifie), et ces canaux sont souvent les mêmes que côté EDI. Les configurer deux fois créerait deux vérités.

## Décision
1. **Une table, un service** : `comm_channel` dans le module `ma.mystix.connector`, API `/api/v1/channels`. Mystix (page de l'environnement client) et Atelys (Administration › Canaux) appellent la même API : un canal créé d'un côté se voit et se modifie de l'autre. Deux indicateurs `used_by_mystix` et `used_by_atelys` (au moins un des deux) disent qui l'utilise ; `?app=MYSTIX|ATELYS` filtre la liste.
2. **Paramètres vérifiés par protocole** (`ChannelSettings`) : liste blanche de clés, types et valeurs par défaut écrites dans la configuration stockée. Une clé inconnue est refusée.
   - SFTP : hôte, port 22, utilisateur, clé privée ou mot de passe, empreinte de la clé serveur, répertoires, masque, scrutation, après lecture.
   - FTP / FTPS : idem, mode passif, transfert **binaire par défaut** (l'ASCII réécrit les fins de ligne et casse les fixtures EDI), TLS explicite (21) ou implicite (990).
   - AS2 (RFC 4130) : URL partenaire (obligatoire pour émettre), AS2-From / AS2-To (1 à 128 caractères ASCII imprimables), signature, chiffrement, compression, MDN synchrone, asynchrone (URL obligatoire) ou aucun, MDN signé, référence du certificat partenaire.
   - HTTPS : URL `https`, méthode, authentification. Webhook : URL `https`, événements.
3. **Aucun secret en base** : `credential_ref` nomme l'entrée du coffre (mot de passe, clé privée, certificat). Un canal SFTP, FTP, FTPS ou AS2 (ou HTTPS authentifié) ne peut pas être `ACTIVE` sans cette référence (`CHANNEL_INCOMPLETE`).
4. **Statuts** : `DRAFT`, `ACTIVE`, `DISABLED`. Création, modification et suppression : administrateur de l'environnement ou opérateur (`Access.requireManager`).
5. **Livré / prévu** : la configuration est livrée et testée ; **l'ouverture des connexions (AS2, SFTP, FTP/FTPS) reste au Lot 6**. L'API le dit (`transport: CONFIGURATION_ONLY`) et les écrans l'affichent.

## Environnement Atelys de chaque client (amendement de l'ADR-0014)
Chaque société Mystix a son environnement Atelys, créé dans la transaction d'inscription (`CompanyRegistered`) et, au démarrage, pour les sociétés existantes : unités usuelles (U, KG, M, M2, M3, L) et, si la société n'a aucun site, un site PRINCIPAL avec un magasin MAG et un emplacement par type (réception, stockage, production, quarantaine, expédition) plus TRANSIT. Table `atelys_environment` ; idempotent, verrou consultatif par société.

## Prévu
- Rattacher les flux à un canal (`exchange_flow` → `comm_channel`) au lieu d'un simple code.
- Transports du Lot 6, test de connexion depuis l'écran, journal des échanges par canal.
- Lecture des secrets depuis le coffre retenu à l'exploitation.

## Options écartées
| Option | Pourquoi écartée |
| --- | --- |
| Une configuration par application | Deux vérités pour le même serveur SFTP ou le même partenaire AS2 |
| Stocker le mot de passe chiffré en base | La clé de chiffrement finirait à côté des données ; le coffre est fait pour ça |
| Paramètres libres (JSON sans contrôle) | Un mot de passe ou une valeur fausse passerait sans bruit |
