$ErrorActionPreference = 'Stop'
$taskBackendRoot = Split-Path -Parent $PSScriptRoot
$taskResponse = Join-Path $taskBackendRoot 'target/contracts/products-response.json'
# Remove only the output owned by CatalogApiIT, so a failed run cannot validate stale JSON.
if (Test-Path -LiteralPath $taskResponse) { Remove-Item -LiteralPath $taskResponse }
Push-Location -LiteralPath $taskBackendRoot
try {
    if ($IsWindows -or $env:OS -eq 'Windows_NT') { & .\mvnw.cmd verify }
    else { & ./mvnw verify }
    if ($LASTEXITCODE -ne 0) { throw 'Maven verify failed; contract validation was not run.' }
    if (-not (Test-Path -LiteralPath $taskResponse)) { throw 'CatalogApiIT did not produce a fresh HTTP response.' }
    & node --experimental-strip-types src/test/js/catalog-contract.mjs $taskResponse --fixtures
    if ($LASTEXITCODE -ne 0) { throw 'Frontend Zod contract validation failed.' }
} finally {
    Pop-Location
}
