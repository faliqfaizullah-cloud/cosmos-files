#!/data/data/com.termux/files/usr/bin/bash
# Run inside the Cosmos folder in Termux
set -e
pkg update -y && pkg install -y git gh
gh auth status >/dev/null 2>&1 || gh auth login
U=$(gh api user -q .login)
git config --global user.name  >/dev/null || git config --global user.name "$U"
git config --global user.email >/dev/null || git config --global user.email "$U@users.noreply.github.com"
[ -d .git ] || git init -b main
git add -A
git commit -m "Cosmos: initial release" || true
gh repo create cosmos-files --public --source=. --push
git tag v1.0 && git push origin v1.0
echo "Build takes ~5 min. Then: gh release download v1.0 -p Cosmos.apk -D ~/storage/downloads"
