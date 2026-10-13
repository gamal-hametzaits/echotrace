# Build notes

Every push that changes files under `android/` builds a signed APK on a free GitHub runner.
A new versionName publishes the normal release. An unchanged versionName publishes a
`vX.Y.Z-build.N` prerelease (APK and .apk.txt); the newest 10 are kept.
The signer is checked against v1.0.17 before anything is published.
