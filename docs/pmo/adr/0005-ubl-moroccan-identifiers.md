# ADR-0005 — Identifiants marocains dans la sortie UBL 2.1

- Statut : **Proposé** (provisoire jusqu'à publication des spécifications DGI)
- Date : 2026-10-01
- Lot : 1

## Contexte
Le générateur UBL 2.1 doit porter l'ICE, l'IF et le RC du vendeur et de l'acheteur. Aucune CIUS marocaine ni aucun guide d'implémentation DGI n'est publié : ni `CustomizationID`, ni `schemeID`, ni emplacement imposé.

## Décision
Placement par le sens EN 16931, valeurs de schéma **configurables** (`UblSettings`, `TODO(DGI-SPEC)`) :

| Donnée | Terme EN 16931 | Élément UBL | Valeur par défaut |
| --- | --- | --- | --- |
| ICE | BT-30 / BT-47 identifiant d'immatriculation légale | `cac:PartyLegalEntity/cbc:CompanyID` | sans `schemeID` (aucun code ISO 6523 connu) |
| IF (vendeur) | BT-32 identifiant d'enregistrement fiscal | `cac:PartyTaxScheme/cbc:CompanyID`, `cac:TaxScheme/cbc:ID` | `TAX` |
| RC | aucun | non émis | conservé dans le canonique seulement |
| GLN | BT-29 / BT-46 | `cac:PartyIdentification/cbc:ID` | `schemeID="0088"` (ISO 6523, GS1) |
| GTIN | BT-157 | `cac:StandardItemIdentification/cbc:ID` | `schemeID="0160"` (ISO 6523, GS1) |
| Spécification | BT-24 | `cbc:CustomizationID` | `urn:cen.eu:en16931:2017` |

Le mode d'arrondi des montants est aussi un paramètre (`InvoiceCalculator`), par défaut `HALF_UP`, `TODO(DGI-SPEC)`.

## Options écartées
| Option | Pourquoi écartée |
| --- | --- |
| Inventer un `schemeID` « ICE » ou une CIUS « MA » | Règle fiscale inventée ; dette si la DGI publie autre chose |
| Ne pas émettre l'ICE | Donnée attendue par les partenaires marocains, placement EN 16931 cohérent |

## Conséquences
- La sortie est valide au XSD UBL 2.1 (testé). La conformité aux règles métier EN 16931 (Schematron) **n'est pas encore vérifiée** : prévu.
- À la publication des spécifications DGI : changer les valeurs de `UblSettings`, ajouter une fixture, sans toucher au canonique.
