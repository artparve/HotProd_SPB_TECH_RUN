$ErrorActionPreference = 'Stop'
$taskBackendRoot = Split-Path -Parent $PSScriptRoot
$taskCatalogResponse = Join-Path $taskBackendRoot 'target/contracts/products-response.json'
$taskCalculationResponse = Join-Path $taskBackendRoot 'target/contracts/product-estimates-response.json'
$taskOwnedResponses = @($taskCatalogResponse, $taskCalculationResponse)
# Only integration-test-owned output files are removed; failed runs cannot validate an older response.
foreach ($taskResponse in $taskOwnedResponses) {
    if (Test-Path -LiteralPath $taskResponse -PathType Leaf) { Remove-Item -LiteralPath $taskResponse }
    elseif (Test-Path -LiteralPath $taskResponse) { throw "Expected an owned JSON output file: $taskResponse" }
}
Push-Location -LiteralPath $taskBackendRoot
try {
    if ($IsWindows -or $env:OS -eq 'Windows_NT') { & .\mvnw.cmd verify }
    else { & ./mvnw verify }
    if ($LASTEXITCODE -ne 0) { throw 'Maven verify failed; frontend contract validation was not run.' }
    foreach ($taskResponse in $taskOwnedResponses) {
        if (-not (Test-Path -LiteralPath $taskResponse -PathType Leaf)) {
            throw "API integration tests did not produce a fresh HTTP response: $taskResponse"
        }
    }
    & node --experimental-strip-types src/test/js/catalog-contract.mjs $taskCatalogResponse --fixtures
    if ($LASTEXITCODE -ne 0) { throw 'Frontend productSchema contract validation failed.' }
    & node --experimental-strip-types src/test/js/calculation-contract.mjs $taskCalculationResponse
    if ($LASTEXITCODE -ne 0) { throw 'Frontend estimateSchema contract validation failed.' }
} finally {
    Pop-Location
}
