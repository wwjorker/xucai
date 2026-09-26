param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$Maven = 'mvn.cmd',
    [switch]$Online,
    [string]$MavenRepository = '',
    [ValidateSet('test','package')][string]$Goal = 'test',
    [switch]$PlanOnly
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
# 只构建采购运行的六个应用及其必需依赖。
$projects = 'xucai-gateway,xucai-user,xucai-auth/xucai-auth-service,xucai-catalog,xucai-trade,xucai-ai'
# 明确的离线回归集合，外部服务契约按配置单独启用。
$tests = @(
    'AccountAuthFilterTest',
    'AnalysisRunServiceTest',
    'AssistantCardPresentationTest',
    'AssistantComparisonTest',
    'AssistantPurchaseScopeTest',
    'AssistantCartProposalTest',
    'AssistantEvidenceSearchTest',
    'AssistantIntentRulesTest',
    'AssistantOrchestratorTest',
    'AssistantProgressTest',
    'AssistantRequirementDraftTest',
    'AssistantRequirementServiceTest',
    'AssistantSessionStoreTest',
    'AssistantShoppingConstraintsTest',
    'AssistantSpringAiTransportTest',
    'AssistantSpringAiWireTest',
    'AssistantToolsTest',
    'AssistantToolCatalogTest',
    'AssistantUsageAdvisorTest',
    'AuthHttpStatusTest',
    'CandidateDraftRulesTest',
    'CandidateDraftServiceTest',
    'CandidateFactGuardTest',
    'CandidatePlannerTest',
    'CandidatePoolFixtureTest',
    'CandidateRetrievalContractTest',
    'CandidateVectorRetrieverTest',
    'CookieBuilderTest',
    'DashScopeEmbeddingClientTest',
    'DashScopeRequirementAnalyzerTest',
    'DirectPurchaseRulesTest',
    'DirectPurchaseServiceTest',
    'DocChunkerTest',
    'ExtractedTextGroundingTest',
    'FrozenLimitsReplayTest',
    'GatewayAuthStatusTest',
    'GenerationModelSelectionTest',
    'JdbcEmbeddingLedgerTest',
    'MerchantCandidateControllerTest',
    'ModelCallBudgetTest',
    'PlanDraftRulesTest',
    'PlanReviewRulesTest',
    'PortableAuthPropertiesTest',
    'ProductImageTest',
    'ProductMutationLockTest',
    'ProductVisibilityTest',
    'PublicDocChunkTest',
    'PurchaseOrderRulesTest',
    'RedisProcurementContractTest',
    'RequirementRulesTest',
    'RequirementSpringAiTest',
    'RequirementUsageAdvisorTest',
    'AssistantConversationContextTest',
    'RetrievalIsolationTest',
    'RetrievalRequestReplayTest',
    'RetrievalServiceTest',
    'SessionContractTest',
    'SpringAiCompatibilityTest',
    'SpringAiEmbeddingTransportTest',
    'XucaiEnvironmentGuardTest',
    'XucaiRoleControllerTest',
    'XucaiRouteFilterTest',
    'XucaiUserControllerTest',
    'UserIdentityTest'
)
$arguments = @('-llr','-pl',$projects,'-am',"-Dtest=$($tests -join ',')",'-Dsurefire.failIfNoSpecifiedTests=false',$Goal)
if (-not $Online) { $arguments = @('-o') + $arguments }
if ($Online -or $MavenRepository) {
    if (-not $MavenRepository) { $MavenRepository = Join-Path $root '.runtime/maven-repository' }
    $MavenRepository = [IO.Path]::GetFullPath($MavenRepository)
    $arguments = @('-gs',(Join-Path $root 'config/maven-global-empty.xml'),
        '-s',(Join-Path $root 'config/maven-public.xml'),"-Dmaven.repo.local=$MavenRepository") + $arguments
}
if ($PlanOnly) {
    [pscustomobject]@{Name='序采采购服务';Goal=$Goal;Offline=(-not $Online);Repository=$MavenRepository;Projects=$projects;Tests=$tests;Arguments=$arguments}
    return
}
if (-not (Test-Path -LiteralPath "$JavaHome/bin/java.exe")) { throw '请指定可用的JDK 17：-JavaHome。' }
$mavenCommand = Get-Command $Maven -ErrorAction Stop
if ($Goal -eq 'package') {
    # Windows运行中的jar不能覆盖；拒绝隐式停止服务，也不先构建一半再报错。
    foreach ($jar in @('xucai-gateway/target/xucai-gateway.jar','xucai-user/target/xucai-user.jar','xucai-auth/xucai-auth-service/target/xucai-auth-service.jar','xucai-catalog/target/xucai-catalog.jar','xucai-trade/target/xucai-trade.jar','xucai-ai/target/xucai-ai.jar')) {
        $path = Join-Path $root $jar
        if (Test-Path -LiteralPath $path) {
            try { $stream = [IO.File]::Open($path,'Open','ReadWrite','None'); $stream.Dispose() }
            catch { throw "无法独占待打包文件 $jar，请先核对并停止对应采购服务。脚本不会自动停止服务。" }
        }
    }
}
$previousJava = $env:JAVA_HOME
try {
    $env:JAVA_HOME = $JavaHome
    Push-Location $root
    try {
        & $mavenCommand.Source @arguments
        if ($LASTEXITCODE -ne 0) { throw "序采构建失败，Maven退出码：$LASTEXITCODE" }
    } finally { Pop-Location }
} finally { $env:JAVA_HOME = $previousJava }
