# Nuvio Mobile Memory

## Current Status
- Cleared the previous `NuvioDesktop` repository.
- Cloned the `NuvioMobile` fork from `https://github.com/prneut/NuvioMobile.git` into `d:\Projects\Thuis\Nuvio\NuvioMobile`.
- Configured git remotes:
  - `origin` pointing to the user's fork: `https://github.com/prneut/NuvioMobile.git`
  - `upstream` pointing to the official repository: `https://github.com/NuvioMedia/NuvioMobile.git`
- Verified we are on the default branch: `cmp-rewrite` (Compose Multiplatform rewrite branch).

## Design Choices & Architecture
- **Framework:** Kotlin Multiplatform + Compose Multiplatform.
- **Shared Codebase:** Main features and shared UI reside under [`composeApp/src/commonMain`](file:///d:/Projects/Thuis/Nuvio/NuvioMobile/composeApp/src/commonMain).
- **Target Directories:** 
  - [`androidApp`](file:///d:/Projects/Thuis/Nuvio/NuvioMobile/androidApp) / [`androidMain`](file:///d:/Projects/Thuis/Nuvio/NuvioMobile/composeApp/src/androidMain) for Android.
  - [`iosApp`](file:///d:/Projects/Thuis/Nuvio/NuvioMobile/iosApp) / [`iosMain`](file:///d:/Projects/Thuis/Nuvio/NuvioMobile/composeApp/src/iosMain) for iOS.

## Recent Changes
- **Download Stream Feature**: 
  - Added a "Download" button to the secondary actions menu on the `MetaDetailsScreen`.
  - Added `isDownloadMode` flag to `StreamLaunchStore.kt`.
  - Intercepted the player launch inside `StreamRoute` in `App.kt` so that if `isDownloadMode` is true, the chosen stream (via Autoplay or manual selection) is handed over to `DownloadsRepository` to queue the download, skipping video playback entirely.
  - Created branch `feature-download-autoplay` on the fork to hold these modifications.
