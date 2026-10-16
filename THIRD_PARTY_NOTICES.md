# Third-party notices

This file describes third-party build components. It does not grant a project-wide license to this application's first-party code or assets.

## Included Gradle Wrapper

The Gradle 8.9 Wrapper JAR and launcher scripts are included to provide a reproducible build entry point. The scripts retain their original copyright and license headers. The Wrapper JAR retains its embedded `META-INF/LICENSE`.

- Project: [Gradle](https://gradle.org/)
- License: Apache License, Version 2.0. The full text from the Wrapper JAR is included in [licenses/Apache-2.0.txt](licenses/Apache-2.0.txt).
- Wrapper JAR SHA-256: `498495120a03b9a6ab5d155f5de3c8f0d986a449153702fb80fc80e134484f17`.
- The distribution checksum is pinned in `gradle/wrapper/gradle-wrapper.properties`.
- Verification reference: [Gradle release checksums](https://gradle.org/release-checksums/).

The full Gradle distribution is downloaded by the Wrapper; it is not bundled in this repository and includes its own notices.

## Build-resolved dependencies

The following application dependencies are resolved by Gradle and are not vendored as binaries or source in this repository. The listed versions are the runtime graph for the current app configuration; their published POM metadata identifies Apache License, Version 2.0. Preserve the applicable notices and license texts when distributing dependency-containing binaries.

| Dependency | Version |
|---|---|
| androidx.webkit:webkit | 1.12.1 |
| androidx.core:core | 1.1.0 |
| androidx.versionedparcelable:versionedparcelable | 1.1.0 |
| androidx.collection:collection | 1.0.0 |
| androidx.annotation:annotation-experimental | 1.4.1 |
| androidx.annotation:annotation-jvm | 1.8.1 |
| androidx.lifecycle:lifecycle-runtime | 2.0.0 |
| androidx.lifecycle:lifecycle-common | 2.0.0 |
| androidx.arch.core:core-common | 2.0.0 |
| org.jetbrains.kotlin:kotlin-stdlib | 1.7.10 |
| org.jetbrains.kotlin:kotlin-stdlib-common | 1.7.10 |
| org.jetbrains:annotations | 13.0 |

Android Gradle Plugin 8.7.3 is a build tool, separate from the application's runtime dependencies, and is also published under Apache License, Version 2.0. The Android SDK is obtained separately under its own terms and is not included here.

## External services

No license to third-party website content, branding, or services is conveyed by this repository. The application uses independent interface names and symbols and is not an official or affiliated client of the referenced services.
