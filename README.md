# telegramforandroid (CQ)

Custom Telegram Android client based on the [official open-source Telegram for Android](https://github.com/DrKLO/Telegram), packaged as **CQ** (`je.cq.tg`).

| | |
| --- | --- |
| Upstream pin | `f32ffeec` — Telegram **13.0.2** (7175) |
| Reference APK | https://me.cq.je/CQ.apk |
| Optimization notes | [docs/CQ_OPTIMIZATION_zh.md](docs/CQ_OPTIMIZATION_zh.md) |
| Upstream details | [UPSTREAM.md](UPSTREAM.md) |

## Quick start

```bash
git submodule update --init --recursive
cp local.properties.example local.properties
# edit sdk.dir, ndk.dir, signing

# Universal APK (same shape as current me.cq.je/CQ.apk)
./gradlew :TMessagesProj_App:assembleAfatRelease

# Smaller arm64-only APK (recommended for phones)
./gradlew :TMessagesProj_App:assembleArm64Release
```

Outputs are named `CQ.apk` / `CQ-arm64.apk` under the App module build directory.

## CQ customizations

- Application id `je.cq.tg`, display name **CQ**
- Sideload-oriented `BuildVars` (no Passkeys / Play Billing; update URL → me.cq.je)
- Optional `arm64` flavor and `CQ_ARM64_ONLY` Gradle flag
- Secrets belong in `local.properties`, not in git

Set `TELEGRAM_API_ID` / `TELEGRAM_API_HASH` in gitignored `local.properties` (see `local.properties.example`). Do not commit them.

## License

Upstream Telegram for Android is GPLv2 or later — see [LICENSE](LICENSE).
