# Publishing SpacePixels

This document covers publishing the current single-module SpacePixels artifact to Maven Central.

## Maven Central publishing

SpacePixels is configured to generate a Maven Central-compatible upload bundle with:

- `javadoc.jar`
- `sources.jar`
- POM metadata
- PGP signatures
- checksum files

The build uses the same local staging and bundle flow as JPlateSolve. Instead of pushing directly to the Central Portal from Gradle, it stages a signed Maven repository under `build/repos/central-staging` and then zips that repository into a Central Portal upload bundle.

Important constraint:

- Bundle generation is intentionally blocked while local `lib/*.jar` dependencies still exist. Replace those local JARs with published Maven coordinates first, otherwise downstream API consumers would get an incomplete POM.

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
signingKey=-----BEGIN PGP PRIVATE KEY BLOCK-----
...
-----END PGP PRIVATE KEY BLOCK-----
signingPassword=YOUR_SIGNING_PASSWORD
```

You can also provide the in-memory signing values through environment variables:

```bash
export ORG_GRADLE_PROJECT_signingKey="..."
export ORG_GRADLE_PROJECT_signingPassword="..."
```

## Build the Central bundle

Run:

```bash
./gradlew clean mavenCentralBundle
```

On Windows:

```powershell
.\gradlew.bat clean mavenCentralBundle
```

This will:

- run the normal checks
- stage the signed Maven publication into `build/repos/central-staging`
- create the final upload bundle in `build/distributions`

Generated bundle:

```text
build/distributions/spacepixels-<version>-central-bundle.zip
```

## Upload to the Central Portal

Upload the generated ZIP bundle manually in the Maven Central Portal after verifying that your namespace is ready there.

The standardized Gradle entrypoints are:

- `validateCentralReleaseConfiguration`
- `bundleCentralPortal`
- `mavenCentralBundle`

The generated Central publication uses:

- coordinates: `eu.startales.spacepixels:spacepixels:<version>`
- sources JAR: enabled
- Javadoc JAR: enabled
- POM metadata: license, developer, and SCM info included
