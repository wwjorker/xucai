$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$statePath=Join-Path $root 'web/.runtime/server.json'
if(-not(Test-Path -LiteralPath $statePath)){Write-Output '没有前端进程记录。';return}
$state=Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
$process=Get-CimInstance Win32_Process -Filter "ProcessId=$($state.Pid)"
$port=if($state.Port){[int]$state.Port}else{5173}
if($process){
    if(-not $process.CommandLine.Contains($state.Entry) -or $process.CommandLine -notmatch "--port\s+$port(?:\s|$)"){throw 'PID已不属于记录的序采前端，拒绝停止。'}
    $stopped=Invoke-CimMethod -InputObject $process -MethodName Terminate
    if($stopped.ReturnValue -ne 0){throw "停止前端失败，系统返回$($stopped.ReturnValue)，保留进程记录。"}
    Write-Output "已停止序采前端PID=$($state.Pid)"
}
Remove-Item -LiteralPath $statePath
