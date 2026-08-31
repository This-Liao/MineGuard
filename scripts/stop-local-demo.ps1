param([Parameter(Mandatory)][string]$RunPath)
$ErrorActionPreference = 'Stop'
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$AllowedRoot = [System.IO.Path]::GetFullPath((Join-Path $ProjectRoot 'data/runtime/local-demo')) + [System.IO.Path]::DirectorySeparatorChar
$ResolvedRun = (Resolve-Path -LiteralPath $RunPath).Path
if (!$ResolvedRun.StartsWith($AllowedRoot, [StringComparison]::OrdinalIgnoreCase)) { throw '只能停止本项目启动脚本记录的服务。' }
$state = Get-Content -LiteralPath (Join-Path $ResolvedRun 'processes.json') -Raw | ConvertFrom-Json
foreach ($entry in $state.processes) {
    $process = Get-Process -Id $entry.id -ErrorAction SilentlyContinue
    if (!$process) { continue }
    if (!$process.StartTime -or $process.StartTime.ToUniversalTime().Ticks -ne ([DateTime]$entry.startTime).ToUniversalTime().Ticks) { throw '进程编号已被复用或无法核验启动时间，拒绝停止。' }
    $process.Kill()
}
Write-Host '本次演示服务已停止，数据库、账号文件与日志保留。任务将在同一数据库上的下一实例启动后按租约恢复。'
