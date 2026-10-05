---
title: FireTube privacy policy
---

# FireTube privacy policy

_Last updated: October 4, 2026_

FireTube is a free, open-source music player with no ads. This policy explains what data the official builds (from [GitHub Releases](https://github.com/PalmerinTechnology/firetube/releases)) handle.

## Data on your device

Your playlists, listening history (including the play counts and listening time behind Your stats), downloads and settings are stored on your device. If Android backup is on, Android can include your playlists, history and settings (not downloads) in your Google account's device backup and restore them on a new phone. Uninstalling FireTube deletes the copy on your device.

## Crash reports

Crash reports are on by default. When the app crashes, it sends a crash report to Google Firebase Crashlytics. A report contains the device model, Android version, app version and technical details about the crash. It doesn't contain your name, your email address or what you were listening to. You can turn crash reports off in **Settings**.

## Google sign-in and playlist sync (optional)

Sign-in is optional. If you sign in with Google, FireTube uses Firebase Authentication. It receives your Google account's name, email address and profile picture, plus an account identifier. Your playlists are then stored in Firebase Realtime Database under that identifier, so they sync between your devices. Only your account can read them.

Signing out doesn't delete synced data. To delete it, open an issue (see Contact below) asking for deletion. I'll delete your account and synced playlists.

## Third-party services

FireTube connects directly to these services:

- **YouTube**, for search, streams and recommendations. See [Google's privacy policy](https://policies.google.com/privacy).
- **SponsorBlock** (sponsor.ajay.app), to skip non-music sections. It receives the ID of the video you're playing.
- **LRCLIB** (lrclib.net), only when you open lyrics. It receives the song's title, artist and length.
- **GitHub**, to check for app updates. See the [GitHub privacy statement](https://docs.github.com/site-policy/privacy-policies/github-general-privacy-statement).
- **Google Cast**, only when you cast. The Cast device plays audio streamed from your phone over your local network, and the Cast framework communicates with Google.
- **Google Firebase**, for crash reports and the optional sign-in and sync described above.

FireTube doesn't sell or share your data, and it doesn't show ads.

## Children

FireTube isn't directed at children under 13.

## Changes

Changes to this policy are posted on this page.

## Contact

Stephen Palmerin: open an issue at [github.com/PalmerinTechnology/firetube/issues](https://github.com/PalmerinTechnology/firetube/issues).
