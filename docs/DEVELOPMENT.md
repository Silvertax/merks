# Development

## Build

```
./gradlew testDebugUnitTest assembleRelease
```

Requires JDK 21 and the Android SDK (platform 35). Release signing reads `MERKS_KEYSTORE`, `MERKS_KEYSTORE_PASSWORD`, `MERKS_KEY_ALIAS` and `MERKS_KEY_PASSWORD` from the environment; without them the debug key is used.

## Releasing

1. Raise `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Describe the changes in a new section `## <version>` of `CHANGELOG.md`.
3. Create a GitHub release with the tag `v<version>`. Title and description can stay empty.

The Build workflow then checks that the tag matches the app version, runs the tests, builds and signs `merks.apk`, attaches it to the release and fills in an empty title and description. The description comes from `CHANGELOG.md`, or from the commit messages if the changelog has no section for the version.

## Tests

`./gradlew testDebugUnitTest` runs the unit tests and the app tests in a simulated Android 15 (Robolectric), in German and English: update from an old database, speaking to confirmation, calendar series, reminder buttons, settings, language choice, list and widget. Nothing goes to the network.

- `MERKS_LIVE=1 ./gradlew testDebugUnitTest --tests '*LiveApiTest'` also talks to the real Anthropic API; with `MERKS_API_KEY` set it checks a working key as well.
- `MERKS_SCREENSHOTS=<folder>` renders the screens as PNG files; `MERKS_SCREENSHOTS_LANG=en` renders the English app.
