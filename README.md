# Omran Caption — Android

Native Android app (Kotlin) that captures **system audio** (any app/video
playing on the phone) using the `MediaProjection` + `AudioPlaybackCapture`
APIs (Android 10+), sends short audio chunks to the same `/api/caption`
backend used by Tarjiman Live / Tarjiman Desktop, and shows the live
translated captions in a floating overlay window on top of any app.

## Why a native app?
Browsers and PWAs on Android/iOS are sandboxed and cannot capture system
audio from other apps — that's an OS-level restriction, not a limitation of
this project. A native app with the `MediaProjection` permission (the same
permission Google's own Live Caption feature uses) is the only way to do
this on Android. iOS does not allow *any* third-party app (native or web)
to do this at all.

## How it works
1. `MainActivity` — pick captioning + translation language, request mic,
   notification, overlay, and screen-capture (`MediaProjection`) permissions.
2. `CaptionService` — a foreground service that opens an `AudioRecord` with
   `AudioPlaybackCaptureConfiguration` to capture system audio, chunks it
   into ~3.5s WAV clips, and POSTs to `https://tarjiman-live.vercel.app/api/caption`.
3. `OverlayService` — draws a small draggable, resizable window that shows
   only the translated caption text (no buttons), staying on top of any app.

## Build
GitHub Actions (`.github/workflows/build.yml`) builds a debug APK on every
push to `main` and uploads it as a workflow artifact — download it from the
Actions tab, no Play Store needed.

## Known limitations
- Requires Android 10 (API 29) or newer.
- Some apps mark their audio as "not capturable" (e.g. some DRM-protected
  video/music apps); their audio can't be captured by design — same
  restriction affects every app on the Play Store, including Google's own.
- iOS: not possible in any form (Apple platform restriction).
