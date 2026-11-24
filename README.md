# Figurinhas 🎞️

A small, free Android app that makes WhatsApp stickers out of GIFs, videos and photos.
It has no ads, no premium tier, no account, and no internet permission.

## Features

- **Animated stickers from GIFs and videos** (MP4, WebM, MOV) and **static stickers from photos**.
- **Share to Figurinhas**: share a GIF or video from Giphy, Tenor, the gallery, a browser and so on, and it opens straight in the editor.
- **Editor**
  - Trim with a live preview. The app keeps clips within WhatsApp's 10-second limit.
  - Speed (0.5× to 3×), frames per second, and normal, reverse or boomerang loops.
  - Fit or fill the square. Pinch to zoom and drag to position.
  - Rounded corners.
  - Text caption with an outline, a choice of colors, and top or bottom placement.
  - Emojis, which WhatsApp uses for sticker search.
- **Automatic size fitting**: the app searches quality and frame-rate settings until the sticker fits WhatsApp's limits (500 KB animated, 100 KB static, 512×512). You never have to adjust them by hand.
- **Packs**: create, rename, reorder, pick the tray icon, and delete. A pack holds 3 to 30 stickers. The app shows whether a pack is already added to WhatsApp, and changes sync to WhatsApp automatically.
- Works with **WhatsApp** and **WhatsApp Business**.
- Interface in English and Brazilian Portuguese.

## Install

Download `Figurinhas.apk` from the latest [GitHub Release](../../releases) or from the build artifacts of the **Build APK** workflow. Open it on your phone and allow "install unknown apps" when Android asks.

To publish a release, push a tag:

```bash
git tag v1.0.0 && git push origin v1.0.0
```

## How it works (technical)

- WhatsApp reads third-party packs through a `ContentProvider` that follows WhatsApp's official sticker contract (`provider/StickerContentProvider.kt`). The "Add to WhatsApp" button sends the `com.whatsapp.intent.action.ENABLE_STICKER_PACK` intent.
- Animated WebP is built **without native code**. Android's own encoder compresses each frame (`Bitmap.compress(WEBP_LOSSY)`). `engine/AnimatedWebP.kt` then takes the `VP8`/`ALPH`/`VP8L` chunks out of each frame and wraps them into `ANMF` frames inside a `VP8X` + `ANIM` RIFF container.
- `engine/StickerMaker.kt` estimates the bytes per frame at each quality level from a few sample frames. It picks the best quality and frame-skip combination, then checks the result with a real encode.
- GIFs are decoded by a small built-in decoder (`engine/GifReader.kt`) that handles every disposal method correctly, so transparent GIFs leave no trails. Its output matches Pillow pixel for pixel. Video frames come from `MediaMetadataRetriever`, read in sequential batches on Android 9 and later.

## Build

Requirements: JDK 17 or later and the Android SDK (platform 35).

```bash
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest
```

### Signing key

The repository holds no signing key. Release builds are signed with:

1. `signing/release.jks` and `signing/signing.properties` when they exist locally (both are git-ignored, see `signing/README.md`), or
2. the `SIGNING_*` repository secrets in CI, or
3. the debug key as a fallback.

Android only installs an update over an existing app when both APKs are signed with the same key. To have CI builds update your installed app, add your key as repository secrets:

| Secret | Value |
| --- | --- |
| `SIGNING_KEYSTORE_BASE64` | `base64 -w0 release.jks` |
| `SIGNING_STORE_PASSWORD` | the keystore password |
| `SIGNING_KEY_ALIAS` | `figurinhas` |
| `SIGNING_KEY_PASSWORD` | the key password |
