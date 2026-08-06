#!/usr/bin/env bash
# One-time setup: store the 1Password service-account token in the macOS
# Keychain so deploy scripts can use it without any plaintext secret on disk.
#
#   1. Create the service account: https://my.1password.com -> Developer ->
#      Service Accounts -> New. Grant it VIEW access to the "treloc" vault.
#   2. Run this script and paste the ops-... token when prompted.
#
# The token never touches the repo, chat, or any file — only the Keychain.
# Takes no arguments; verification lives in bin/op-secret.sh check.
set -euo pipefail

if [ $# -ne 0 ]; then
    echo "ERROR: this script takes no arguments." >&2
    echo "Did you mean: bin/op-secret.sh check" >&2
    exit 2
fi

KEYCHAIN_SERVICE="foodfinder-op"
KEYCHAIN_ACCOUNT="service-token"

printf 'Paste the 1Password service account token (ops-...): '
IFS= read -rs token
echo

if [ -z "$token" ]; then
    echo "ERROR: empty token." >&2
    exit 1
fi

echo "Validating token against 1Password..."
if ! OP_SERVICE_ACCOUNT_TOKEN="$token" op whoami >/dev/null 2>&1; then
    echo "ERROR: token rejected (op whoami failed). Check the token and retry." >&2
    exit 1
fi

security add-generic-password -U \
    -s "$KEYCHAIN_SERVICE" -a "$KEYCHAIN_ACCOUNT" -w "$token"
unset token OP_SERVICE_ACCOUNT_TOKEN

echo
echo "OK — token stored in Keychain (service=$KEYCHAIN_SERVICE, account=$KEYCHAIN_ACCOUNT)."
echo "Next: bin/op-secret.sh check"
