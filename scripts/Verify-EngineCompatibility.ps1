[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $PreviousEngineDirectory,
    [string] $CurrentEngineDirectory = (Join-Path $PSScriptRoot '../../JTransient'),
    [string] $PlateSolveDirectory = (Join-Path $PSScriptRoot '../../JPlateSolve')
)

$ErrorActionPreference = 'Stop'
$workspace = Split-Path $PSScriptRoot -Parent
$repository = Join-Path $workspace 'build/repos/engine-compatibility'
$stagingInit = Join-Path $workspace 'gradle/stage-engine-compatibility.init.gradle'

function Get-LibraryVersion {
    param([string] $Directory)
    $buildFile = Join-Path $Directory 'build.gradle'
    $match = [regex]::Match([IO.File]::ReadAllText($buildFile), "(?m)^version\s*=\s*'([^']+)'")
    if (-not $match.Success) { throw "Cannot read literal project version from $buildFile" }
    return $match.Groups[1].Value
}

function Invoke-ProjectGradle {
    param([string] $Directory, [string[]] $GradleArguments)
    Push-Location -LiteralPath $Directory
    try {
        & (Join-Path $Directory 'gradlew.bat') @GradleArguments --console=plain
        if ($LASTEXITCODE -ne 0) { throw "Gradle failed in $Directory with exit code $LASTEXITCODE" }
    } finally {
        Pop-Location
    }
}

$previous = (Resolve-Path -LiteralPath $PreviousEngineDirectory).Path
$current = (Resolve-Path -LiteralPath $CurrentEngineDirectory).Path
$plateSolve = (Resolve-Path -LiteralPath $PlateSolveDirectory).Path
$previousVersion = Get-LibraryVersion $previous
$currentVersion = Get-LibraryVersion $current
if ($previousVersion -eq $currentVersion) { throw 'Baseline and current engine versions must be distinct.' }

foreach ($library in @($previous, $current, $plateSolve)) {
    Invoke-ProjectGradle $library @('-I', $stagingInit, "-PengineCompatibilityRepository=$repository",
        'publishMavenJavaPublicationToEngineCompatibilityRepository')
}

$baselineJar = Join-Path $repository "io/github/ppissias/jtransient/jtransient/$previousVersion/jtransient-$previousVersion.jar"
Invoke-ProjectGradle $current @('check', 'jar', 'javadoc', "-PcompatibilityBaselineJar=$baselineJar")

foreach ($version in @($previousVersion, $currentVersion)) {
    $pairArguments = @('-PuseLocalJTransientBuild=false', '-PuseLocalJPlateSolveBuild=false',
        "-PjTransientVersion=$version", "-PengineCompatibilityRepository=$repository")
    Invoke-ProjectGradle $workspace ($pairArguments + @('check'))
    Invoke-ProjectGradle $workspace ($pairArguments + @('-PapiConsumerMetadata=pom', 'apiConsumerTest'))
}

Write-Output "Verified Java 21 binary consumer and SpacePixels artifact pairs with JTransient $previousVersion and $currentVersion."
Write-Output 'Only local unsigned validation artifacts were staged; no Central publication or Git changes were performed.'
