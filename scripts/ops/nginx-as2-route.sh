#!/usr/bin/env bash
# Routes AS2 reception (/inbound/as2/) from Nginx to the Mystix backend (127.0.0.1:8080) on the production server.
# Safe to run twice; keeps a dated backup; restores it if `nginx -t` fails. Run as root (sudo).
# Usage: sudo bash scripts/ops/nginx-as2-route.sh [/etc/nginx/sites-available/mystix]
set -euo pipefail

CONF="${1:-/etc/nginx/sites-available/mystix}"
MARK="# mystix: AS2 reception (docs/ops/test-as2-reel-mystix-edix.md §3.2)"

[ -f "$CONF" ] || { echo "Configuration introuvable : $CONF" >&2; exit 1; }
if grep -q "location /inbound/as2/" "$CONF"; then
  echo "Route /inbound/as2/ déjà présente dans $CONF : rien à faire."
else
  BACKUP="$CONF.bak-$(date +%F-%H%M%S)"
  cp -p "$CONF" "$BACKUP"
  echo "Sauvegarde : $BACKUP"
  # Insert the block before every "location / {" (the HTTPS server block; the HTTP block only redirects).
  awk -v mark="$MARK" '
    /^[[:space:]]*location[[:space:]]+\/[[:space:]]*\{/ && !done[NR] {
      match($0, /^[[:space:]]*/); pad = substr($0, 1, RLENGTH)
      print pad mark
      print pad "location /inbound/as2/ {"
      print pad "    proxy_pass http://127.0.0.1:8080;"
      print pad "    proxy_http_version 1.1;"
      print pad "    proxy_set_header Host $host;"
      print pad "    proxy_set_header X-Real-IP $remote_addr;"
      print pad "    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;"
      print pad "    proxy_set_header X-Forwarded-Proto $scheme;"
      print pad "    proxy_request_buffering off;"
      print pad "    proxy_read_timeout 120s;"
      print pad "    client_max_body_size 20m;"
      print pad "}"
      print ""
    }
    { print }
  ' "$BACKUP" > "$CONF"
  if ! nginx -t; then
    echo "nginx -t a échoué : restauration de la sauvegarde." >&2
    cp -p "$BACKUP" "$CONF"
    nginx -t
    exit 1
  fi
  systemctl reload nginx
  echo "Nginx rechargé."
fi

echo "Vérification (réponse attendue : erreur JSON Mystix LISTENER_NOT_RUNNING, pas une page HTML Next.js) :"
curl -sS -X POST "https://edi.ma/inbound/as2/00000000-0000-0000-0000-000000000000" \
  -H "Content-Type: application/octet-stream" --data-binary "test" -w "\nHTTP %{http_code}\n" | tail -c 600
