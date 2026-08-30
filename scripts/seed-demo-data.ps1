$ErrorActionPreference = 'Stop'
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location $ProjectRoot
try {
    mvn -q -DskipTests compile exec:java "-Dexec.mainClass=com.mineguard.config.SeedApplication"
    if ($LASTEXITCODE -ne 0) { throw "Seed failed with exit code $LASTEXITCODE" }
} finally {
    Pop-Location
}
