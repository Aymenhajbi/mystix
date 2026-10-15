# ADR-0017 — ArtifactStore sur l'API S3

- Statut : **Proposé** (2026-10-15)
- Date : 2026-10-15
- Lot : 4

## Contexte
Les artefacts de chaque facture (RAW reçu octet pour octet, CANONICAL, OUT UBL 2.1) sont en `BYTEA` dans `invoice_artifact` depuis l'ADR-0006, « jusqu'à l'ArtifactStore S3 ». En volume, PostgreSQL n'est pas le bon endroit pour des millions de documents à conserver longtemps, et l'archivage légal demande des objets qu'on ne peut ni modifier ni effacer avant terme. Les images communautaires MinIO ont disparu de Docker Hub (risque R-07) : le code doit dépendre de l'API S3, pas de MinIO.

## Décision
1. **Interface `ArtifactStore`** (`ma.mystix.storage`) : `put` une fois, `get` par version, contrôle SHA-256. Deux modes, choisis par `mystix.storage.mode` :
   - `database` (défaut) : rien ne change, les octets restent dans la table ;
   - `s3` : les octets vont dans un stockage compatible S3 ; la table garde la clé, la version, la taille et le SHA-256 (`content` à NULL). Contrainte : un artefact est dans la table **ou** dans le stockage, jamais les deux ni aucun (V25).
2. **Client** : AWS SDK for Java v2 (`software.amazon.awssdk:s3` 2.46.7, Apache-2.0), client HTTP du JDK (`url-connection-client`) au lieu d'Apache et Netty. Fonctionne avec MinIO (dev, image Chainguard), AWS S3 ou tout stockage compatible.
3. **Écriture unique** : `If-None-Match: *`. Réécrire la même clé avec les mêmes octets est un rejeu sans effet ; avec d'autres octets, refus `STORAGE_CONFLICT`.
4. **Lecture contrôlée** : par `versionId` (une version déposée derrière Mystix n'est pas servie à la place de l'original), puis SHA-256 ; un écart donne `STORAGE_INTEGRITY` et rien n'est servi.
5. **Rétention** : object lock (`GOVERNANCE` ou `COMPLIANCE`) pendant `retention-days`, 0 par défaut. La durée légale n'est pas confirmée (probablement 10 ans) : `TODO(DGI-SPEC)`, paramétrable, jamais codée en dur.
6. **Échec du stockage** : l'écriture se fait dans la transaction de la facture, avant la ligne ; si le stockage ne répond pas, `STORAGE_UNAVAILABLE` (503, réessayable) et rien n'est enregistré. Un objet peut rester orphelin si la base échoue après l'écriture : il porte l'identifiant du message, il est inoffensif et repérable.
7. **Démarrage** : en mode `s3`, le bucket doit répondre (`HeadBucket`), sinon l'application ne démarre pas : aucun artefact ne doit partir ailleurs que là où on l'a demandé.
8. **Clé d'objet** : `{company_id}/invoices/{AAAA}/{MM}/{message_id}/{KIND}`, pour lister, conserver ou exporter les objets d'une société ensemble.

## Livré / prévu
- **Livré** : interface, mode S3, migration V25, tests contre un vrai MinIO en conteneur (écriture, relecture via l'API, rétention, non-remplacement, version déposée en douce non servie, stockage injoignable).
- **Prévu** : migration des artefacts existants de la base vers S3 (outil de reprise), clé d'accès dédiée et politique de bucket en exploitation, artefacts des autres modules (messages EDIFACT, fichiers SFTP).

## Options écartées
| Option | Pourquoi écartée |
| --- | --- |
| Client MinIO Java | Lie le code à MinIO, dont la distribution communautaire a cessé (R-07) |
| Garder les octets en base | Volume, sauvegardes lourdes, pas d'immutabilité légale |
| Écrire en S3 après la transaction | Une facture acceptée pourrait n'avoir aucun artefact durable |
| Lire la dernière version | Une version déposée hors de Mystix serait servie comme l'original |
