# Third-party notices

This file describes third-party build and runtime components. It does not grant a project-wide license to this application's first-party code or assets.

## Included Gradle Wrapper

The Gradle 8.9 Wrapper JAR and launcher scripts are included to provide a reproducible build entry point. The scripts retain their original copyright and license headers. The Wrapper JAR retains its embedded `META-INF/LICENSE`.

- Project: [Gradle](https://gradle.org/)
- License: Apache License, Version 2.0. The full text from the Wrapper JAR is included in [licenses/Apache-2.0.txt](licenses/Apache-2.0.txt).
- Wrapper JAR SHA-256: `498495120a03b9a6ab5d155f5de3c8f0d986a449153702fb80fc80e134484f17`.
- The distribution checksum is pinned in `gradle/wrapper/gradle-wrapper.properties`.
- Verification reference: [Gradle release checksums](https://gradle.org/release-checksums/).

The full Gradle distribution is downloaded by the Wrapper; it is not bundled in this repository and includes its own notices.

## Build-resolved dependencies

The following direct application dependencies are resolved by Gradle and are not vendored as binaries or source in this repository. Gradle also resolves their AndroidX, Kotlin, and supporting runtime dependencies. Their respective published licenses and notices apply. Firebase Messaging and Google Play services were removed in 0.9.0.

| Dependency | Version |
|---|---|
| androidx.webkit:webkit | 1.12.1 |
| androidx.swiperefreshlayout:swiperefreshlayout | 1.2.0 |

- AndroidX uses Apache License, Version 2.0. See [AndroidX](https://developer.android.com/jetpack/androidx).
- Preserve dependency-provided license/notice resources when distributing the built application. The Apache-2.0 text is included in [licenses/Apache-2.0.txt](licenses/Apache-2.0.txt).

Android Gradle Plugin 8.7.3 is a build tool, separate from the application's runtime dependencies, and is also published under Apache License, Version 2.0. The Android SDK is obtained separately under its own terms and is not included here.

## External services

No license to third-party website content, branding, or services is conveyed by this repository. The application uses locally implemented themes referencing Gmail, X, Instagram, Microsoft Excel, and Naver Cafe, including their names and visual conventions. It is not an official or affiliated client of those services. Theme names do not change the underlying DCInside service or create access to the referenced services' features.
