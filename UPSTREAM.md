# Upstream pin

| Field | Value |
| --- | --- |
| Upstream | [DrKLO/Telegram](https://github.com/DrKLO/Telegram) |
| Commit | `f32ffeec763f0bdbf4bec25b52d654f77bff733d` |
| Tag / message | `update to 13.0.2 (7175)` |
| Matched APK | `https://me.cq.je/CQ.apk` — package `je.cq.tg`, versionName `13.0.2`, versionCode `71759` (= `7175 * 10 + 9`) |
| AGP (from APK metadata) | `8.13.2` |

Native JNI third-party trees are Git submodules. After clone:

```bash
git submodule update --init --recursive
```

Or use `./scripts/sync-upstream.sh` to refresh to a newer upstream commit, then re-apply CQ overlays listed in `docs/CQ_OPTIMIZATION_zh.md`.
