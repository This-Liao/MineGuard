param([Parameter(Mandatory)][string]$RunPath, [switch]$UseDeepSeek, [switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$AllowedRoot = [System.IO.Path]::GetFullPath((Join-Path $ProjectRoot 'data/runtime/local-demo')) + [System.IO.Path]::DirectorySeparatorChar
$ResolvedRun = (Resolve-Path -LiteralPath $RunPath).Path
if (!$ResolvedRun.StartsWith($AllowedRoot, [StringComparison]::OrdinalIgnoreCase)) { throw '只能重启本项目记录的演示目录。' }
foreach ($file in @('processes.json', 'application.mv.db', 'accounts.txt')) {
    if (!(Test-Path -LiteralPath (Join-Path $ResolvedRun $file))) { throw "缺少 $file，不能作为已有环境恢复。" }
}
$state = Get-Content -LiteralPath (Join-Path $ResolvedRun 'processes.json') -Raw | ConvertFrom-Json
foreach ($port in @(8080, 18081, 5173)) {
    $listeners = Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue
    foreach ($listener in $listeners) {
        if ($listener.OwningProcess -notin $state.processes.id) { throw "端口 $port 被其他进程占用，不会终止该进程。" }
        $entry = $state.processes | Where-Object { $_.id -eq $listener.OwningProcess }
        $candidate = Get-Process -Id $listener.OwningProcess -ErrorAction SilentlyContinue
        if (!$candidate.StartTime -or $candidate.StartTime.ToUniversalTime().Ticks -ne ([DateTime]$entry.startTime).ToUniversalTime().Ticks) {
            throw "端口 $port 的进程身份不匹配，不会终止该进程。"
        }
    }
}
$RestartPath = Join-Path $ResolvedRun ('restart-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $RestartPath | Out-Null
Copy-Item -LiteralPath (Join-Path $ResolvedRun 'processes.json') -Destination (Join-Path $RestartPath 'previous-processes.json')
foreach ($entry in $state.processes) {
    $candidate = Get-Process -Id $entry.id -ErrorAction SilentlyContinue
    if (!$candidate) { continue }
    # Windows 可能复用已退出服务的编号；未占用服务端口的无关进程不影响恢复，也绝不终止。
    if (!$candidate.StartTime -or $candidate.StartTime.ToUniversalTime().Ticks -ne ([DateTime]$entry.startTime).ToUniversalTime().Ticks) { continue }
    $candidate.Kill()
    if (!$candidate.WaitForExit(10000)) { throw '原服务未能在十秒内退出，停止重启。' }
}
$started = @()
$names = @('OPENAI_API_KEY', 'MINEGUARD_INDUSTRIAL_TOKEN')
$previous = @{}
foreach ($name in $names) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
Push-Location $ProjectRoot
try {
    if (!$SkipBuild) {
        mvn -q -DskipTests package
        if ($LASTEXITCODE -ne 0) { throw '后端构建失败，原数据库和账号仍保留。' }
    }
    # 每次启动复制一份构建产物，运行中的 JVM 不再锁住 target，后续可正常 clean verify。
    $jar = Join-Path $RestartPath 'mineguard.jar'
    Copy-Item -LiteralPath (Join-Path $ProjectRoot 'target/mineguard-1.0.0-SNAPSHOT.jar') -Destination $jar
    $java = (Get-Command java).Source
    # 只轮换后端与本地契约服务之间的随机令牌；不读取或修改用户密码。
    $env:MINEGUARD_INDUSTRIAL_TOKEN = [Guid]::NewGuid().ToString('N')
    $contract = Start-Process $java -WindowStyle Hidden -PassThru -WorkingDirectory $ResolvedRun -ArgumentList @('-Dloader.main=com.mineguard.contract.IndustrialContractServer','-cp',('"' + $jar + '"'),'org.springframework.boot.loader.launch.PropertiesLauncher','18081') -RedirectStandardOutput (Join-Path $RestartPath 'industrial.log') -RedirectStandardError (Join-Path $RestartPath 'industrial-error.log')
    $started += $contract
    $modelArgs = '--mineguard.llm.provider=deterministic'
    if ($UseDeepSeek) {
        $env:OPENAI_API_KEY = (Get-Content -LiteralPath (Join-Path $ProjectRoot 'key.txt') -Raw -Encoding UTF8).Trim()
        if (!$env:OPENAI_API_KEY -or $env:OPENAI_API_KEY -match '\s') { throw 'key.txt 必须仅包含一行有效密钥。' }
        $modelArgs = '--mineguard.llm.provider=openai-compatible --mineguard.llm.base-url=https://api.deepseek.com --mineguard.llm.model=deepseek-v4-flash --mineguard.llm.max-calls=1000 --mineguard.llm.max-output-tokens=2048 --mineguard.llm.request-timeout-seconds=60 --mineguard.llm.thinking=disabled'
    }
    $database = 'jdbc:h2:file:' + ($ResolvedRun -replace '\\','/') + '/application;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE'
    $backendArgs = '-jar "' + $jar + '" --server.address=127.0.0.1 --server.port=8080 --spring.datasource.url="' + $database + '" --spring.datasource.driver-class-name=org.h2.Driver --spring.datasource.username=sa --spring.datasource.password= --mineguard.runtime.bootstrap-username= --mineguard.runtime.bootstrap-password= --mineguard.vector-store.type=in-memory --mineguard.demo-data-enabled=true --mineguard.runtime.scheduler-enabled=true --mineguard.industrial.type=http-contract --mineguard.industrial.base-url=http://127.0.0.1:18081 --mineguard.trace-path="' + (Join-Path $ResolvedRun 'traces') + '" ' + $modelArgs
    $backend = Start-Process $java -WindowStyle Hidden -PassThru -WorkingDirectory $ProjectRoot -ArgumentList $backendArgs -RedirectStandardOutput (Join-Path $RestartPath 'backend.log') -RedirectStandardError (Join-Path $RestartPath 'backend-error.log')
    $started += $backend
    $ready = $false
    for ($attempt = 0; $attempt -lt 90; $attempt++) {
        if ($backend.HasExited -or $contract.HasExited) { throw '服务启动失败，请检查本次重启日志。' }
        try {
            Invoke-RestMethod 'http://127.0.0.1:8080/api/health' -TimeoutSec 2 | Out-Null
            Invoke-RestMethod 'http://127.0.0.1:18081/health' -TimeoutSec 2 | Out-Null
            $ready = $true; break
        } catch { Start-Sleep -Milliseconds 500 }
    }
    if (!$ready) { throw '服务就绪超时。' }
    $node = (Get-Command node).Source
    $vite = Join-Path $ProjectRoot 'frontend/node_modules/vite/bin/vite.js'
    $frontend = Start-Process $node -WindowStyle Hidden -PassThru -WorkingDirectory (Join-Path $ProjectRoot 'frontend') -ArgumentList @(('"' + $vite + '"'),'--host','127.0.0.1','--port','5173','--strictPort') -RedirectStandardOutput (Join-Path $RestartPath 'frontend.log') -RedirectStandardError (Join-Path $RestartPath 'frontend-error.log')
    $started += $frontend
    $state = @{createdAt=(Get-Date).ToString('o');runPath=$ResolvedRun;restartPath=$RestartPath;processes=@($started | ForEach-Object { @{id=$_.Id;startTime=$_.StartTime.ToUniversalTime().ToString('o')} })}
    [System.IO.File]::WriteAllText((Join-Path $ResolvedRun 'processes.json'), ($state | ConvertTo-Json -Depth 5))
    Write-Host '已有环境已恢复：http://127.0.0.1:5173；数据库、任务、账号文件和历史日志保持不变。'
    Write-Host "本次日志：$RestartPath"
    if ($UseDeepSeek) { Write-Host '当前后端使用 DeepSeek；本进程最多 1000 次调用，工业侧为本地契约服务。' }
} catch {
    foreach ($process in $started) { if (!$process.HasExited) { $process.Kill() } }
    throw
} finally {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
    Pop-Location
}
