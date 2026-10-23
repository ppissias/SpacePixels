[CmdletBinding()]
param(
    [string] $EngineDirectory = (Join-Path $PSScriptRoot '../../JTransient'),
    [string] $PlateSolveDirectory = (Join-Path $PSScriptRoot '../../JPlateSolve')
)

$ErrorActionPreference = 'Stop'
$workspace = Split-Path $PSScriptRoot -Parent
$repository = Join-Path $workspace 'build/repos/release-validation'
$stagingInit = Join-Path $workspace 'gradle/stage-release-artifacts.init.gradle'

function Get-LibraryVersion {
    param([string] $Directory)
    $match = [regex]::Match([IO.File]::ReadAllText((Join-Path $Directory 'build.gradle')), "(?m)^version\s*=\s*'([^']+)'")
    if (-not $match.Success) { throw "Cannot read literal project version from $Directory" }
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

$engine = (Resolve-Path -LiteralPath $EngineDirectory).Path
$solver = (Resolve-Path -LiteralPath $PlateSolveDirectory).Path
$engineVersion = Get-LibraryVersion $engine
$solverVersion = Get-LibraryVersion $solver

Invoke-ProjectGradle $engine @('-I', $stagingInit, "-PreleaseValidationRepository=$repository", 'check', 'jar', 'javadoc')
foreach ($library in @($engine, $solver)) {
    Invoke-ProjectGradle $library @('-I', $stagingInit, "-PreleaseValidationRepository=$repository",
        'publishMavenJavaPublicationToReleaseValidationRepository')
}

$selectedArguments = @('-PuseLocalJTransientBuild=false', '-PuseLocalJPlateSolveBuild=false',
    "-PjTransientVersion=$engineVersion", "-PjPlateSolveVersion=$solverVersion", "-PreleaseValidationRepository=$repository")
Invoke-ProjectGradle $workspace ($selectedArguments + @('check'))
Invoke-ProjectGradle $workspace ($selectedArguments + @('-PapiConsumerMetadata=pom', 'apiConsumerTest'))

Write-Output "Verified selected JTransient $engineVersion and JPlateSolve $solverVersion with this SpacePixels build."
Write-Output 'Only unsigned local validation artifacts were staged; no Central publication, Git or configuration changes were performed.'
