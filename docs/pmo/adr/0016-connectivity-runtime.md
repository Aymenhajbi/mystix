# ADR-0016 — Connectivity Manager : listeners, points d'entrée et moteur d'exécution

- Statut : **Proposé** (2026-10-15)
- Date : 2026-10-15
- Lots : 6 (transports), 4 (file), 8 (exploitation)

## Contexte
L'ADR-0015 a livré la **configuration** des canaux (`comm_channel` : AS2, SFTP, FTP/FTPS, HTTPS, webhook, partenaire, certificats, référence de coffre), sans aucun transport (`transport: CONFIGURATION_ONLY`). Il faut maintenant des points d'entrée qui **reçoivent réellement** des messages et les remettent au pipeline Mystix, avec un état fiable : ne jamais afficher « en écoute » sur la seule foi d'une configuration enregistrée.

## Décision
1. **Pas de seconde table de connexions** : une « connexion » du Connectivity Manager est un canal `comm_channel` (ADR-0015). Les partenaires restent ceux de `partner`, les flux ceux de `exchange_flow`, la file celle de l'ADR-0003.
2. **Point d'entrée** (`entry_point`) : où un message entre dans le pipeline. Il nomme le flux cible (`exchange_flow`, actif), le type de document et le format attendus. Un point d'entrée désactivé, ou dont le flux n'est pas actif, ne reçoit rien.
3. **Listener** (`listener`) : ressource d'exécution qui attend ou détecte des échanges sur un canal et les remet à un point d'entrée. Deux états distincts :
   - `desired_state` (`RUNNING` ou `STOPPED`) : ce que l'administrateur a demandé ;
   - `runtime_state` (`CONFIGURED`, `STARTING`, `RUNNING`, `DEGRADED`, `STOPPING`, `STOPPED`, `ERROR`, `DISABLED`) : ce que le moteur a constaté.
   `RUNNING` n'est écrit qu'après le démarrage effectif de l'adaptateur **et** un contrôle de santé réussi.
4. **Moteur commun** (`ListenerAdapter`) : `kind`, `preconditions`, `start`, `stop`, `health`. Le superviseur (un par processus) garde les poignées en mémoire, écrit un heartbeat périodique, et **réconcilie** au démarrage et à intervalle régulier : un listener `desired=RUNNING` sans poignée locale est redémarré (événement `RESTORED`). Les transitions se font sous `SELECT … FOR UPDATE` sur la ligne : deux « Démarrer » simultanés ne démarrent qu'une fois. Un heartbeat plus vieux que trois intervalles est affiché `DEGRADED` (`HEARTBEAT_MISSING`).
5. **Premier adaptateur réel : HTTP entrant** (`HTTP_INBOUND`). Route `POST /inbound/http/{listenerId}` servie par le backend, hors `/api/v1` (le partenaire n'a pas de session). Authentification par clé d'API envoyée dans `X-Mystix-Api-Key` : seule son empreinte SHA-256 est en base, la clé n'est montrée qu'une fois (création, rotation). Refus explicites : 503 si le listener n'est pas en marche sur ce nœud, 401 clé absente ou fausse, 413 au-delà de `maxBytes`, 415 mauvais type de contenu. Chaque message reçoit un identifiant de corrélation renvoyé dans `X-Mystix-Correlation-Id`. Le canal doit être `HTTPS` ou `WEBHOOK`, `IN` ou `BOTH`, et `ACTIVE`.
6. **Remise au pipeline** : un point d'entrée `INVOICE` / `JSON` appelle la même réception que `POST /api/v1/invoices` en mode asynchrone (stockage RAW, canonique, UBL 2.1, clearance via la file). Une erreur de réception (clé, taille, listener arrêté) est distinguée d'un refus métier (règles de la facture), et les deux sont journalisés (`listener_event`).
7. **Journal** (`listener_event`) : démarrage demandé, démarré, refusé, échec, arrêté, restauré, message reçu ou refusé, avec corrélation et détails sans secret ni contenu de message. Compteurs `received_count`, `rejected_count`, `last_activity_at`.
8. **Droits** : créer, modifier, démarrer, arrêter, faire tourner la clé : administrateur de l'environnement ou opérateur (`Access.requireManager`).

## Amendement (2026-10-15) : SFTP par scrutation
9. **Adaptateur `SFTP_POLLING`** (Apache MINA SSHD 2.18.0, Apache-2.0) : Mystix est **client** du serveur SFTP du partenaire (un serveur SFTP hébergé par Mystix n'est pas construit). Au démarrage, une connexion de contrôle prouve la configuration ; chaque échec est nommé : `HOST_UNREACHABLE`, `TIMEOUT`, `HOST_KEY_MISMATCH`, `AUTH_FAILED`, `REMOTE_DIRECTORY_UNAVAILABLE`, `SECRET_MISSING`, `SECRET_INVALID`.
10. **Clé du serveur toujours vérifiée** : l'empreinte SHA-256 déclarée sur le canal est obligatoire ; aucune option pour la désactiver.
11. **Acquisition** : fichiers du répertoire d'entrée qui suivent le masque, complets (pas de suffixe `.part`, `.tmp`, `.filepart`, `.partial` ; inchangés depuis `mystix.connectivity.sftp-stable-after`), dans la limite de taille. Empreinte SHA-256 et **dédoublonnage** par listener (`listener_file`, V24) : un contenu déjà pris n'est jamais remis deux fois au pipeline. Un fichier accepté est supprimé, déplacé en archive ou laissé, selon le canal ; un fichier refusé ou rejeté reste en place, journalisé une fois.
12. **Secrets** : `SecretStore` lit le secret nommé par `credential_ref` dans `mystix.secrets.directory` (un fichier par référence, hors dépôt, jamais en base). Clés privées PKCS#8 ou OpenSSH sans phrase de passe. Le coffre d'exploitation reste prévu (Lot 8).
13. **Une seule instance** : un poller ne tourne que dans un processus (`multiInstance() = false`) ; un démarrage est refusé (`RUNNING_ELSEWHERE`) tant qu'un autre processus a un heartbeat frais.
14. **« Scruter maintenant »** : `POST /listeners/{id}/poll` lance un cycle et renvoie ce qu'il a fait.

## Livré / prévu
- **Livré** : modèle, migrations, moteur, réconciliation, heartbeat, listener HTTP entrant réel, listener SFTP par scrutation (testé contre un vrai serveur SSH embarqué), point d'entrée facture JSON, API, écrans, tests d'intégration.
- **Prévu** :
  - AS2 (bibliothèque selon l'ADR-0004, validée contre `tools/as2-test`) ;
  - liste blanche des hôtes joignables (protection SSRF) et test de connexion sortant ; SFTP sortant (dépôt) ; FTP/FTPS ;
  - secrets lus depuis le coffre ; mTLS ; points d'entrée EDIFACT, XML, CSV.
- **Limite connue** : HTTP entrant est servi par chaque nœud ; pour un adaptateur qui ouvre un port ou scrute un serveur distant (SFTP, AS2 hébergé), il faudra un verrou distribué par listener (une seule instance active). Le schéma le prévoit (`runtime_instance`, heartbeat) ; le verrou viendra avec le premier adaptateur de ce type.

## Options écartées
| Option | Pourquoi écartée |
| --- | --- |
| Une table `connection` à côté de `comm_channel` | Deux vérités pour le même partenaire et le même serveur (règle de l'ADR-0015) |
| Démarrer le listener à l'enregistrement | Un service ne démarre que sur action explicite et préconditions vérifiées |
| Un seul champ `status` | Mélange ce qui est demandé et ce qui tourne ; un redémarrage du backend le rendrait faux |
| Clé d'API en clair ou chiffrée en base | L'empreinte suffit pour vérifier ; la clé n'a pas à être relue |
| Nouveau port d'écoute pour HTTP entrant | Le serveur Mystix sert déjà HTTP ; un port de plus = un point d'exposition de plus |
