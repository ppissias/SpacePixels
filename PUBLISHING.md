# Publishing SpacePixels

SpacePixels has one Maven Central publication, configured in the root `build.gradle`:

| Coordinate | Contents |
|---|---|
| `eu.startales.spacepixels:spacepixels:<version>` | Headless API, required supporting packages/report resources and CLI tools (`BatchDetectionCli`, `ArtificialStarInjector`); no GUI, jobs/tasks, GUI icons or FlatLaf |

The same single Gradle project builds the desktop application separately. `run`, `installDist`, `distZip` and `distTar` retain the GUI and tool launchers. The unpublished `desktopJar` companion and FlatLaf belong only to desktop distributions; neither is a Maven publication. There is no `:core` project or `spacepixels-core` publication.

The API artifact compiles for Java 21 and supplies matching sources, Javadocs, POM and Gradle module metadata. JTransient is a compile/API dependency because public requests/results expose its types. FITS, Gson and JPlateSolve are runtime-transitive implementation dependencies; JPlateSolve does not need `api` scope for the supported detection API. Headless AWT/`java.desktop` remains necessary for images/reports. See [API.md](API.md#dependency) for the embedding boundary.

## Maven Central publishing

SpacePixels is configured to generate a Maven Central-compatible upload bundle with:

- the headless API/CLI binary JAR
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
.\gradlew.bat check apiConsumerTest
.\gradlew.bat apiConsumerTest -PapiConsumerMetadata=pom
```

`check` includes the API/CLI binary/sources/Javadoc/POM/Gradle-metadata checks, desktop/API ownership checks and ordinary tests. `apiConsumerTest` is an isolated headless consumer build; `mavenCentralBundle` runs it (with Gradle metadata) before it builds the bundle. The API is published unsigned to `build/repos/consumer-test`. With local composites enabled, the test also stages the local engine/solver binaries and their actual generated POMs there, without altering sibling publications or Maven Local. The consumer has no composite builds, disables global project substitution, and checks preparation, detection, calibrated Auto-Tune, reporting/assets, packaged CLI execution, compile/runtime dependency scopes and absence of GUI/FlatLaf. This proves packaging of the tested source pair, not that those dependencies are released to Central.

The second command repeats the consumer checks using only Maven POM metadata, ignoring Gradle-module redirection; the default mode tests Gradle metadata. When a sibling flag is disabled, its group is excluded from the consumer's local staging repository, so stale staged source binaries cannot stand in for the selected published dependency.

**Release gate (JA-11):** before distributing, choose/pin the engine/solver versions supplying the required APIs and validate that exact packaged combination and Java baseline without local substitutions. Preserve the active-development flags; use explicit `-PuseLocalJTransientBuild=false -PuseLocalJPlateSolveBuild=false` for the separate artifact check. `validateCentralReleaseConfiguration`, which Central staging and `mavenCentralBundle` run, refuses while either library comes from a local checkout. With both flags off, a selected library version Central does not have fails to resolve, so no bundle can name it. Earlier checks recorded missing historical coordinates; there is no obligation to publish an older unused version. Publish/verify the versions actually selected for this release, inspect final bundles/metadata and complete desktop/CLI checks before upload. No cross-version library compatibility matrix is required; see the [coordinated release policy](DEVELOPER.md#build-and-run).

Build the single headless API/CLI bundle:

```bash
./gradlew mavenCentralBundle
```

On Windows:

```powershell
.\gradlew.bat mavenCentralBundle
```

This will:

- run the normal checks
- stage the signed API/CLI publication into `build/repos/central-staging`
- create a ZIP containing only the API's current-version binary/sources/Javadoc, POM/module metadata, signatures and checksums

Generated bundle:

```text
build/distributions/spacepixels-<version>-central-bundle.zip
```

Publishing/signing and developer-metadata configuration live in the root `build.gradle`. The bundle selects only the current API publication's expected filenames, excluding stale versions, old `spacepixels-core` artifacts and any desktop-classifier files left in staging by earlier builds. `bundleCentralPortal` is the underlying task; `mavenCentralBundle` remains the single standard command. Signing and developer metadata use the same existing local properties/environment variables. Maven Local publication also publishes only the API/CLI artifact.

For the desktop application, use `gradlew.bat installDist` (or `./gradlew installDist`), not a Central publication command. Distribute the complete installed application or desktop ZIP/TAR rather than the API JAR alone.

## Upload to the Central Portal

Before release, select and pin the exact JTransient/JPlateSolve versions for this SpacePixels release and use the [selected-artifact validation workflow](docs/release-artifacts.md). Verify actual public artifact availability with composites and the validation-only repository disabled. One application binary need not support historical engine versions, and an old-JAR/new-runtime or two-engine matrix is not required. Central staging rejects `releaseValidationRepository`; unsigned locally staged libraries cannot establish a publishable dependency combination.

Upload the generated ZIP bundle manually in the Maven Central Portal after verifying that your namespace is ready there.

The standardized Gradle entrypoints are:

- `validateCentralReleaseConfiguration`
- `bundleCentralPortal`
- `mavenCentralBundle`

The Central publication uses:

- the original `eu.startales.spacepixels:spacepixels:<version>` coordinate only
- sources JAR: enabled
- Javadoc JAR: enabled
- POM metadata: license, developer, and SCM info included
