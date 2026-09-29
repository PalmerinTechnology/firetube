---
title: FireTube privacy policy
---

# FireTube privacy policy

_Last updated: September 29, 2026_

FireTube is a free, open-source music player with no ads. This policy explains what data the official builds (from [GitHub Releases](https://github.com/spalmerin21/firetube/releases)) handle.

## Data on your device

Your playlists, listening history, downloads and settings are stored only on your device. Uninstalling FireTube deletes them.

## Crash reports

When the app crashes, it sends a crash report to Google Firebase Crashlytics. A report contains the device model, Android version, app version and technical details about the crash. It doesn't contain your name, your email address or what you were listening to. You can turn crash reports off in **Settings**.

## Google sign-in and playlist sync (optional)

Sign-in is optional. If you sign in with Google, FireTube uses Firebase Authentication. It receives your Google account's name, email address and profile picture, plus an account identifier. Your playlists are then stored in Firebase Realtime Database under that identifier, so they sync between your devices. Only your account can read them.

To delete this data, open an issue (see Contact below) asking for deletion. I'll delete your account and synced playlists.

## Third-party services

FireTube connects directly to these services:

- **YouTube**, for search, streams and recommendations. See [Google's privacy policy](https://policies.google.com/privacy).
- **SponsorBlock** (sponsor.ajay.app), to skip non-music sections. It receives the ID of the video you're playing.
- **GitHub**, to check for app updates. See the [GitHub privacy statement](https://docs.github.com/site-policy/privacy-policies/github-general-privacy-statement).
- **Google Firebase**, for crash reports and the optional sign-in and sync described above.

FireTube doesn't sell or share your data, and it doesn't show ads.

## Children

FireTube isn't directed at children under 13.

## Changes

Changes to this policy are posted on this page.

## Contact

Stephen Palmerin: open an issue at [github.com/spalmerin21/firetube/issues](https://github.com/spalmerin21/firetube/issues).
