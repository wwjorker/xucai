param([string]$BaseUrl='http://127.0.0.1:10010', [string]$OutputPath='')
$ErrorActionPreference='Stop'
$results=[Collections.Generic.List[object]]::new()
function Invoke-Check([string]$Name,[string]$Path,[int]$ExpectedStatus,[hashtable]$Headers=@{},[string]$Method='GET',[object]$Body=$null) {
    $options=@{Uri="$BaseUrl$Path";Method=$Method;Headers=$Headers;SkipHttpErrorCheck=$true;TimeoutSec=15}
    if($null -ne $Body){$options.Body=($Body|ConvertTo-Json -Compress);$options.ContentType='application/json'}
    $response=Invoke-WebRequest @options
    $json=$response.Content|ConvertFrom-Json
    # 登录响应含 token，只记录状态，不输出或保存原响应。
    $entry=@{case=$Name;http_status=[int]$response.StatusCode;business_code=$json.code}
    if($Path -eq '/users/users/me' -and $null -ne $json.data){$entry.returned_user_id=[string]$json.data.id}
    $results.Add($entry)
    if([int]$response.StatusCode -ne $ExpectedStatus){throw "$Name 状态错误：$($response.StatusCode)"}
    return $json
}
[void](Invoke-Check 'anonymous' '/users/users/me' 401)
[void](Invoke-Check 'forged-user' '/users/users/me' 401 @{'user-info'='10001';'token-info'='forged-demo-token'})
[void](Invoke-Check 'invalid-token-and-forged-user' '/users/users/me' 401 @{'authorization'='invalid-demo-token';'user-info'='10001'})
foreach($account in @(
    @{Name='buyer_demo';Path='/auth/accounts/login';Id='10001'},
    @{Name='merchant_demo';Path='/auth/accounts/admin/login';Id='10002'}
)) {
    $login=Invoke-Check "$($account.Name)-login" $account.Path 200 @{} 'POST' @{type=1;username=$account.Name;password='XucaiDemo2026!';rememberMe=$false}
    if($login.code -ne 200 -or -not $login.data){throw "$($account.Name) 登录失败"}
    $me=Invoke-Check "$($account.Name)-me" '/users/users/me' 200 @{authorization=[string]$login.data}
    if([string]$me.data.id -ne $account.Id){throw '身份不符'}
    $overridden=Invoke-Check "$($account.Name)-token-overrides-forged-user" '/users/users/me' 200 @{authorization=[string]$login.data;'user-info'='99999';'token-info'='forged-demo-token'}
    if([string]$overridden.data.id -ne $account.Id){throw '伪造身份覆盖了真实 token'}
}
if($OutputPath){$results|ConvertTo-Json -Depth 5|Set-Content -LiteralPath $OutputPath -Encoding utf8}
$results|ForEach-Object { [pscustomobject]$_ }|Format-Table case,http_status,business_code,returned_user_id -AutoSize
