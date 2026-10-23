# Selected release artifact validation

Develop SpacePixels, JTransient and JPlateSolve together using both local composite builds. Before release, select and pin one exact version combination; old/new-engine interchangeability is not supported.

## Local packaged check

Configure Java 21 and the local tool paths, then run:

```powershell
.\scripts\Verify-ReleaseArtifacts.ps1
```

The script checks the current engine, stages unsigned current engine/solver Maven artifacts under `build/repos/release-validation`, and runs SpacePixels `check` against those exact versions with both composites disabled. It verifies **both** resolved library versions, API/desktop resources and seven isolated headless consumers, then repeats the consumer tests using POM-only metadata. It never publishes to Central, changes Git, modifies preferences or changes ignored composite flags. Optional `-EngineDirectory`/`-PlateSolveDirectory` select the source checkouts to validate; no historical checkout is needed.

For an existing staged repository:

```powershell
.\gradlew.bat -PuseLocalJTransientBuild=false -PuseLocalJPlateSolveBuild=false `
  '-PjTransientVersion=1.0.6' '-PjPlateSolveVersion=1.0.1' `
  '-PreleaseValidationRepository=C:/path/to/staged-repository' check
```

The versions above are development examples, not a release decision. Use `verifyReleaseArtifacts` explicitly for no-composite artifact resolution checks without running the full suite.

## Final release

Pin the selected versions in the release build. Verify actual availability through the intended distribution repository with both composites disabled **and without** `releaseValidationRepository`. Central staging rejects that local validation property. Local staging proves integration/packaging, not public availability.

JTransient `check` enforces its adopted bytecode architecture boundaries on Java 21; its `mavenCentralBundle` also compiles and runs the current-version independent `apiConsumer`. There is no previous-JAR binary canary or historical-version matrix. Keep scientific reference-data comparisons separate from packaging checks.
