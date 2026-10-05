# ADR-0008 — Versions de mapping exécutables par flux

- Statut : **Proposé** (à valider par le fondateur)
- Date : 2026-10-02
- Lot : 7 (Mapping Studio, palier 3)

## Contexte
Chaque client a ses flux (ADR-0007) et ses particularités : unités SAP à convertir (`PCE` → `C62`), référence acheteur à prendre dans le bon de commande, préfixe sur une désignation, valeur par défaut… Le mapping de base (`ubl-invoice` 1.0, ADR-0005) est du code. Il faut pouvoir adapter un flux sans redéployer, sans casser ce qui marche, et sans toucher à ce qui est fiscal.

## Décision
1. **Règles déclaratives par flux, versionnées.** Une version de mapping est une liste de règles JSON appliquées par un moteur, au-dessus du mapping de base. Statuts : `DRAFT` (modifiable), `PUBLISHED` (une seule par flux, exécutée), `RETIRED`.
2. **Point d'application** : sur la requête reçue, après la lecture du JSON et **avant** les contrôles de champs, le modèle canonique, le XSD et EN 16931. Tous les contrôles s'appliquent donc au résultat transformé. Le RAW stocké reste celui reçu ; le CANONICAL montre le résultat. La lignée affiche la différence.
3. **Périmètre volontairement limité** : seuls des champs texte et codes non fiscaux sont modifiables (référence acheteur, bon de commande, note, raisons sociales, désignation, unité de ligne). **Aucune règle sur les montants, taux de TVA, ICE, IF, dates ou numéros de facture.**
4. **Opérations** : source (autre champ de la requête ou constante), `trim`, `upper`, `lower`, `prefix`, `suffix`, `lookup` (table de correspondance, avec repli : garder la valeur ou rejeter), `default` (si vide).
5. **Publication bloquée par les tests** : un « rejeu à blanc » exécute la version sur la facture de référence et sur les 20 dernières requêtes reçues du flux (sans stockage ni clearance) : mapping, XSD UBL 2.1, règles EN 16931. Il compare la sortie à celle de la version publiée et liste les champs qui changent. La publication n'est possible que si le dernier test est vert **et** porte sur les règles exactes à publier (empreinte SHA-256).
6. **Retour arrière** : republier une version `RETIRED` (déjà testée).
7. **Idempotence** : l'empreinte d'une facture reste celle du canonique transformé. Renvoyer une ancienne facture après un changement de mapping peut donc donner un conflit (409) : le résultat déjà stocké est immuable.

## Options écartées
| Option | Pourquoi écartée |
| --- | --- |
| XSLT éditable par flux | Puissant mais opaque pour un client PME, difficile à tester champ par champ, risque d'injection |
| Règles sur montants et TVA | Règles fiscales hors de portée d'un paramétrage client ; référentiel daté prévu au Lot 2 |
| Appliquer après le modèle canonique | Les contrôles de champs ne verraient pas la valeur transformée |

## Conséquences
- Le Mapping Studio édite ces règles en glisser-déposer (source → cible, chaîne de transformations), teste et publie.
- Les nouveaux formats (IDoc, EDIFACT, CSV) apporteront leurs propres mappings de base ; les règles par flux s'y appliqueront de la même façon.
