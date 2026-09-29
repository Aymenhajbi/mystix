# ADR-0003 — File de messages : file PostgreSQL (outbox + SKIP LOCKED)

- Statut : **Accepté**
- Date : 2026-09-29
- Lot : 4

## Contexte
Le Lot 4 introduit outbox, retry, DLQ. PostgreSQL est la source de vérité de Mystix (messages, événements, artefacts). L'équipe est réduite : chaque composant supplémentaire coûte en exploitation, sécurité, sauvegarde et supervision.

## Décision
La file de travail est implémentée **dans PostgreSQL**, derrière une interface `MessageQueue` :
- l'outbox et la file sont écrites dans la **même transaction** que le message métier : pas de double écriture, pas de message perdu entre la base et un broker ;
- consommation par `SELECT … FOR UPDATE SKIP LOCKED` (plusieurs workers sans collision) ;
- retry avec backoff par `available_at`, compteur `attempts`, classement `TRANSIENT` / `PERMANENT` des erreurs structurées ;
- DLQ = statut `DEAD` dans la même table, rejouable depuis l'environnement client (confirmation dans la page).

Esquisse de schéma (indicative, la migration réelle suivra l'outil de migration constaté au Lot 0) :

```sql
CREATE TABLE message_queue (
  id            BIGSERIAL PRIMARY KEY,
  company_id    BIGINT       NOT NULL,          -- isolation tenant
  message_id    UUID         NOT NULL,
  stage         VARCHAR(40)  NOT NULL,          -- étape du pipeline à exécuter
  status        VARCHAR(16)  NOT NULL DEFAULT 'READY', -- READY | RUNNING | DONE | DEAD
  attempts      INT          NOT NULL DEFAULT 0,
  max_attempts  INT          NOT NULL DEFAULT 8,
  available_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
  locked_by     VARCHAR(100),
  locked_at     TIMESTAMPTZ,
  last_error    JSONB,                          -- errorCode, stage, retryable, rootCause
  created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
  UNIQUE (message_id, stage)                    -- idempotence
);
CREATE INDEX ix_queue_ready ON message_queue (available_at) WHERE status = 'READY';
```

## Options écartées
| Option | Pourquoi écartée |
| --- | --- |
| RabbitMQ | Routage, DLX et retry natifs, mais un composant de plus à exploiter, sécuriser et sauvegarder, et une outbox reste nécessaire pour la cohérence avec PostgreSQL. Le gain ne se justifie pas aux volumes actuels |

## Critères de réexamen (pas de bascule sans mesure)
Rouvrir l'ADR si, mesuré en production ou en test de charge :
- la file ralentit les requêtes métier de la base (contention, vacuum) de façon durable ;
- un besoin de fan-out vers plusieurs consommateurs externes apparaît (ex. diffusion d'événements aux éditeurs) ;
- le débit soutenu nécessaire dépasse ce qu'un test de charge PostgreSQL tient avec marge.
Les seuils chiffrés seront fixés après mesure des volumes réels (Sahel, premiers clients) ; ils ne sont pas inventés ici.

## Conséquences
- Rien à ajouter en local : la file tourne dans le PostgreSQL du `docker-compose.dev.yml`.
- `MessageQueue` isole l'implémentation : une bascule vers RabbitMQ reste un nouvel adaptateur, pas une refonte.
- Supervision : profondeur de file, âge du plus vieux message `READY`, nombre de `DEAD`, exposés dans le cockpit.
