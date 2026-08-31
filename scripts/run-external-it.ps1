$ErrorActionPreference = 'Stop'
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$previousFlag = $env:MINEGUARD_RUN_EXTERNAL_IT
Push-Location $ProjectRoot
try {
    # 独立项目、独立数据卷和回环端口；不会启动或清理其他 Docker 项目。
    docker compose -f infra/compose.integration.yml up -d --wait --wait-timeout 180
    if ($LASTEXITCODE -ne 0) { throw '隔离数据库环境未就绪，尚未执行集成测试。' }
    $env:MINEGUARD_RUN_EXTERNAL_IT = 'true'
    mvn -q -Pexternal-it clean verify
    if ($LASTEXITCODE -ne 0) { throw '构建或外部服务验收失败，请检查 target/failsafe-reports。' }
    Write-Host '外部服务验收完成：target/failsafe-reports；覆盖率：target/site/jacoco/index.html。'
    Write-Host '隔离容器保留运行。停止命令：docker compose -f infra/compose.integration.yml stop'
} finally {
    $env:MINEGUARD_RUN_EXTERNAL_IT = $previousFlag
    Pop-Location
}
