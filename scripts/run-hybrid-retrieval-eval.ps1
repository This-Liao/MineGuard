$ErrorActionPreference = 'Stop'
$taskProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$taskPython = Join-Path $taskProjectRoot 'data/runtime/semantic-embedding/venv/Scripts/python.exe'
$taskServer = Join-Path $taskProjectRoot 'scripts/embedding/server.py'
$taskModel = Join-Path $taskProjectRoot 'data/runtime/semantic-embedding/model'
$taskStartedEmbedding = $null

Push-Location $taskProjectRoot
try {
    # 使用隔离的 Milvus 集成环境；评测器还会为本轮创建并清理随机集合。
    docker compose -f infra/compose.integration.yml up -d --wait --wait-timeout 180
    if ($LASTEXITCODE -ne 0) { throw 'Milvus 集成环境未就绪。' }

    try { $taskHealth = Invoke-RestMethod 'http://127.0.0.1:18082/health' -TimeoutSec 3 } catch { $taskHealth = $null }
    if ($null -eq $taskHealth -or $taskHealth.status -ne 'UP') {
        if (-not (Test-Path -LiteralPath $taskPython)) { throw '未找到已安装依赖的 BGE Python 环境。' }
        $taskStartedEmbedding = Start-Process -FilePath $taskPython -ArgumentList @($taskServer, '--model-dir', $taskModel) `
            -WorkingDirectory $taskProjectRoot -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput 'data/runtime/semantic-embedding/server.out.log' `
            -RedirectStandardError 'data/runtime/semantic-embedding/server.err.log'
        for ($taskAttempt = 0; $taskAttempt -lt 30; $taskAttempt++) {
            Start-Sleep -Seconds 2
            try { $taskHealth = Invoke-RestMethod 'http://127.0.0.1:18082/health' -TimeoutSec 3 } catch { $taskHealth = $null }
            if ($null -ne $taskHealth -and $taskHealth.status -eq 'UP') { break }
        }
    }
    if ($null -eq $taskHealth -or $taskHealth.status -ne 'UP') { throw 'BGE 服务未在 60 秒内就绪。' }

    mvn -q -DskipTests compile exec:java '-Dexec.mainClass=com.mineguard.eval.HybridRetrievalEvalApplication'
    if ($LASTEXITCODE -ne 0) { throw '混合检索评测未完成，请检查 data/runtime/hybrid-retrieval-eval。' }
} finally {
    if ($null -ne $taskStartedEmbedding -and -not $taskStartedEmbedding.HasExited) { Stop-Process -Id $taskStartedEmbedding.Id }
    Pop-Location
}
