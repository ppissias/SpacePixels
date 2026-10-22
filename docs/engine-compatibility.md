# Engine compatibility and Java baseline

SpacePixels and JTransient target **Java 21**. JTransient 1.0.6 adds immutable tuner options, an independent available-sample entry point and shared execution policies/sessions. SpacePixels still defaults to 1.0.5 and discovers the new tuner entry point at one optional-capability boundary. It keeps the old serialized legacy-tuning adapter when the capability is absent. The calibrated tuner, rules, cancellation and existing application behavior remain available with the previous engine source baseline.

## Repeatable staged artifact matrix

Configure Java 21 and the machine's tool paths first (consult local `AGENTS.md`/`setpaths.bat`). Supply a separate checkout or source export of the previous JTransient version; do not change the active sibling checkout or the maintainer's in-progress work. From the SpacePixels repository on Windows:

```powershell
.\scripts\Verify-EngineCompatibility.ps1 -PreviousEngineDirectory C:\path\to\previous-JTransient
```

The script:
- Builds unsigned **local-only** Maven validation artifacts for previous/current JTransient and the sibling JPlateSolve.
- Runs JTransient `check jar javadoc`, compiling its legacy API consumer against the previous JAR and running it on the current engine.
- Runs the full SpacePixels checks against each explicit engine version with **both composites disabled**, including API/desktop publication checks and seven independent headless artifact-consumer cases.
- Repeats the independent consumer cases using POM-only metadata as well as the default Gradle metadata.

The script performs no Git operations, installs no software, changes no ignored development flags/preferences, and never uploads artifacts or invokes Central staging. Its default local repository is `build/repos/engine-compatibility`. Existing artifacts there may be rebuilt; observing data is not used by this packaging check. The baseline version must differ from the current version. Both libraries' configured signing hooks are skipped because this is not Central staging.

Individual SpacePixels checks can use the same staged repository:

```powershell
.\gradlew.bat -PuseLocalJTransientBuild=false -PuseLocalJPlateSolveBuild=false `
  '-PjTransientVersion=1.0.6' '-PengineCompatibilityRepository=C:/path/to/staged-repository' check
```

`verifyEngineArtifact` asserts the actual resolved version is a packaged dependency, not a sibling/composite project. The isolated headless consumer also verifies that exact version. These are validation-only repository settings; `validateCentralReleaseConfiguration` rejects them. Normal development keeps both ignored composite flags `true`.

## Release limitations

Validation on 2026-10-22 uses previous JTransient source commit `4926ad9` (unreleased 1.0.5) and current 1.0.6, SpacePixels 2026.10-04 and JPlateSolve 1.0.1. Both engine pairs pass the 433-case application suite and seven independent consumer cases in each metadata mode; the old engine intentionally skips the new-capability-only case in addition to four opt-in integrations. The engine passes 248 unit tests plus its old-JAR/new-runtime consumer and architecture checks. The compatibility-repository Central guard fails as intended. These source-built results do not establish public release availability.

As checked on 2026-10-22, Maven Central does **not** resolve `io.github.ppissias.jtransient:jtransient:1.0.5`. Staging a source export of the previous engine proves compatibility with those source-built bytes, not with a publicly distributed artifact. No Maven Central upload is authorized or performed by this work. Before releasing SpacePixels, publish/verify the intended engine artifact and run the no-composite release checks against that actual repository without `engineCompatibilityRepository`. Update the default dependency only as part of that explicit release decision.

The engine's `legacyApiCompatibility` canary covers representative public entry points, nested models, constructors, identity, stack encoding, fallback alias compatibility and sequential reuse; it is not an exhaustive binary API-diff tool. `verifyArchitecture` uses Java 21 `jdeps` on compiled classes, enforcing only the adopted `config` and `execution` project boundaries. Existing science-package cycles and later structural seams remain tracked in the design plan.
