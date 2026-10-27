# Publishing SpacePixels

SpacePixels has one Maven Central publication, configured in the root `build.gradle`:

| Coordinate | Contents |
|---|---|
| `eu.startales.spacepixels:spacepixels:<version>` | Headless API, required supporting packages/report resources and CLI tools (`BatchDetectionCli`, `ArtificialStarInjector`); no GUI, jobs/tasks, GUI icons or FlatLaf |

The same single Gradle project builds the desktop application separately. `run`, `installDist`, `distZip` and `distTar` retain the GUI and tool launchers. The unpublished `desktopJar` companion and FlatLaf belong only to desktop distributions; neither is a Maven publication. There is no `:core` project or `spacepixels-core` publication.

The API artifact compiles for Java 21 and supplies matching sources, Javadocs, POM and Gradle module metadata. JTransient is a compile/API dependency because public requests/results expose its types. FITS, Gson and JPlateSolve are runtime-transitive implementation dependencies; JPlateSolve does not need `api` scope for the supported detection API. Headless AWT/`java.desktop` remains necessary for images/reports. See [API.md](API.md#dependency) for the embedding boundary.

## Windows installer

Build on Windows x64 with a 64-bit JDK 21 selected by `JAVA_HOME`. The JDK supplies `jpackage` and `jlink`. Building the installer also requires the WiX 3 build tools (`candle.exe` and `light.exe`); WiX is not needed by the installed application.

```powershell
.\gradlew.bat windowsAppImage
.\gradlew.bat windowsInstaller -PwixHome="<WiX tools folder>"
```

`windowsAppImage` creates `build/windows/image/SpacePixels/`. `windowsInstaller` creates the image if needed, then writes `build/installers/SpacePixels-<installer-version>.exe`. WiX can instead be configured with `wixHome` in git-ignored `gradle.properties`, `WIX_HOME`, `WIX`, or PATH. Ordinary builds and archive distributions do not require Windows or WiX.

The image contains the desktop and headless JARs, their dependencies, all shared files from the Java distribution and a private Java 21 runtime with its legal notices. The default detection profile, appearance example and application license are included unchanged under `app/`; future distribution configuration files and examples are copied too. Native launchers replace the archive's launcher scripts:

- `SpacePixels.exe` opens the desktop without a console.
- `spacepixels-batch.exe` runs the batch CLI in a console. For example, from the installation folder: `.\spacepixels-batch.exe "<sequence folder>" "app\config\default_detection_profile.json"`.
- `injectStars.exe` runs the artificial-star injector in a console, with the same arguments as the archive's `injectStars` script.

All three retain the distribution's `-XX:MaxRAMPercentage=80.0 -XX:+UseG1GC` defaults. Launcher options are in `app/SpacePixels.cfg`, `app/spacepixels-batch.cfg` and `app/injectStars.cfg`; an upgrade can replace these application files. The bundled runtime retains `java.exe` and `javaw.exe`, which the Appearance restart helper needs. The ICO is generated from the existing application PNGs.

Setup is unsigned and installs per user, with a directory chooser and optional Start menu and desktop shortcuts. A later installer upgrades the same installation. Keep the upgrade UUID in `gradle/windows-installer.gradle` unchanged across releases. The application version `2026.10-04` maps to Windows installer version `26.10.4`; `-PwindowsInstallerVersion=major.minor.build` overrides the mapping, with maximum components `255.255.65535`. Use a higher installer version for an upgrade; the application continues to display its own version.

The installer bundles its library JARs and can use the local composite library builds. Users do not need those libraries to be published separately to Maven Central. Run `releaseCheck` before distribution; archive and installer tasks themselves only package the application. There is no automatic updater; code signing remains a separate release step.

Before release, check the actual EXE on a clean Windows account or disposable VM with neither Java nor WiX installed:

1. Install to a folder containing spaces and check the directory and shortcut choices.
2. Launch the desktop, run a small analysis, open its report and test Appearance's restart. Also run the batch launcher with the installed example profile.
3. Save settings, then close the application and upgrade an older installer using the same upgrade UUID and a higher version. Check that settings still load.
4. Uninstall through Windows Installed apps and verify that home settings and image datasets remain. The installer owns only its application files.

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

Run the release checks without signing or release credentials:

```powershell
.\gradlew.bat releaseCheck
```

`releaseCheck` runs the ordinary `check`, `apiConsumerTest`, report JavaScript regressions, GUI mechanics and available real-data smoke tests. Node (on PATH or selected with `NODE_EXECUTABLE`) and an unlocked desktop are required; the GUI tests move the mouse and type into the application. Configure optional reference data with `realDataRoot` in git-ignored `gradle.properties`; missing datasets are reported as skips. This does not replace installer lifecycle checks or full scientific regressions.

`check` includes the API/CLI binary/sources/Javadoc/POM/Gradle-metadata checks, desktop/API ownership checks and ordinary tests. `apiConsumerTest` runs independent headless consumers twice: once with Gradle metadata and once using only Maven POM metadata. The API is published unsigned to `build/repos/consumer-test`. With local composites enabled, the test also stages local library binaries and their generated POMs there, without changing sibling publications or Maven Local. Consumers disable project substitution and check preparation, detection, Auto-Tune, reporting/assets, CLI execution, dependency scopes and absence of GUI/FlatLaf. This proves packaging of the tested source pair. When a sibling flag is disabled, its group is excluded from local staging so stale source binaries cannot replace the published dependency.

**Maven dependencies:** declare the required engine and solver versions directly in the `dependencies` block of `build.gradle`, alongside the other dependencies. For Maven Central publication, publish those library versions first and build with `-PuseLocalJTransientBuild=false -PuseLocalJPlateSolveBuild=false`. Gradle resolves the declared dependencies normally. `validateCentralReleaseConfiguration` requires the composite builds to be disabled and checks signing/developer configuration; `apiConsumerTest` checks that the packaged API works for another Java application. There is no separate list of expected library versions or release-staging validation workflow.

Build the single headless API/CLI bundle:

```bash
./gradlew -PuseLocalJTransientBuild=false -PuseLocalJPlateSolveBuild=false mavenCentralBundle
```

On Windows:

```powershell
.\gradlew.bat -PuseLocalJTransientBuild=false -PuseLocalJPlateSolveBuild=false mavenCentralBundle
```

This will:

- run `releaseCheck`
- stage the signed API/CLI publication into `build/repos/central-staging`
- create a ZIP containing only the API's current-version binary/sources/Javadoc, POM/module metadata, signatures and checksums

Generated bundle:

```text
build/distributions/spacepixels-<version>-central-bundle.zip
```

Publishing/signing and developer-metadata configuration live in the root `build.gradle`. The bundle selects only the current API publication's expected filenames, excluding stale versions, old `spacepixels-core` artifacts and any desktop-classifier files left in staging by earlier builds. `bundleCentralPortal` is the underlying task; `mavenCentralBundle` remains the single standard command. Signing and developer metadata use the same existing local properties/environment variables. Maven Local publication also publishes only the API/CLI artifact.

For the desktop application, use `gradlew.bat installDist` (or `./gradlew installDist`), not a Central publication command. Distribute the complete installed application or desktop ZIP/TAR rather than the API JAR alone.

## Upload to the Central Portal

For Maven publication, the declared library dependencies must be available to external consumers through Maven Central. Local API consumer checks can also use source-built library JARs during development; those checks establish packaging correctness, independently of public availability.

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
