param(
    [ValidateRange(1, 2)][int]$MaxCalls = 2,
    [ValidateSet('deepseek-v4-flash', 'deepseek-v4-pro', 'deepseek-v4-flash-vision-exp')]
    [string]$Model = 'deepseek-v4-flash',
    [string]$KeyFile = ''
)
$ErrorActionPreference = 'Stop'
$taskProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if ([string]::IsNullOrWhiteSpace($KeyFile)) { $KeyFile = Join-Path $taskProjectRoot 'key.txt' }
$taskNames = @('OPENAI_API_KEY', 'OPENAI_BASE_URL', 'OPENAI_MODEL', 'MINEGUARD_LLM_MAX_CALLS')
$taskPrevious = @{}
foreach ($taskName in $taskNames) { $taskPrevious[$taskName] = [Environment]::GetEnvironmentVariable($taskName, 'Process') }

Push-Location $taskProjectRoot
try {
    mvn -q -DskipTests compile
    if ($LASTEXITCODE -ne 0) { throw '编译失败，未调用真实模型。' }
    $taskKey = (Get-Content -LiteralPath $KeyFile -Raw -Encoding UTF8).Trim()
    if ([string]::IsNullOrWhiteSpace($taskKey) -or $taskKey -match '\s') {
        throw '密钥文件必须仅包含一行有效密钥。'
    }
    $env:OPENAI_API_KEY = $taskKey
    $env:OPENAI_BASE_URL = 'https://api.deepseek.com'
    $env:OPENAI_MODEL = $Model
    $env:MINEGUARD_LLM_MAX_CALLS = "$MaxCalls"
    Write-Host "开始 LangChain4j AI Services 只读验收：$Model，最多 $MaxCalls 次请求。"
    mvn -q -DskipTests exec:java '-Dexec.mainClass=com.mineguard.eval.LangChain4jSmokeApplication'
    if ($LASTEXITCODE -ne 0) { throw 'LangChain4j 真实调用未完成；失败请求可能已计费，请勿自动重复运行。' }
} finally {
    $taskKey = $null
    foreach ($taskName in $taskNames) { [Environment]::SetEnvironmentVariable($taskName, $taskPrevious[$taskName], 'Process') }
    Pop-Location
}
