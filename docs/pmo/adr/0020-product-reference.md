# ADR-0020 : Référentiel produits ouvert et GTIN des produits Atelys

- Statut : **proposé** (lot 13, 19/10/2026)
- Contexte : ADR-0010 (référentiels plateforme), ADR-0014 (Atelys WMS), ADR-0018 (scan mobile).

## Contexte

Un code-barres scanné sur un terminal Honeywell ou saisi au portail doit être vérifié : est-ce un GTIN valide, quel
produit de l'environnement le porte, et qu'en sait-on ailleurs ? Les produits Atelys n'avaient pas de GTIN (audit
ADR-0018).

Sources examinées :
- **GS1 (Verified by GS1)** : 30 requêtes par 24 h, résultats réservés à l'usage interne, sans redistribution
  ([conditions](https://gs1.org/docs/verified-by-gs1/public-verified-by-gs1-tou.pdf)). Pas de base téléchargeable.
- **Open Food Facts, Open Beauty Facts, Open Products Facts** : exports CSV quotidiens, base sous ODbL 1.0 et contenu
  sous DbCL 1.0 ([conditions](https://openfoodfacts.github.io/documentation/docs/Product-Opener/api/tutorials/license-be-on-the-legal-side/)).
  Le 19/10/2026, l'API d'Open Food Facts annonçait 22 988 produits vendus au Maroc.

## Décision

1. Table plateforme `product_reference` (sans `company_id`, comme `vat_rate`), clé `(gtin, source)`, GTIN normalisé à
   14 chiffres. Seules les lignes dont le code passe le contrôle GS1 mod 10 sont gardées ; les autres sont comptées.
2. Import par un opérateur (`POST /api/v1/admin/product-references/{OFF|OBF|OPF}/import`) depuis un répertoire du
   serveur (`MYSTIX_PRODUCT_REFERENCE_DIRECTORY`) ; lecture en flux du gzip, `COPY` par lots de 20 000, upsert ; la
   ligne `product_reference_import` garde SHA-256 du fichier, lignes lues, importées, sans code, clé fausse.
   Le pilote PostgreSQL passe en portée `compile` pour l'API COPY.
3. `wms_product.gtin` (unique par environnement, clé vérifiée, jamais corrigée), `PUT /wms/products/{id}/gtin`,
   recherche par GTIN, `GET /wms/products/by-gtin/{code}` : produit de l'environnement + référentiel ouvert.
4. Le référentiel **vérifie**, il ne crée ni ne modifie jamais un produit d'un client. L'attribution ODbL est affichée
   partout où une donnée ouverte apparaît (page « Référentiel produits », application mobile).
5. Les exports ne sont pas versionnés dans git (1,3 Go) ; ils sont téléchargés dans un répertoire hors dépôt.

## Conséquences et limites

- Données collaboratives : noms, catégories ou pays peuvent être faux ou absents ; un code absent n'est pas faux.
- Exception assumée à la règle « données de test fictives » (AGENTS.md §6), décidée par le fondateur le 19/10/2026 :
  les données ouvertes restent dans le référentiel ; les environnements de démo gardent des produits fictifs.
- Le préfixe 611 indique l'organisation GS1 émettrice (GS1 Maroc), pas le pays de fabrication.
- Prévu : rafraîchissement planifié, cross-check à la réception EDI (DESADV) et à la saisie des lignes de commande.
