#!/usr/bin/env bash
# Sync CQ workspace to a DrKLO/Telegram commit, then remind about CQ overlays.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
COMMIT="${1:-f32ffeec763f0bdbf4bec25b52d654f77bff733d}"
TMP="${TMPDIR:-/tmp}/telegram-upstream-$$"

echo "==> Fetching DrKLO/Telegram @ ${COMMIT}"
git clone --depth 1 https://github.com/DrKLO/Telegram.git "$TMP"
cd "$TMP"
git fetch --depth 1 origin "$COMMIT"
git checkout "$COMMIT"

echo "==> Copying sources into ${ROOT} (preserving cq/, docs/, scripts/, .git)"
# shellcheck disable=SC2046
tar --exclude='.git' -C "$TMP" -cf - . | tar -C "$ROOT" -xf -

echo "==> Initializing native submodules (this is large)"
cd "$ROOT"
git submodule sync --recursive || true
git submodule update --init --recursive

echo "==> Done. Re-check CQ overlays:"
echo "    - gradle.properties APP_PACKAGE=je.cq.tg"
echo "    - TMessagesProj_App arm64 flavor / CQ.apk naming"
echo "    - BuildVars fork flags + me.cq.je update URL"
echo "    - AppName strings (CQ)"
echo "See docs/CQ_OPTIMIZATION_zh.md"
rm -rf "$TMP"
