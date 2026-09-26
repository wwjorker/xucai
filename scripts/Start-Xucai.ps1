param(
    [string]$JavaHome = $env:JAVA_HOME,
    [ValidateSet('all','user','auth','gateway','catalog','trade','ai')]
    [string]$Application = 'all',
    [ValidateSet('portable')][string]$Mode = 'portable',
    [ValidateRange(0,40000)][int]$PortOffset = 0,
    [ValidateSet(256,512)][int]$MaxHeapMb = 512
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
if ($PortOffset -ne 0 -and $Mode -ne 'portable') { throw '偏移端口仅用于独立portable环境。' }
if (-not (Test-Path -LiteralPath "$JavaHome/bin/java.exe")) { throw '请通过 JAVA_HOME 或 -JavaHome 指定 JDK 17。' }
foreach ($name in @('XUCAI_DB_PASSWORD','XUCAI_REDIS_PASSWORD')) {
    if (-not [Environment]::GetEnvironmentVariable($name)) { throw "缺少本地环境变量 $name" }
}
if ($Mode -eq 'portable') {
    foreach ($name in @('XUCAI_DB_HOST','XUCAI_REDIS_HOST','XUCAI_NACOS_SERVER','XUCAI_DISCOVERY_IP','XUCAI_JWT_LOCATION','XUCAI_JWT_PASSWORD','XUCAI_JWT_ALIAS')) {
        if (-not [Environment]::GetEnvironmentVariable($name)) { throw "portable缺少环境变量 $name" }
    }
}
$apps = @(
    @{Name='user'; Jar='xucai-user/target/xucai-user.jar'; Port=8082},
    @{Name='auth'; Jar='xucai-auth/xucai-auth-service/target/xucai-auth-service.jar'; Port=8081},
    @{Name='gateway'; Jar='xucai-gateway/target/xucai-gateway.jar'; Port=10010},
    @{Name='catalog'; Jar='xucai-catalog/target/xucai-catalog.jar'; Port=8086},
    @{Name='trade'; Jar='xucai-trade/target/xucai-trade.jar'; Port=8088},
    @{Name='ai'; Jar='xucai-ai/target/xucai-ai.jar'; Port=8094}
)
$selected = @($apps | Where-Object { $Application -eq 'all' -or $_.Name -eq $Application })
foreach ($app in $selected) { $app.Port += $PortOffset }
foreach ($app in $selected) {
    if (-not (Test-Path -LiteralPath (Join-Path $root $app.Jar))) { throw "尚未打包 $($app.Name)" }
    if (Get-NetTCPConnection -State Listen -LocalPort $app.Port -ErrorAction SilentlyContinue) { throw "端口 $($app.Port) 已占用；先核对现有进程。" }
}
$stateDir = Join-Path $root '.runtime'
$logDir = Join-Path $root 'logs/xucai'
New-Item -ItemType Directory -Path $stateDir,$logDir -Force | Out-Null
foreach ($app in $selected) {
    $jarPath = Join-Path $root $app.Jar
    $statePath = Join-Path $stateDir "$($app.Name).json"
    if (Test-Path -LiteralPath $statePath) {
        $old = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
        if (Get-Process -Id $old.Pid -ErrorAction SilentlyContinue) { throw "$($app.Name) 的旧进程仍在；请先核对。" }
    }
    $javaArguments = @('-Xms64m',"-Xmx${MaxHeapMb}m",'-XX:ActiveProcessorCount=2','-Dfile.encoding=UTF-8')
    # 本机TUN虚拟IPv4地址在Java双栈连接下可能超时；只对明确启用此选项的采购AI生效。
    if ($app.Name -eq 'ai' -and $env:XUCAI_AI_PREFER_IPV4 -eq 'true') { $javaArguments += '-Djava.net.preferIPv4Stack=true' }
    $javaArguments += @('-jar',('"'+$jarPath+'"'),"--spring.profiles.active=$Mode,xucai","--server.port=$($app.Port)")
    $process = Start-Process -FilePath "$JavaHome/bin/java.exe" -WindowStyle Hidden -PassThru -WorkingDirectory $root `
        -ArgumentList $javaArguments `
        -RedirectStandardOutput (Join-Path $logDir "$($app.Name).out.log") `
        -RedirectStandardError (Join-Path $logDir "$($app.Name).err.log")
    @{Name=$app.Name; Pid=$process.Id; Port=$app.Port; Jar=$jarPath; StartedAt=(Get-Date).ToString('o'); Mode=$Mode} |
        ConvertTo-Json | Set-Content -LiteralPath $statePath -Encoding utf8
    Write-Output "$($app.Name): PID=$($process.Id), port=$($app.Port) (进程已创建，尚不代表启动成功)"
}
