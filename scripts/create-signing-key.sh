#!/usr/bin/env bash
# One-time setup: create the app's release signing key and give it to GitHub Actions.
#
# Creates ../chompchamp-signing/ (outside the repo, never committed) with the keystore and
# its password, then stores three repository secrets with `gh secret set`.
# Back up that folder: losing the key means every phone must uninstall/reinstall.
set -euo pipefail

REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
KEY_DIR="${KEY_DIR:-$(dirname "$REPO_DIR")/chompchamp-signing}"
KS="$KEY_DIR/chompchamp-release.p12"
ALIAS="chompchamp"

if [[ -e "$KS" ]]; then
  echo "Keystore already exists at $KS - not overwriting it." >&2
  echo "To re-upload it to GitHub, delete nothing; run the 'gh secret set' lines below by hand." >&2
  exit 1
fi

mkdir -p "$KEY_DIR"
chmod 700 "$KEY_DIR"
PW="$(openssl rand -base64 32 | tr -d '/+=' | head -c 28)"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
openssl req -x509 -newkey rsa:4096 -sha256 -days 10950 -nodes \
  -keyout "$TMP/key.pem" -out "$TMP/cert.pem" -subj "/CN=ChompChamp" 2>/dev/null
openssl pkcs12 -export -inkey "$TMP/key.pem" -in "$TMP/cert.pem" -name "$ALIAS" \
  -out "$KS" -passout "pass:$PW"

cat > "$KEY_DIR/README-KEEP-SAFE.txt" <<EOF
ChompChamp release signing key
Keystore: chompchamp-release.p12 (PKCS12)
Alias:    $ALIAS
Password: $PW

Back this folder up somewhere safe (password manager, encrypted drive).
If it's lost, installed copies of the app can't be updated.
EOF
chmod 600 "$KS" "$KEY_DIR/README-KEEP-SAFE.txt"

cd "$REPO_DIR"
base64 -w0 "$KS" | gh secret set ANDROID_KEYSTORE_B64
printf '%s' "$PW" | gh secret set ANDROID_KEYSTORE_PASSWORD
printf '%s' "$ALIAS" | gh secret set ANDROID_KEY_ALIAS

echo "Done. Key saved in $KEY_DIR (back it up). GitHub secrets set:"
gh secret list
