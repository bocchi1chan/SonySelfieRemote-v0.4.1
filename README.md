# SonySelfieRemote v0.4.4

Selfie-monitor build for Sony A7R II.

## New in v0.4.4
- Normal multi-shot count is now an editable **1–1000** value. Normal mode uses the already-tested `actTakePicture` path, so the requested count is exact.
- For very large groups, the camera still captures the full requested count, but the phone downloads only the **latest 6 postview JPEGs** for quick pose review.
- Added **High-speed continuous shooting (Experimental)** toggle. It is enabled only when the connected camera appears to expose continuous-shooting capability.
- Experimental mode attempts `setContShootingMode` / `setContShootingSpeed` and then `startContShooting` / `stopContShooting`.
- If `getEvent` v1.2 reports continuous-shooting postviews, the app uses the reported count. If not, A7R II's nominal ~5 fps timing is used, so experimental-mode shot count can be approximate after the buffer fills.
- If the high-speed API fails, the app automatically falls back to normal multi-shot mode.
- Fixed a duplicate MediaStore JPEG write in the prior source.

Default selfie workflow remains 15 s delay / 3 shots / 1 s interval / 5 s review.
