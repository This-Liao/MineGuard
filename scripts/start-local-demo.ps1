param([switch]$UseDeepSeek, [switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$RunId = Get-Date -Format 'yyyyMMdd-HHmmss'
$RunPath = Join-Path $ProjectRoot "data/runtime/local-demo/$RunId"
$Ports = @(8080, 18081, 5173)
foreach ($port in $Ports) {
    if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) { throw "端口 $port 已被占用，未启动或终止任何服务。" }
}
Push-Location $ProjectRoot
$names = @('MINEGUARD_BOOTSTRAP_USERNAME','MINEGUARD_BOOTSTRAP_PASSWORD','MINEGUARD_INDUSTRIAL_TOKEN','OPENAI_API_KEY')
$previous = @{}
foreach ($name in $names) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
$started = @()
try {
    if (!$SkipBuild) {
        mvn -q -DskipTests package
        if ($LASTEXITCODE -ne 0) { throw '后端构建失败。' }
        Push-Location frontend
        try { npm run build; if ($LASTEXITCODE -ne 0) { throw '前端构建失败，请先 npm ci。' } } finally { Pop-Location }
    }
    New-Item -ItemType Directory -Path $RunPath -Force | Out-Null
    # 每次启动使用独立的文件数据库和随机账号，不覆盖已有演示或业务数据。
    $passwords = @{admin = [Guid]::NewGuid().ToString('N'); operator = [Guid]::NewGuid().ToString('N'); approver = [Guid]::NewGuid().ToString('N')}
    $env:MINEGUARD_BOOTSTRAP_USERNAME = 'demo-admin'
    $env:MINEGUARD_BOOTSTRAP_PASSWORD = $passwords.admin
    $env:MINEGUARD_INDUSTRIAL_TOKEN = [Guid]::NewGuid().ToString('N')
    $jar = Join-Path $ProjectRoot 'target/mineguard-1.0.0-SNAPSHOT.jar'
    $java = (Get-Command java).Source
    $contract = Start-Process $java -WindowStyle Hidden -PassThru -WorkingDirectory $RunPath -ArgumentList @('-Dloader.main=com.mineguard.contract.IndustrialContractServer','-cp', ('"' + $jar + '"'),'org.springframework.boot.loader.launch.PropertiesLauncher','18081') -RedirectStandardOutput (Join-Path $RunPath 'industrial.log') -RedirectStandardError (Join-Path $RunPath 'industrial-error.log')
    $started += $contract
    $modelArgs = '--mineguard.llm.provider=deterministic'
    if ($UseDeepSeek) {
        $env:OPENAI_API_KEY = (Get-Content -LiteralPath (Join-Path $ProjectRoot 'key.txt') -Raw -Encoding UTF8).Trim()
        if (!$env:OPENAI_API_KEY -or $env:OPENAI_API_KEY -match '\s') { throw 'key.txt 必须仅包含密钥一行。' }
        $modelArgs = '--mineguard.llm.provider=openai-compatible --mineguard.llm.base-url=https://api.deepseek.com --mineguard.llm.model=deepseek-v4-flash --mineguard.llm.max-calls=1000 --mineguard.llm.thinking=disabled'
    }
    $database = 'jdbc:h2:file:' + ($RunPath -replace '\\','/') + '/application;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE'
    $backendArgs = '-jar "' + $jar + '" --server.address=127.0.0.1 --server.port=8080 --spring.datasource.url="' + $database + '" --spring.datasource.driver-class-name=org.h2.Driver --spring.datasource.username=sa --spring.datasource.password= --mineguard.vector-store.type=in-memory --mineguard.demo-data-enabled=true --mineguard.runtime.scheduler-enabled=true --mineguard.industrial.type=http-contract --mineguard.industrial.base-url=http://127.0.0.1:18081 --mineguard.trace-path="' + (Join-Path $RunPath 'traces') + '" ' + $modelArgs
    $backend = Start-Process $java -WindowStyle Hidden -PassThru -WorkingDirectory $ProjectRoot -ArgumentList $backendArgs -RedirectStandardOutput (Join-Path $RunPath 'backend.log') -RedirectStandardError (Join-Path $RunPath 'backend-error.log')
    $started += $backend
    $ready = $false
    for ($attempt = 0; $attempt -lt 90; $attempt++) {
        if ($backend.HasExited -or $contract.HasExited) { throw '服务启动失败，请检查本次目录的日志。' }
        try {
            Invoke-RestMethod 'http://127.0.0.1:8080/api/health' -TimeoutSec 2 | Out-Null
            $login = Invoke-RestMethod 'http://127.0.0.1:8080/api/auth/login' -Method Post -ContentType 'application/json' -Body (@{username='demo-admin';password=$passwords.admin} | ConvertTo-Json) -TimeoutSec 2
            $ready = $true; break
        } catch { Start-Sleep -Milliseconds 500 }
    }
    if (!$ready) { throw '服务就绪超时。' }
    $headers = @{Authorization = 'Bearer ' + $login.accessToken}
    foreach ($role in @('operator','approver')) {
        Invoke-RestMethod 'http://127.0.0.1:8080/api/admin/users' -Method Post -Headers $headers -ContentType 'application/json' -Body (@{username="demo-$role";password=$passwords[$role];roles=@($role.ToUpperInvariant())} | ConvertTo-Json) | Out-Null
    }
    Invoke-RestMethod 'http://127.0.0.1:8080/api/auth/logout' -Method Post -Headers $headers | Out-Null
    $credentialPath = Join-Path $RunPath 'accounts.txt'
    $accountText = "仅供本机演示，请勿分享或提交。两个浏览器页面可分别登录操作员和审批员。`r`n" + (($passwords.Keys | Sort-Object | ForEach-Object { "demo-$_ : " + $passwords[$_] }) -join "`r`n")
    [System.IO.File]::WriteAllText($credentialPath, $accountText, [System.Text.UTF8Encoding]::new($false))
    # 账号文件只授权当前 Windows 用户；Git 忽略不是加密。
    $acl = Get-Acl -LiteralPath $credentialPath
    $acl.SetAccessRuleProtection($true, $false)
    $identity = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
    $acl.SetAccessRule([System.Security.AccessControl.FileSystemAccessRule]::new($identity,'FullControl','Allow'))
    Set-Acl -LiteralPath $credentialPath -AclObject $acl
    $node = (Get-Command node).Source
    $vite = Join-Path $ProjectRoot 'frontend/node_modules/vite/bin/vite.js'
    $frontend = Start-Process $node -WindowStyle Hidden -PassThru -WorkingDirectory (Join-Path $ProjectRoot 'frontend') -ArgumentList @(('"' + $vite + '"'),'--host','127.0.0.1','--port','5173','--strictPort') -RedirectStandardOutput (Join-Path $RunPath 'frontend.log') -RedirectStandardError (Join-Path $RunPath 'frontend-error.log')
    $started += $frontend
    $state = @{createdAt=(Get-Date).ToString('o');runPath=$RunPath;processes=@($started | ForEach-Object { @{id=$_.Id;startTime=$_.StartTime.ToUniversalTime().ToString('o')} })}
    [System.IO.File]::WriteAllText((Join-Path $RunPath 'processes.json'), ($state | ConvertTo-Json -Depth 5))
    Write-Host "前端：http://127.0.0.1:5173；后端：http://127.0.0.1:8080；本地工业契约：http://127.0.0.1:18081"
    Write-Host "账号文件（未回显密码）：$credentialPath"
    Write-Host "停止本次服务：.\scripts\stop-local-demo.ps1 -RunPath '$RunPath'"
    if ($UseDeepSeek) { Write-Host '本次页面任务使用真实 DeepSeek；单次服务进程保护上限 1000 次。工业目标仍为本地契约环境。' }
} catch {
    # 仅终止本次脚本刚创建且仍存活的进程，不按端口查杀用户程序。
    foreach ($process in $started) { if (!$process.HasExited) { $process.Kill() } }
    throw
} finally {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
    $passwords = $null; $login = $null; $headers = $null; $accountText = $null
    Pop-Location
}
