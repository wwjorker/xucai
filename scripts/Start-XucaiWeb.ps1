param([string]$NodeExe='node', [ValidateSet('dev','preview')][string]$Mode='preview',
      [ValidateRange(1024,65535)][int]$Port=5173,
      [ValidateRange(1024,65535)][int]$ApiPort=10010)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$web=Join-Path $root 'web'
$entry=Join-Path $web 'node_modules/vite/bin/vite.js'
$stateDir=Join-Path $web '.runtime'
$statePath=Join-Path $stateDir 'server.json'
if(-not (Test-Path -LiteralPath $entry)){throw '请先在web目录执行npm ci。'}
if($Mode -eq 'preview' -and -not (Test-Path -LiteralPath (Join-Path $web 'dist/index.html'))){throw '请先在web目录执行npm run build。'}
if($Port -eq $ApiPort){throw '前端端口不能与后端相同。'}
if(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue){throw "$Port 端口已占用，请先核对现有前端。"}
if(Test-Path -LiteralPath $statePath){
    $old=Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
    if(Get-Process -Id $old.Pid -ErrorAction SilentlyContinue){throw '记录的前端进程仍存在，请先核对。'}
}
$logDir=Join-Path $root 'logs/xucai'
New-Item -ItemType Directory -Force $stateDir,$logDir | Out-Null
$arguments=@(('"'+$entry+'"'))
if($Mode -eq 'preview'){$arguments+='preview'}
$arguments+=@('--host','127.0.0.1','--port',[string]$Port,'--strictPort')
$previousApiOrigin=$env:XUCAI_API_ORIGIN
try {
$env:XUCAI_API_ORIGIN="http://127.0.0.1:$ApiPort"
$process=Start-Process -FilePath $NodeExe -ArgumentList $arguments -WorkingDirectory $web -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $logDir 'web.out.log') -RedirectStandardError (Join-Path $logDir 'web.err.log')
} finally { $env:XUCAI_API_ORIGIN=$previousApiOrigin }
@{Pid=$process.Id;Entry=$entry;Mode=$Mode;Port=$Port;ApiPort=$ApiPort;Url="http://127.0.0.1:$Port";StartedAt=(Get-Date).ToString('o')} | ConvertTo-Json | Set-Content -LiteralPath $statePath -Encoding utf8
Write-Output "序采前端：PID=$($process.Id)，模式=$Mode，http://127.0.0.1:$Port，后端端口$ApiPort（进程已创建，仍需访问验证）"
