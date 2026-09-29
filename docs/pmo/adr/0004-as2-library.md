# ADR-0004 — AS2 via une bibliothèque éprouvée

- Statut : Accepté sur le principe ; choix de la bibliothèque **Proposé**
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
