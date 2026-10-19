# Tests de bout en bout (portail Mystix + Atelys)

Scénario et règles métier : skill `.claude/skills/edix-business`. Données de démo fictives uniquement.

## Playwright CLI

```bash
npm install -g @playwright/cli@0.1.22          # une fois par poste
playwright-cli -s=edix open http://localhost:3000/fr/login
# connexion : remplir e-mail et mot de passe sans afficher la sortie (voir le skill), puis :
bash e2e/cli-check.sh 01-cockpit /fr "Clearance simulée"
bash e2e/cli-check.sh 18-devices /fr/atelys/devices "Terminaux"
```

`cli-check.sh` ouvre la page, garde l'instantané d'accessibilité (`snaps/`), une capture (`shots/`) et compte les
erreurs de la console.

## Playwright MCP

Le serveur déclaré dans `.mcp.json` est piloté en JSON-RPC, comme le ferait Claude Code :

```bash
node e2e/mcp-run.mjs .claude/dev-credentials.txt <id société Atelys> <id société avec factures>
```

Il se connecte, vérifie 8 points (clearance simulée, badge, liste fournisseurs, référence `CMD-`, Terminaux fr/ar),
contrôle que chaque champ de formulaire a un nom accessible et qu'aucune erreur n'apparaît dans la console.
Sortie : `mcp/results.json`, code de retour 1 si un point échoue.

Ces tests n'exercent ni un terminal Honeywell réel ni la DGI (clearance simulée).
