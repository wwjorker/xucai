param([ValidateSet('all','user','auth','gateway','catalog','trade','ai')][string]$Application='all')
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Get-ChildItem -LiteralPath (Join-Path $root '.runtime') -Filter '*.json' -ErrorAction SilentlyContinue | ForEach-Object {
    $state = Get-Content -LiteralPath $_.FullName -Raw | ConvertFrom-Json
    if ($state.Pid -and ($Application -eq 'all' -or $state.Name -eq $Application)) {
        $process = Get-CimInstance Win32_Process -Filter "ProcessId=$($state.Pid)"
        if ($process) {
            if (-not $process.CommandLine.Contains($state.Jar) -or -not ($process.CommandLine.Contains('--spring.profiles.active=local,xucai') -or $process.CommandLine.Contains('--spring.profiles.active=portable,xucai'))) {
                throw "PID $($state.Pid) 已不属于记录中的采购应用，拒绝停止。"
            }
            Stop-Process -Id $state.Pid
            Write-Output "已停止 $($state.Name) PID=$($state.Pid)"
        }
        Remove-Item -LiteralPath $_.FullName
    }
}
