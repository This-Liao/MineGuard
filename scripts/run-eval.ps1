$ErrorActionPreference = 'Stop'
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location $ProjectRoot
try {
    mvn clean verify
    if ($LASTEXITCODE -ne 0) { throw "Maven verification failed with exit code $LASTEXITCODE" }
    mvn -q -DskipTests exec:java "-Dexec.mainClass=com.mineguard.eval.EvalApplication"
    if ($LASTEXITCODE -ne 0) { throw "Evaluation failed with exit code $LASTEXITCODE" }
    Write-Host "Generated docs/eval/latest.json and docs/EVAL_REPORT.md"
} finally {
    Pop-Location
}
