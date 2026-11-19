#!/data/data/com.termux/files/usr/bin/bash
# Usage: bash release.sh 1.1
set -e
[ -z "$1" ] && { echo "Usage: bash release.sh <version>  e.g. 1.1"; exit 1; }
git add -A
git commit -m "Cosmos v$1" || true
git push
git tag "v$1" && git push origin "v$1"
echo "Building v$1 on GitHub (~5 min). Installed apps will offer the update."
