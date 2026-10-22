# Publishing SpacePixels

This document covers preparing the headless core and desktop SpacePixels publications for Maven Central. Bundle tasks stage/sign locally; they never upload.

| Coordinate | Contents |
|---|---|
| `eu.startales.spacepixels:spacepixels-core:<version>` | Headless API and supporting implementation/report resources; no desktop GUI/FlatLaf dependency |
| `eu.startales.spacepixels:spacepixels:<version>` | Desktop/tools and icons, with a transitive API dependency on core; existing Maven/Gradle consumers retain API access |

Both compile for Java 21 and provide binary, sources and Javadoc artifacts, POM and Gradle module metadata. Source package names are unchanged; the desktop JAR no longer duplicates core classes. Installed distributions include both JARs; manual classpaths must do the same. Core still uses headless AWT/`java.desktop` for images/reports.

## Maven Central publishing

SpacePixels is configured to generate a Maven Central-compatible upload bundle with:

- `javadoc.jar`
- `sources.jar`
- POM metadata
- PGP signatures
- checksum files

The build uses the same local staging and bundle flow as JPlateSolve. Instead of pushing directly to the Central Portal from Gradle, it stages a signed Maven repository under `build/repos/central-staging` and then zips that repository into a Central Portal upload bundle.

## Local configuration

1. Edit `gradle.properties` in the project root or define the same properties in `%USERPROFILE%\.gradle\gradle.properties`.
2. Fill in your local signing values.

The expected properties are:

- `pomDeveloperId`
- `pomDeveloperName`
- `pomDeveloperEmail`
- `pomDeveloperOrganization`
- `pomDeveloperOrganizationUrl`
- `signingKey`, `signingPassword`
- `signing.keyId`, `signing.password`, `signing.secretKeyRingFile`

Example `gradle.properties`:

```properties
pomDeveloperId=ppissias
pomDeveloperName=Petros Pissias
pomDeveloperEmail=petrospis@gmail.com
pomDeveloperOrganization=GitHub
pomDeveloperOrganizationUrl=https://github.com/ppissias
signingKey=-----BEGIN PGP PRIVATE KEY BLOCK-----\n\nlQdGBF...\n...\n-----END PGP PRIVATE KEY BLOCK-----\n
signingPassword=YOUR_SIGNING_PASSWORD
```

A value in a `.properties` file ends at the end of its line, so write the armored key on one line and put `\n` where the key's line breaks are, including the empty line after the `BEGIN` line. The build also turns a literal `\n` into a line break, so the same one-line form works in the environment variable below.

You can also provide the in-memory signing values through environment variables:

```bash
export ORG_GRADLE_PROJECT_signingKey="..."
export ORG_GRADLE_PROJECT_signingPassword="..."
```

## Build the Central bundle

First validate the packaging without signing or release credentials:

```powershell
.\gradlew.bat check coreConsumerTest
.\gradlew.bat coreConsumerTest -PcoreConsumerMetadata=pom
```

`check` includes the core binary/sources/Javadoc/POM/Gradle-metadata checks, desktop/core ownership checks, ordinary tests and an isolated headless consumer build. Core is published unsigned to `build/repos/consumer-test`. With local composites enabled, the test also stages the local engine/solver binaries and their actual generated POMs there, without altering sibling publications or Maven Local. The consumer has no composite builds, disables global project substitution, and checks preparation, detection, calibrated Auto-Tune, reporting/assets and absence of GUI/FlatLaf. This proves packaging of the tested source pair, not that those dependencies are released to Central.

The second command repeats the consumer checks using only Maven POM metadata, ignoring Gradle-module redirection; the default mode tests Gradle metadata. When a sibling flag is disabled, its group is excluded from the consumer's local staging repository, so stale staged source binaries cannot stand in for the selected published dependency.

**Release gate (JA-11):** before distributing, publish/declare the engine/solver versions supplying the required APIs and validate the real supported published pair and Java baseline without local substitutions. Preserve the active-development flags; use explicit `-PuseLocalJTransientBuild=false -PuseLocalJPlateSolveBuild=false` for the separate published-pair check. Do not claim that an existing `1.0.5` coordinate necessarily contains the required JA-01 API. Inspect final bundles/metadata and complete desktop/CLI checks before upload.

Build only the headless core bundle:

```bash
./gradlew :core:mavenCentralBundle
```

On Windows:

```powershell
.\gradlew.bat :core:mavenCentralBundle
```

This will:

- run the normal checks
- stage the signed core publication into `build/repos/central-staging`
- create a ZIP containing only core's current-version Maven repository subtree

Generated bundle:

```text
core/build/distributions/spacepixels-core-<version>-central-bundle.zip
```

For the desktop plus its required core publication, the existing root command remains `gradlew.bat mavenCentralBundle` (or `./gradlew mavenCentralBundle`). It stages both publications and writes `build/distributions/spacepixels-<version>-central-bundle.zip`. Both bundle types include matching sources/Javadocs, metadata, signatures and checksums, and include only their current-version artifact subtrees rather than stale staging versions. An explicit root Maven Local publication also publishes core first. Shared publishing configuration lives in `gradle/publishing.gradle`; signing and developer metadata use the same local properties/environment variables for both modules.

## Upload to the Central Portal

Upload the generated ZIP bundle manually in the Maven Central Portal after verifying that your namespace is ready there.

The standardized Gradle entrypoints are:

- `validateCentralReleaseConfiguration`
- `bundleCentralPortal`
- `mavenCentralBundle`

Each generated Central publication uses:

- coordinates from the table above (core and desktop are separate artifacts)
- sources JAR: enabled
- Javadoc JAR: enabled
- POM metadata: license, developer, and SCM info included
