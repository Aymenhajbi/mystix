# ADR-0004 — AS2 via une bibliothèque éprouvée

- Statut : **Accepté** ; bibliothèque retenue le 2026-10-15 : **as2-lib 5.1.8** (Philip Helger)
- Date : 2026-09-29
- Lot : 6

## Contexte
AS2 (RFC 4130) : signature et chiffrement S/MIME, MDN synchrone/asynchrone, MIC. Le test factice Node.js + OpenSSL a passé 9/9 contrôles ; leçon : normaliser les fins de ligne en CRLF avant de signer, sinon MIC et signature échouent chez le partenaire.

## Décision
Ne pas réimplémenter le protocole. Choisir entre **as2-lib (phax)** et **Apache Camel AS2**, avec Bouncy Castle.

## Critères de choix (à évaluer au Lot 6)
- Les 9 contrôles du test factice passent contre `tools/as2-test` (`http://127.0.0.1:4080/as2`).
- Idempotence sur `Message-ID` : doublon = même MDN, sans retraitement.
- Refus explicites : `authentication-failed`, `decryption-failed`, `unknown-trading-partner`.
- Clé privée hors base (keystore chiffré puis Vault/KMS) ; rotation sans coupure ; alertes J-60/J-30/J-7.
- Intégration avec Spring Boot sans imposer Camel ailleurs.

## Hors périmètre
Certification Drummond : plus tard, coût non vérifié (devis à demander).

## Décision du 2026-10-15
- **as2-lib 5.1.8** (licence de type BSD, publiée le 2026-07-17 ; la 5.1.9 du 2026-10-09 jugée trop récente). Camel AS2 écarté : il imposait le cœur Camel et son propre serveur HTTP.
- **Émission** : `As2Sender` (client as2-lib), clés en mémoire (`As2Keys` : clé privée du coffre, certificats OWN et PARTNER du canal), MDN synchrone signé demandé, MIC comparé. Journal `as2_message` (V26).
- **Réception** : listener `AS2_SERVER` (ADR-0016), `POST /inbound/as2/{listenerId}`, session as2-lib par listener. Lecture de la requête comme le servlet d'as2-lib (`HTTPHelper.readAndDecodeHttpRequest`). **Vérification de signature uniquement avec le certificat du partenaire enregistré** (`setCryptoVerifyUseCertificateInBodyPart(false)`) : par défaut, as2-lib acceptait le certificat joint au message, donc n'importe quel signataire sous le nom du partenaire. Doublon de Message-ID : même MDN, octet pour octet, sans retraitement. Le MDN reflète le résultat de transport ; le refus métier de la facture est journalisé à part.
- **MDN asynchrone** : pas encore pris en charge (refusé à l'émission).

## Résultats de contrôle (2026-10-15)
- **Tests d'intégration CI** (`As2Tests`, as2-lib des deux côtés, HTTP réel) : nominal (MDN signé, processed, Original-Message-ID, MIC identique), doublon, signataire inconnu, message altéré, destinataire inconnu, émission Mystix → partenaire avec MDN signé et MIC identique, préconditions. **Tous verts.**
- **Banc `as2-test` (Node + OpenSSL), local** :
  - partenaire → Mystix : **8/9**. Le contrôle en échec est « MDN signé par Mystix » : OpenSSL en vérification stricte (`cms -verify -binary`) refuse la signature du MDN produit par as2-lib, alors que la vérification S/MIME canonique (`cms -verify` sans `-binary`) la valide ;
  - Mystix → partenaire : le partenaire du banc refuse la signature (`authentication-failed`) pour la même raison ; il exige aussi des parties en base64 (réglage de canal `transferEncoding` ajouté).
  - **Conclusion** : l'entité signée transmise par as2-lib n'est pas identique octet pour octet à celle que lit OpenSSL en mode strict. Tant que ce point n'est pas résolu, **AS2 n'est pas déclaré opérationnel** avec un partenaire OpenSSL strict.
