# Test AS2 en grandeur réelle — Mystix (edi.ma) ↔ partenaire ediX

> Version du 19/10/2026. Document de travail entre l'équipe Mystix et Achraf.
> **Aucun mot de passe, clé privée ni contenu de `.env` ne doit figurer ici ni circuler par messagerie.**

## 1. Objectif et état de départ

Vérifier, entre deux machines distinctes reliées par Internet, qu'un partenaire peut **envoyer** un document à Mystix
en AS2 (RFC 4130) et en recevoir un, avec signature, chiffrement et accusé MDN signé.

| Élément | État |
| --- | --- |
| Réception AS2 (`POST /inbound/as2/{listenerId}`), MDN synchrone signé, MIC, doublons rejoués | **Livré** (ADR-0004, ADR-0016), testé contre un banc Node.js + OpenSSL : 8/9 contrôles |
| Envoi AS2 depuis un canal Mystix, MDN synchrone | **Livré**, même banc |
| MDN asynchrone | **Non disponible** (refusé par la configuration du canal) |
| Vérification stricte OpenSSL (`cms -verify -binary`) des signatures produites par Mystix | **Point ouvert** : à observer pendant ce test avec la station du partenaire |
| Clearance DGI | **Simulée** : aucun document ne part vers la DGI |
| Production `edi.ma` | Déployée au commit `4ac4191`, schéma `v29` (document de déploiement) |

## 2. Rôles

### Équipe Mystix — Aymen et Zineb : « Hub Mystix »

Vous exploitez la plateforme `edi.ma`, c'est-à-dire le côté qu'un vrai client utilisera.

- Administrer le serveur `64.177.15.122` (sécurité, Nginx, services) et le portail.
- Créer dans Mystix l'environnement de test, le point d'entrée, l'écouteur AS2 et le canal sortant.
- Générer la paire de clés AS2 de Mystix, ne publier que le certificat public.
- Suivre chaque échange dans le portail (`/fr/connectivity`, `/fr/logs`, `/fr/jobs`) et remplir le tableau du §7.

### Achraf : « Partenaire ediX »

Achraf joue un partenaire commercial indépendant (client ou fournisseur) **depuis une autre machine** que le serveur
Mystix, pour que le test traverse vraiment Internet.

- Installer et exploiter une station AS2 indépendante (proposé : **Mendelson AS2**, libre, ou **OpenAS2**).
- Générer sa propre paire de clés, ne transmettre que son certificat public.
- Envoyer les documents de test vers Mystix et recevoir ceux de Mystix.
- Noter, de son côté, l'état de chaque MDN (signature vérifiée ou non, MIC identique ou non).

Achraf a aussi accès au serveur : pendant le test, il ne modifie pas la configuration Mystix. Les deux côtés doivent
rester indépendants, sinon le test ne prouve rien.

### Qui fait quoi

| Tâche | Hub Mystix | Partenaire ediX |
| --- | --- | --- |
| Sécuriser l'accès SSH (§3.1) | ✔ | informé |
| Route Nginx `/inbound/as2/` (§3.2) | ✔ | — |
| Environnement, écouteur, canal dans Mystix (§3.3) | ✔ | — |
| Station AS2 et URL publique | — | ✔ |
| Clés et certificat public | ✔ (Mystix) | ✔ (ediX) |
| Vérification des empreintes par téléphone | ✔ | ✔ |
| Envoi des documents | T5, T6 | T1 à T4 |
| Tableau des résultats (§7) | ✔ | ✔ |

## 3. Préparation côté Hub Mystix

### 3.1 Sécurité du serveur — avant tout le reste

Le mot de passe `root` a circulé en clair dans une messagerie : il doit être **considéré comme compromis**.

1. Changer immédiatement le mot de passe `root` (`passwd`).
2. Créer un compte nominatif par personne (Aymen, Zineb, Achraf) avec `sudo`, et une clé SSH par personne.
3. Dans `/etc/ssh/sshd_config` : `PermitRootLogin no`, `PasswordAuthentication no`, puis `systemctl reload ssh`
   **après** avoir vérifié qu'une connexion par clé fonctionne dans une seconde session.
4. Pare-feu : n'ouvrir que 22, 80 et 443.

### 3.2 Exposer la réception AS2 derrière Nginx

Aujourd'hui Nginx envoie tout vers Next.js (`:3000`). La réception AS2 est servie par le backend (`:8080`) sur
`/inbound/as2/{listenerId}` ; elle n'est pas soumise à la connexion du portail (l'authentification AS2 repose sur la
signature et le certificat du partenaire). Ajouter dans le bloc `server` HTTPS de `/etc/nginx/sites-available/mystix`,
**avant** `location /` :

```nginx
location /inbound/as2/ {
    proxy_pass http://127.0.0.1:8080;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_request_buffering off;
    proxy_read_timeout 120s;
    client_max_body_size 20m;
}
```

```bash
nginx -t && systemctl reload nginx
curl -i -X POST https://edi.ma/inbound/as2/00000000-0000-0000-0000-000000000000
```

Résultat attendu : une erreur structurée Mystix (`LISTENER_NOT_RUNNING`), et non une page Next.js. Cela prouve que la
route atteint le backend.

### 3.3 Paramétrage dans Mystix

1. Créer l'environnement **« ediX Test AS2 (fictif) »** avec un ICE fictif.
2. Connectivité (`/fr/connectivity`) : créer un point d'entrée, puis un écouteur **AS2** rattaché, avec :
   - AS2-To (identifiant Mystix) : `MYSTIX-EDIMA` ;
   - AS2-From attendu (partenaire) : `EDIX-PARTNER-TEST` ;
   - certificat public du partenaire, signature `SHA-256`, chiffrement `AES256_CBC` ;
   - taille maximale : 5 Mo (valeur par défaut, 20 Mo au maximum).
3. Relever l'URL affichée par le portail : `https://edi.ma/inbound/as2/<listenerId>`. C'est elle qu'on transmet à
   Achraf.
4. Partenaires (environnement client) : créer le partenaire **« ediX partenaire test (fictif) »** et un canal AS2
   sortant vers l'URL d'Achraf, avec MDN `SYNC` signé.

## 4. Clés et certificats

Chaque côté génère sa propre paire de clés. Seul le **certificat public** (`.crt`, PEM) est échangé.

```bash
# Côté Mystix (identique côté ediX avec son propre CN)
openssl req -x509 -newkey rsa:3072 -sha256 -days 730 -nodes \
  -keyout mystix-edima.key -out mystix-edima.crt \
  -subj "/CN=MYSTIX-EDIMA/O=ediX/C=MA"
openssl x509 -in mystix-edima.crt -noout -fingerprint -sha256
```

- La clé privée reste sur sa machine, en permission `600`. Elle n'entre ni dans git ni dans une messagerie.
- Les empreintes SHA-256 des deux certificats sont **lues à voix haute par téléphone** avant le premier échange.

## 5. Fiche de connexion à échanger

| Paramètre | Hub Mystix (edi.ma) | Partenaire ediX (Achraf) |
| --- | --- | --- |
| Identifiant AS2 | `MYSTIX-EDIMA` | `EDIX-PARTNER-TEST` |
| URL de réception | `https://edi.ma/inbound/as2/<listenerId>` (§3.3) | `https://<hôte-achraf>/<chemin-as2>` *(à remplir)* |
| Certificat public | `mystix-edima.crt` | `edix-partner-test.crt` *(à remplir)* |
| Empreinte SHA-256 | *(à remplir)* | *(à remplir)* |
| Signature | SHA-256 | SHA-256 |
| Chiffrement | AES256_CBC | AES256_CBC |
| MDN | synchrone, signé | synchrone, signé |
| Encodage de transfert | binary | binary |
| Content-Type | `application/xml` | `application/xml` |
| Taille maximale | 5 Mo | *(à remplir)* |
| IP sortante (liste blanche éventuelle) | `64.177.15.122` | *(à remplir)* |

## 6. Plan de test

Toutes les données sont **fictives**. Document de départ : l'exemple public EN 16931
`backend/src/test/resources/fixtures/en16931/ubl-tc434-example1.xml`, avec des noms et identifiants modifiés en
« (fictif) ».

Lignes en CRLF avant signature : leçon du banc ADR-0004, sinon le MIC et la signature échouent chez l'autre partie.

| N° | Sens | Scénario | Résultat attendu |
| --- | --- | --- | --- |
| T1 | ediX → Mystix | Facture UBL signée et chiffrée | MDN `processed` signé, MIC identique, message visible dans le journal de l'écouteur |
| T2 | ediX → Mystix | Renvoi du même Message-ID | Même MDN rejoué, aucun second traitement |
| T3 | ediX → Mystix | Envoi signé avec un autre certificat | Refus : MDN d'erreur ou rejet, rien n'est traité |
| T4 | ediX → Mystix | Fichier de plus de 5 Mo | Refus explicite de taille |
| T5 | Mystix → ediX | Envoi depuis le canal sortant | MDN signé reçu, `delivered = true` dans Mystix |
| T6 | Mystix → ediX | Station d'Achraf arrêtée | Échec réseau visible dans `/fr/jobs`, nouvelle tentative possible |
| T7 | les deux | Vérification de la signature du MDN Mystix par la station d'Achraf | **Point ouvert ADR-0004** : noter si elle accepte ou refuse |

## 7. Résultats

| N° | Date et heure | Message-ID | MDN (disposition) | Signature MDN vérifiée | MIC identique | OK / KO | Remarque |
| --- | --- | --- | --- | --- | --- | --- | --- |
| T1 | | | | | | | |
| T2 | | | | | | | |
| T3 | | | | | | | |
| T4 | | | | | | | |
| T5 | | | | | | | |
| T6 | | | | | | | |
| T7 | | | | | | | |

## 8. Critères de réussite et limites

- **Réussi** : T1, T2, T3, T4 et T5 conformes. T7 est noté tel quel : un refus documente le point ouvert, il n'est
  pas masqué.
- **Pas couvert** :
  - MDN asynchrone ;
  - transmission DGI réelle (clearance simulée) ;
  - certificats émis par une autorité tierce ;
  - charge et volumétrie.
- Tant que T7 n'est pas résolu avec une station en vérification stricte, AS2 reste présenté comme **aperçu**, jamais
  comme opérationnel, y compris dans les documents commerciaux.
