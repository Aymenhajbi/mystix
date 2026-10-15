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

## Livré / prévu
- **Livré dans ce lot** : modèle, migrations, moteur, réconciliation, heartbeat, listener HTTP entrant réel, point d'entrée facture JSON, API, tests d'intégration.
- **Prévu** :
  - écrans Connectivity Center et Listener Management ;
  - SFTP client par scrutation (Apache MINA SSHD, testé contre un serveur SSH embarqué) ;
  - AS2 (bibliothèque selon l'ADR-0004, validée contre `tools/as2-test`) ;
  - test de connexion sortant avec protection SSRF ;
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
