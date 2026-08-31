param(
    [int]$MaxCalls = 0,
    [ValidateRange(1, 30)][int]$AgentCases = 3,
    [ValidateRange(0, 20)][int]$SafetyCases = 0,
    [ValidateRange(0, 12)][int]$SupplementalCases = 0,
    [ValidateRange(128, 16384)][int]$MaxOutputTokens = 2048,
    [ValidateSet('deepseek-v4-flash', 'deepseek-v4-pro', 'deepseek-v4-flash-vision-exp')]
    [string]$Model = 'deepseek-v4-flash',
    [string]$KeyFile = '',
    [switch]$Holdout
)
$ErrorActionPreference = 'Stop'
if ($MaxCalls -le 0) { throw '尚未授权付费调用。请明确传入本次允许的 -MaxCalls；此参数不是费用金额上限。' }
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if ([string]::IsNullOrWhiteSpace($KeyFile)) { $KeyFile = Join-Path $ProjectRoot 'key.txt' }
$names = @('OPENAI_API_KEY', 'OPENAI_BASE_URL', 'OPENAI_MODEL', 'MINEGUARD_LLM_MAX_CALLS',
    'MINEGUARD_LLM_MAX_OUTPUT_TOKENS', 'MINEGUARD_LLM_TIMEOUT_SECONDS', 'MINEGUARD_LLM_THINKING',
    'MINEGUARD_EVAL_AGENT_CASES', 'MINEGUARD_EVAL_SAFETY_CASES', 'MINEGUARD_EVAL_SUPPLEMENTAL_CASES', 'MINEGUARD_EVAL_SUITE')
$previous = @{}
foreach ($name in $names) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
Push-Location $ProjectRoot
try {
    # 先编译再读取密钥；不通过命令行参数、文件模板或日志传递凭据。
    mvn -q -DskipTests compile
    if ($LASTEXITCODE -ne 0) { throw '编译失败，未调用真实模型。' }
    $evaluationKey = (Get-Content -LiteralPath $KeyFile -Raw -Encoding UTF8).Trim()
    if ([string]::IsNullOrWhiteSpace($evaluationKey) -or $evaluationKey -match '\s') {
        throw '密钥文件必须仅包含一行有效密钥，不要附加说明。'
    }
    $env:OPENAI_API_KEY = $evaluationKey
    $env:OPENAI_BASE_URL = 'https://api.deepseek.com'
    $env:OPENAI_MODEL = $Model
    $env:MINEGUARD_LLM_MAX_CALLS = "$MaxCalls"
    $env:MINEGUARD_LLM_MAX_OUTPUT_TOKENS = "$MaxOutputTokens"
    $env:MINEGUARD_LLM_TIMEOUT_SECONDS = '60'
    $env:MINEGUARD_LLM_THINKING = 'disabled'
    $env:MINEGUARD_EVAL_AGENT_CASES = "$AgentCases"
    $env:MINEGUARD_EVAL_SAFETY_CASES = "$SafetyCases"
    $env:MINEGUARD_EVAL_SUPPLEMENTAL_CASES = "$SupplementalCases"
    $env:MINEGUARD_EVAL_SUITE = if ($Holdout) { 'holdout-v1' } else { 'regression' }
    Write-Host "开始受控评测：$Model，最多 $MaxCalls 次请求；工具只操作本地模拟环境。"
    mvn -q -DskipTests exec:java '-Dexec.mainClass=com.mineguard.eval.RealModelEvalApplication'
    if ($LASTEXITCODE -ne 0) { throw '真实模型评测未完成，请检查独立报告。不要自动重跑，以免重复计费。' }
} finally {
    $evaluationKey = $null
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
    Pop-Location
}
