$ErrorActionPreference = 'Stop'
$taskProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location $taskProjectRoot
try {
    # 不读取 DeepSeek 密钥，不启动或修改当前业务服务。
    mvn -q -DskipTests compile exec:java '-Dexec.mainClass=com.mineguard.eval.SemanticRetrievalEvalApplication'
    if ($LASTEXITCODE -ne 0) { throw '检索评测未完成，请检查冻结清单、独立报告与本机 18082 向量服务。' }
} finally { Pop-Location }
