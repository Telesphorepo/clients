#!/usr/bin/env bash
# Guided release setup and release of the Telesphorepo clients.
#
#   ./release.sh            menu
#   ./release.sh check      show what is set up and what expires
#   ./release.sh renew      move the signing key's expiry date 3 years out
#   ./release.sh release    release a new version (tag, push, watch CI)
#
# Run it from Git Bash, MSYS2 (UCRT64) or Linux (it uses GNU sed). It needs git, gh (signed in to an account
# that can edit Telesphorepo/clients), gpg, and JDK 17 for the release step.
#
# No secret is printed, written to a file or passed as a command argument. The script reads
# each one at a hidden prompt (or from a file you already have) and pipes it to "gh secret set".

set -euo pipefail

REPO="Telesphorepo/clients"
UID_NAME="Telesphorepo"
KEYSERVER="keyserver.ubuntu.com"
ROOT="$(cd "$(dirname "$0")" && pwd)"

bold() { printf '\n\033[1m%s\033[0m\n' "$*"; }
info() { printf '  %s\n' "$*"; }
fail() { printf '\n\033[31m%s\033[0m\n' "$*" >&2; exit 1; }
ask() { local a; read -rp "  $1 [y/N] " a; [[ $a == [yY]* ]]; }
pause() { read -rp "  Press Enter when this is done. " _; }

need() {
  for t in "$@"; do
    command -v "$t" >/dev/null || fail "$t is not installed. Install it, then run this again."
  done
}

secret() { # secret NAME ENV: stdin becomes the secret value
  gh secret set "$1" --env "$2" -R "$REPO" >/dev/null && info "Set $1 in $2."
}

hidden() { # hidden PROMPT VAR: read a value at a hidden prompt
  local v
  read -rsp "  $1: " v
  echo
  [[ -n $v ]] || fail "Nothing entered."
  printf -v "$2" '%s' "$v"
}

# ---------------------------------------------------------------- environments

environments() {
  bold "GitHub environments"
  for e in PluginPortal Central; do
    gh api -X PUT "repos/$REPO/environments/$e" --silent
    info "$e exists."
  done
}

# ---------------------------------------------------------------- signing key

signing_key() {
  need gpg gh
  bold "1. Signing key"
  info "Central refuses unsigned files and checks each signature against your public key on a"
  info "public keyserver. The Plugin Portal signs with the same key."
  echo
  gpg --list-secret-keys --keyid-format long "$UID_NAME" 2>/dev/null || true
  local fpr=""
  if ask "Create a NEW key now? (Say no to use an existing key.)"; then
    local email pass pass2
    read -rp "  Email for the key: " email
    hidden "Passphrase for the new key" pass
    hidden "The same passphrase again" pass2
    [[ $pass == "$pass2" ]] || fail "The passphrases are not the same."
    printf '%s' "$pass" | gpg --batch --pinentry-mode loopback --passphrase-fd 0 \
      --quick-gen-key "$UID_NAME <$email>" rsa4096 sign 3y
    fpr=$(gpg --list-secret-keys --with-colons "$UID_NAME <$email>" | awk -F: '/^fpr/ {f=$10} END {print f}')
    info "New key: $fpr (it expires in 3 years)."
    info "Copy the revocation certificate in ~/.gnupg/openpgp-revocs.d/$fpr.rev to a safe place."
  else
    read -rp "  Key ID or fingerprint to use: " fpr
    gpg --list-secret-keys "$fpr" >/dev/null 2>&1 || fail "gpg has no secret key $fpr."
    local pass
    hidden "Passphrase of that key" pass
  fi

  info "Sending the public key to $KEYSERVER ..."
  gpg --keyserver "$KEYSERVER" --send-keys "$fpr"

  local key
  key=$(printf '%s' "$pass" | gpg --batch --pinentry-mode loopback --passphrase-fd 0 \
    --armor --export-secret-keys "$fpr") || fail "gpg refused the passphrase."
  [[ $key == *"PRIVATE KEY BLOCK"* ]] || fail "gpg exported nothing. Check the passphrase."
  environments
  for e in PluginPortal Central; do
    printf '%s\n' "$key" | secret GPG_PRIVATE_KEY "$e"
    printf '%s' "$pass" | secret GPG_PASSPHRASE "$e"
  done
  unset key pass
}

# ---------------------------------------------------------------- renew

renew_key() {
  need gpg gh
  bold "Renew the signing key"
  info "This moves the expiry date of the key you have. The key itself stays the same, so"
  info "signatures made with it keep working."
  echo
  gpg --list-secret-keys --keyid-format long "$UID_NAME" 2>/dev/null || true
  local fpr pass
  read -rp "  Key ID or fingerprint to renew: " fpr
  gpg --list-secret-keys "$fpr" >/dev/null 2>&1 || fail "gpg has no secret key $fpr."
  hidden "Passphrase of that key" pass
  printf '%s' "$pass" | gpg --batch --pinentry-mode loopback --passphrase-fd 0 --quick-set-expire "$fpr" 3y
  info "The key now expires in 3 years."
  info "Sending the renewed public key to $KEYSERVER ..."
  gpg --keyserver "$KEYSERVER" --send-keys "$fpr"
  local key
  key=$(printf '%s' "$pass" | gpg --batch --pinentry-mode loopback --passphrase-fd 0 \
    --armor --export-secret-keys "$fpr") || fail "gpg refused the passphrase."
  [[ $key == *"PRIVATE KEY BLOCK"* ]] || fail "gpg exported nothing. Check the passphrase."
  for e in PluginPortal Central; do
    printf '%s\n' "$key" | secret GPG_PRIVATE_KEY "$e"
  done
  unset key pass
}

# ---------------------------------------------------------------- Plugin Portal

plugin_portal() {
  need gh
  bold "2. Gradle Plugin Portal API key"
  info "Open https://plugins.gradle.org, sign in, click your name, then the API Keys tab."
  info "Generate a key. The page shows gradle.publish.key and gradle.publish.secret."
  local k s
  hidden "gradle.publish.key" k
  hidden "gradle.publish.secret" s
  environments
  printf '%s' "$k" | secret GRADLE_PUBLISH_KEY PluginPortal
  printf '%s' "$s" | secret GRADLE_PUBLISH_SECRET PluginPortal
  unset k s
}

# ---------------------------------------------------------------- Maven Central

central() {
  need gh
  bold "3. Maven Central user token"
  info "Open https://central.sonatype.com, sign in, click your name, View Account,"
  info "Generate User Token. A new token replaces the old one."
  local u p
  hidden "Token username" u
  hidden "Token password" p
  environments
  printf '%s' "$u" | secret CENTRAL_TOKEN_USERNAME Central
  printf '%s' "$p" | secret CENTRAL_TOKEN_PASSWORD Central
  unset u p
  echo
  if ask "Publish snapshots of main to the Central snapshot repository too?"; then
    info "On central.sonatype.com open Namespaces, the menu of com.telesphorepo, Enable SNAPSHOTs."
    pause
    gh variable set CENTRAL_SNAPSHOTS --body true -R "$REPO"
    info "Snapshots are on."
  fi
}

# ---------------------------------------------------------------- check

check() {
  need gh
  bold "Secrets on GitHub (names only)"
  for e in PluginPortal Central; do
    info "$e:"
    gh secret list --env "$e" -R "$REPO" 2>/dev/null | awk '{print "    " $1 "  (set " $2 ")"}' || info "    (environment missing)"
  done
  info "CENTRAL_SNAPSHOTS = $(gh variable get CENTRAL_SNAPSHOTS -R "$REPO" 2>/dev/null || echo 'not set')"
  if command -v gpg >/dev/null; then
    bold "Local signing keys named $UID_NAME"
    gpg --list-secret-keys --keyid-format long "$UID_NAME" 2>/dev/null || info "None on this computer. CI has its own copy."
  fi
  bold "Versions"
  info "gradle/gradle.properties: $(sed -n 's/^version=//p' "$ROOT/gradle/gradle.properties" | tr -d '\r')"
  info "maven/pom.xml:            $(sed -n '0,/<version>/s:.*<version>\(.*\)</version>.*:\1:p' "$ROOT/maven/pom.xml")"
  info "Latest tag:               $(git -C "$ROOT" describe --tags --abbrev=0 2>/dev/null || echo none)"
}

# ---------------------------------------------------------------- release

release() {
  need git gh
  bold "Release"
  cd "$ROOT"
  [[ -z $(git status --porcelain) ]] || fail "The working tree has changes. Commit or stash them first."
  git fetch -q --tags origin
  [[ $(git rev-parse --abbrev-ref HEAD) == main ]] || fail "Switch to main first."
  [[ $(git rev-parse HEAD) == $(git rev-parse origin/main) ]] || fail "main is not the same as origin/main. Pull or push first."
  info "Latest tag: $(git describe --tags --abbrev=0 2>/dev/null || echo none)"
  local v
  read -rp "  New version (for example 0.2.0): " v
  [[ $v =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "Use a plain version such as 0.2.0."
  git rev-parse -q --verify "refs/tags/v$v" >/dev/null && fail "Tag v$v exists. A published version can never be reused."

  info "Setting the version to $v ..."
  sed -i "s/^version=.*/version=$v/" gradle/gradle.properties
  (cd maven && ./mvnw -q -B -ntp org.codehaus.mojo:versions-maven-plugin:2.18.0:set \
    -DnewVersion="$v" -DgenerateBackupPoms=false)
  git --no-pager diff --stat
  ask "Commit \"Release $v\", tag v$v and push? CI then publishes to the Plugin Portal and Maven Central." \
    || { git checkout -- .; fail "Stopped. The version change was undone."; }
  git commit -qam "Release $v"
  git tag "v$v"
  git push -q origin main "v$v"
  info "Pushed. Waiting for the release workflow to start ..."
  sleep 10
  local run
  run=$(gh run list -R "$REPO" --workflow release.yml --limit 1 --json databaseId --jq '.[0].databaseId')
  gh run watch "$run" -R "$REPO" --exit-status \
    && bold "Released $v." \
    || fail "The release workflow failed. See: gh run view $run -R $REPO --log-failed"
  info "The Plugin Portal shows it within minutes; Maven Central within about 30 minutes."
}

# ---------------------------------------------------------------- menu

menu() {
  bold "Telesphorepo clients: release setup"
  info "1  Signing key: create or reuse a GPG key, publish it, store it on GitHub"
  info "2  Gradle Plugin Portal API key"
  info "3  Maven Central user token"
  info "4  All of 1-3 (first setup, or when the key expires)"
  info "5  Renew the signing key before it expires (same key, new date)"
  info "6  Check what is set up"
  info "7  Release a new version"
  local c
  read -rp "  Choose: " c
  case $c in
    1) signing_key ;;
    2) plugin_portal ;;
    3) central ;;
    4) signing_key; plugin_portal; central; check ;;
    5) renew_key ;;
    6) check ;;
    7) release ;;
    *) fail "Choose 1 to 7." ;;
  esac
}

case "${1:-}" in
  check) check ;;
  renew) renew_key ;;
  release) release ;;
  "") menu ;;
  *) fail "Usage: ./release.sh [check|renew|release]" ;;
esac
