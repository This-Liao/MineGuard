param(
    [ValidateSet('deepseek-v4-flash')][string]$Model = 'deepseek-v4-flash',
    [string]$KeyFile = ''
)
$ErrorActionPreference = 'Stop'
$taskProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if ([string]::IsNullOrWhiteSpace($KeyFile)) { $KeyFile = Join-Path $taskProjectRoot 'key.txt' }
$taskNames = @('OPENAI_API_KEY', 'OPENAI_BASE_URL', 'OPENAI_MODEL', 'MINEGUARD_LLM_MAX_CALLS',
    'MINEGUARD_LLM_MAX_OUTPUT_TOKENS', 'MINEGUARD_LLM_TIMEOUT_SECONDS', 'MINEGUARD_LLM_THINKING')
$taskPrevious = @{}
foreach ($taskName in $taskNames) { $taskPrevious[$taskName] = [Environment]::GetEnvironmentVariable($taskName, 'Process') }

Push-Location $taskProjectRoot
try {
    mvn -q -DskipTests compile
    if ($LASTEXITCODE -ne 0) { throw '编译失败，尚未调用真实模型。' }
    $taskKey = (Get-Content -LiteralPath $KeyFile -Raw -Encoding UTF8).Trim()
    if ([string]::IsNullOrWhiteSpace($taskKey) -or $taskKey -match '\s') {
        throw '密钥文件必须仅包含一行有效密钥。'
    }
    $env:OPENAI_API_KEY = $taskKey
    $env:OPENAI_BASE_URL = 'https://api.deepseek.com'
    $env:OPENAI_MODEL = $Model
    $env:MINEGUARD_LLM_MAX_CALLS = '48'
    $env:MINEGUARD_LLM_MAX_OUTPUT_TOKENS = '2048'
    $env:MINEGUARD_LLM_TIMEOUT_SECONDS = '60'
    $env:MINEGUARD_LLM_THINKING = 'disabled'
    Write-Host "开始 LangChain4j AI Services 真实专项回归 v2：24 条用例，$Model，最多 48 次请求。"
    mvn -q -DskipTests exec:java '-Dexec.mainClass=com.mineguard.eval.LangChain4jRegressionApplication'
    if ($LASTEXITCODE -ne 0) { throw '专项回归未完整结束；失败请求可能已计费，脚本不会自动重跑。' }
} finally {
    $taskKey = $null
    foreach ($taskName in $taskNames) { [Environment]::SetEnvironmentVariable($taskName, $taskPrevious[$taskName], 'Process') }
    Pop-Location
}
