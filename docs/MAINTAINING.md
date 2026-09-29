# Maintaining FireTube

Notes for publishing official builds. Contributors don't need any of this.

## Release signing
Official builds are signed with the FireTube release key. It's the same key as 1.x, so existing installs
upgrade in place. Locally, signing reads `keystore.properties` at the repo root, which is git-ignored:

```properties
storeFile=/path/to/release.keystore
storePassword=…
keyAlias=…
keyPassword=…
```

Without that file, release builds are unsigned.

## Repository secrets (for the Release workflow)
| Secret | What it is |
|---|---|
| `KEYSTORE_BASE64` | the release keystore, base64-encoded |
| `STORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` | the keystore's credentials |
| `GOOGLE_SERVICES_JSON` | the whole contents of the Firebase `google-services.json` |

## Releasing
1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`, and merge to `dev`.
2. Push an annotated tag. Its message becomes the release notes:
   `git tag -a v2.0.1 -m "What changed" && git push origin v2.0.1`
3. The **Release** workflow then:
   - builds the release APK;
   - attaches `FireTube-<version>-<code>.apk` to a GitHub Release, where the in-app updater finds it
     (GitHub adds the source archives);

## Firebase (free Spark plan)
The app uses Crashlytics, Auth (Google sign-in) and Realtime Database.

1. **Authentication → Sign-in method:** enable **Google**.
2. **Project settings → Your apps:** add the SHA-1 and SHA-256 of the release key.
3. **Download `google-services.json`.** If it has no `client_type: 3` (web) OAuth client, add the
   project's web client ID to it. Credential Manager needs it as the server client ID.
4. **Deploy the database rules** from `database.rules.json`: `firebase deploy --only database`.
5. **Restrict the Firebase API key** to Android apps: package `com.palmerintech.firetube` plus the SHA-1s.
6. **Keep billing disabled on the project.** With no billing account, Spark can never be charged.
