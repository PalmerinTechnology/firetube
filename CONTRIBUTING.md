# Contributing to FireTube

Thanks for helping. A few notes to keep things smooth.

## Reporting a problem
Open an issue using the **Bug report** template. Include:
- your FireTube version (Settings → About),
- your device and Android version,
- what you did, what you expected, and what happened.

If a song won't play, include its YouTube link.

**If lots of songs suddenly stop playing,** YouTube has probably changed something.
Check for an open "YouTube canary failing" issue before opening a new one.

## Making a change
1. Fork the repo and create a branch from `dev`.
2. Build and test:
   ```sh
   ./gradlew assembleGithubDebug testGithubDebugUnitTest lintGithubDebug :extractor:test
   ```
   You don't need a Firebase config; see the README.
3. Keep pull requests focused, one change per PR, and explain *why* in the description.
4. Open the PR against `dev`. CI must pass.

## Code style
- Kotlin, Jetpack Compose, coroutines.
- Match the surrounding code's naming and comment style.
- Comment the **why**, not the what.
- Anything that talks to YouTube goes in the `:extractor` module, behind `StreamSource`.
- Add tests for logic: see `extractor/src/test` and `app/src/test`.

## License
By contributing, you agree that your contributions are licensed under the project's
[GPLv3](LICENSE) license.
