# Copy to .runtime/portable-env.ps1, edit privately, then dot-source the copy.
# These are examples for a separate local dependency environment, not existing credentials.
# Optional product images: absolute path to private endpoint/region/bucket/prefix/accessKeyId/accessKeySecret JSON.
# $env:XUCAI_OSS_CONFIG='C:\private\xucai-oss.json' # Never commit this credential file.
$env:XUCAI_DB_HOST='127.0.0.1'
$env:XUCAI_DB_PORT='13306'
# MySQL 8 caching_sha2_password: pin the server PUBLIC key copied through your trusted Docker/SSH connection.
# Use a forward-slash path without spaces; do not copy private_key.pem or enable arbitrary key retrieval.
$env:XUCAI_DB_RSA_PUBLIC_KEY=''
$env:XUCAI_DB_USERNAME='xucai_app'
$env:XUCAI_DB_PASSWORD='' # Required: a NEW app account restricted to the five procurement databases.
$env:XUCAI_REDIS_HOST='127.0.0.1'
$env:XUCAI_REDIS_PASSWORD='' # Required. Redis Stack must be exposed on port 16379.
$env:XUCAI_NACOS_SERVER='127.0.0.1:18848'
$env:XUCAI_NACOS_NAMESPACE='' # Empty means the public namespace; discovery group stays XUCAI.
$env:XUCAI_NACOS_USERNAME=''
$env:XUCAI_NACOS_PASSWORD=''
$env:XUCAI_DISCOVERY_IP='127.0.0.1' # Must be reachable by the six app processes.
$env:XUCAI_REQUIREMENT_MODEL_ENABLED='false'
$env:XUCAI_RETRIEVAL_ENABLED='false'
$env:XUCAI_GENERATION_MODEL='qwen3.7-flash-2026-07-15'
$env:XUCAI_MODEL_MAX_CALLS='30'
$env:XUCAI_EMBEDDING_MAX_CALLS='6'
$env:XUCAI_EMBEDDING_MAX_QUERIES='10'
# Optional later, after a bounded model budget is prepared: $env:XUCAI_DASHSCOPE_KEY='...'
$jwtFile=Join-Path (Split-Path $PSScriptRoot -Parent) '.runtime/portable-jwt/jwt-env.json'
if (-not (Test-Path -LiteralPath $jwtFile)) { throw 'Generate an independent local JWT keystore first.' }
$jwtSettings=Get-Content -LiteralPath $jwtFile -Raw | ConvertFrom-Json
$jwtSettings.PSObject.Properties | ForEach-Object {
    [Environment]::SetEnvironmentVariable($_.Name,[string]$_.Value,'Process')
}
Remove-Variable jwtSettings
