#!/usr/bin/env bash
# check.sh <name> <path> <expected regex in the page snapshot> — one page of the ediX scenario with Playwright CLI.
# Prints: name, OK/KO, the matched evidence, console errors count; saves shots/<name>.png and snaps/<name>.yml.
name="$1"; path="$2"; expect="$3"
mkdir -p shots snaps
playwright-cli -s=edix goto "http://localhost:3000$path" > /dev/null 2>&1
sleep "${WAIT:-2}"
playwright-cli -s=edix snapshot > "snaps/$name.yml" 2>&1
evidence=$(grep -oiE "$expect" "snaps/$name.yml" | head -1)
errors=$(playwright-cli -s=edix console error 2>&1 | grep -ciE "^\s*-?\s*\[?error")
playwright-cli -s=edix screenshot --filename="shots/$name.png" > /dev/null 2>&1
url=$(grep -m1 "Page URL" "snaps/$name.yml" | sed 's/.*Page URL: //')
if [ -n "$evidence" ]; then status=OK; else status=KO; fi
printf '%-28s %-3s url=%s | evidence="%s" | console-errors=%s\n' "$name" "$status" "$url" "$evidence" "$errors"
