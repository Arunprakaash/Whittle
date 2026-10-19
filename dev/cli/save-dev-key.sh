#!/bin/zsh
# Stores a provider API key for the developer CLI, outside the repo, readable only by you.
# Usage: dev/cli/save-dev-key.sh <provider-id>    (provider ids are in provider-catalog.json)
set -e

provider="${1:?Usage: save-dev-key.sh <provider-id>}"
key_file="$HOME/.mobileagent/dev-keys.properties"

mkdir -p "$(dirname "$key_file")"
umask 077
touch "$key_file"

read -s "key?${provider} API key (input hidden): "
echo
if [[ -z "$key" ]]; then
  echo "No key entered; nothing saved."
  exit 1
fi

# Replace any existing entry for this provider, keep the others.
grep -v "^${provider}=" "$key_file" > "${key_file}.tmp" || true
printf '%s=%s\n' "$provider" "$key" >> "${key_file}.tmp"
mv "${key_file}.tmp" "$key_file"
chmod 600 "$key_file"
unset key

echo "Saved ${provider} key to ${key_file}"
