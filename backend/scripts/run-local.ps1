[CmdletBinding()]
param(
    [switch]$InitializeData,
    [switch]$PrepareOnly
)

$ErrorActionPreference = 'Stop'
$taskBackendRoot = Split-Path -Parent $PSScriptRoot
$taskSettingsFile = Join-Path $taskBackendRoot '.env.local'
$taskContainer = 'repaircalc-postgres'
$taskVolume = 'repaircalc-pgdata'

function Invoke-LocalDocker {
    param([string[]]$DockerArguments)
    # Windows PowerShell 5 treats native stderr as ErrorRecords, including harmless progress output.
    $ErrorActionPreference = 'Continue'
    $taskOutput = & docker @DockerArguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "Docker $($DockerArguments[0]) failed: $($taskOutput -join [Environment]::NewLine)"
    }
    return $taskOutput
}

Push-Location -LiteralPath $taskBackendRoot
try {
    if (-not $env:JAVA_HOME) {
        $taskJavaCommand = Get-Command java.exe -ErrorAction SilentlyContinue
        if ($taskJavaCommand) { $env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $taskJavaCommand.Source) }
    }
    if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath "$env:JAVA_HOME\bin\javac.exe" -PathType Leaf)) {
        throw 'JDK 21 or newer is required. Set JAVA_HOME to its installation directory, then run again.'
    }
    $taskJava = Join-Path $env:JAVA_HOME 'bin/java.exe'
    $taskCompilerVersion = & "$env:JAVA_HOME\bin\javac.exe" -version 2>&1
    if ($LASTEXITCODE -ne 0 -or "$taskCompilerVersion" -notmatch '^javac (\d+)' -or [int]$Matches[1] -lt 21) {
        throw 'RepairCalc requires JDK 21 or newer. Update JAVA_HOME (JDK 17 is not supported).'
    }
    $env:Path = "$env:JAVA_HOME\bin;$env:Path"
    Write-Host "Using $taskCompilerVersion from JAVA_HOME."

    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'Docker CLI is required. Install/start Docker Desktop with Linux containers.' }
    $taskDockerOs = Invoke-LocalDocker -DockerArguments @('info', '--format', '{{.OSType}}')
    if ("$taskDockerOs" -ne 'linux') { throw 'Start Docker Desktop with Linux containers, then run again.' }
    if (-not $env:DOCKER_HOST) {
        $env:DOCKER_HOST = (Invoke-LocalDocker -DockerArguments @('context', 'inspect', '--format', '{{.Endpoints.docker.Host}}') | Out-String).Trim()
    }

    $taskExistingContainer = Invoke-LocalDocker -DockerArguments @('ps', '--all', '--filter', "name=^/$taskContainer`$", '--format', '{{.ID}}')
    $taskExistingVolume = Invoke-LocalDocker -DockerArguments @('volume', 'ls', '--filter', "name=^$taskVolume`$", '--format', '{{.Name}}')
    if (-not (Test-Path -LiteralPath $taskSettingsFile)) {
        if ($taskExistingContainer -or $taskExistingVolume) {
            throw 'An existing local database was found. Create backend/.env.local from .env.example with its original DB_PASSWORD; existing data will not be reset.'
        }
        $taskRandom = [Security.Cryptography.RandomNumberGenerator]::Create()
        try {
            $taskBytes = New-Object byte[] 32
            $taskRandom.GetBytes($taskBytes)
            $taskPassword = ([BitConverter]::ToString($taskBytes)).Replace('-', '').ToLowerInvariant()
        } finally { $taskRandom.Dispose() }
        $taskSettings = @(
            '# Local launcher settings. Do not commit or share this password.',
            'SPRING_PROFILES_ACTIVE=local', 'SERVER_ADDRESS=127.0.0.1', 'SERVER_PORT=8000',
            'DB_URL=jdbc:postgresql://127.0.0.1:55432/repaircalc', 'DB_USER=repaircalc', "DB_PASSWORD=$taskPassword"
        )
        [IO.File]::WriteAllLines($taskSettingsFile, $taskSettings, [Text.Encoding]::ASCII)
        Write-Host 'Created ignored backend/.env.local with a local database password.'
    }

    # Parse values as data, never as PowerShell code. This launcher owns only this loopback database.
    $taskLocalSettings = @{}
    $taskAllowedKeys = @('SPRING_PROFILES_ACTIVE', 'SERVER_ADDRESS', 'SERVER_PORT', 'DB_URL', 'DB_USER', 'DB_PASSWORD')
    foreach ($taskLine in Get-Content -LiteralPath $taskSettingsFile) {
        if ([string]::IsNullOrWhiteSpace($taskLine) -or $taskLine.TrimStart().StartsWith('#')) { continue }
        if ($taskLine -notmatch '^([A-Z_]+)=(.*)$') { throw 'Invalid .env.local: use KEY=value without shell quoting.' }
        $taskKey, $taskValue = $Matches[1], $Matches[2]
        if ($taskKey -notin $taskAllowedKeys -or $taskLocalSettings.ContainsKey($taskKey)) { throw 'Invalid or duplicate key in .env.local.' }
        $taskLocalSettings[$taskKey] = $taskValue
    }
    if ($taskLocalSettings.SPRING_PROFILES_ACTIVE -ne 'local' -or $taskLocalSettings.SERVER_ADDRESS -ne '127.0.0.1' -or
        $taskLocalSettings.DB_URL -ne 'jdbc:postgresql://127.0.0.1:55432/repaircalc' -or $taskLocalSettings.DB_USER -ne 'repaircalc' -or
        [string]::IsNullOrWhiteSpace($taskLocalSettings.DB_PASSWORD) -or $taskLocalSettings.SERVER_PORT -notmatch '^\d{1,5}$' -or
        [int]$taskLocalSettings.SERVER_PORT -lt 1024 -or [int]$taskLocalSettings.SERVER_PORT -gt 65535) {
        throw 'Invalid .env.local: use the local endpoint/profile/user from .env.example, a nonempty DB_PASSWORD and SERVER_PORT between 1024 and 65535.'
    }
    foreach ($taskKey in $taskAllowedKeys) { [Environment]::SetEnvironmentVariable($taskKey, $taskLocalSettings[$taskKey], 'Process') }

    if (-not $PrepareOnly) {
        $taskPortProbe = New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback, [int]$env:SERVER_PORT)
        try { $taskPortProbe.Start() }
        catch { throw "Port $env:SERVER_PORT is already in use. Stop the existing server or change SERVER_PORT in backend/.env.local." }
        finally { $taskPortProbe.Stop() }
    }

    if ($taskExistingContainer) {
        $taskInspection = (Invoke-LocalDocker -DockerArguments @('inspect', $taskContainer) | Out-String | ConvertFrom-Json)[0]
        $taskMount = @($taskInspection.Mounts | Where-Object { $_.Type -eq 'volume' -and $_.Name -eq $taskVolume -and $_.Destination -eq '/var/lib/postgresql/data' })
        $taskBinding = @($taskInspection.HostConfig.PortBindings.'5432/tcp')
        if ($taskInspection.Config.Image -ne 'postgres:17.11' -or $taskMount.Count -ne 1 -or $taskBinding.Count -ne 1 -or
            $taskBinding[0].HostIp -ne '127.0.0.1' -or $taskBinding[0].HostPort -ne '55432') {
            throw 'repaircalc-postgres has unexpected image/storage/ports. Use the manual setup instructions; the existing container will not be changed.'
        }
        $null = Invoke-LocalDocker -DockerArguments @('start', $taskContainer)
        Write-Host 'Reusing PostgreSQL container and persistent volume.'
    } else {
        $env:POSTGRES_PASSWORD = $env:DB_PASSWORD
        try {
            $null = Invoke-LocalDocker -DockerArguments @('run', '--detach', '--name', $taskContainer, '--publish', '127.0.0.1:55432:5432',
                '--env', 'POSTGRES_DB=repaircalc', '--env', 'POSTGRES_USER=repaircalc', '--env', 'POSTGRES_PASSWORD',
                '--mount', "source=$taskVolume,target=/var/lib/postgresql/data", 'postgres:17.11')
        } finally { Remove-Item Env:POSTGRES_PASSWORD -ErrorAction SilentlyContinue }
        Write-Host 'Created local PostgreSQL container with persistent storage.'
    }

    $taskDatabaseReady = $false
    for ($taskAttempt = 0; $taskAttempt -lt 60; $taskAttempt++) {
        try {
            # The image's temporary initialization server accepts Unix sockets before TCP is ready.
            $null = Invoke-LocalDocker -DockerArguments @('exec', $taskContainer, 'pg_isready', '-q', '--host=127.0.0.1', '-U', 'repaircalc', '-d', 'repaircalc')
            $taskDatabaseReady = $true
            break
        } catch { Start-Sleep -Seconds 1 }
    }
    if (-not $taskDatabaseReady) { throw 'PostgreSQL did not become ready within 60 seconds. Inspect docker logs repaircalc-postgres.' }
    $taskInspection = (Invoke-LocalDocker -DockerArguments @('inspect', $taskContainer) | Out-String | ConvertFrom-Json)[0]
    $taskDatabaseAddress = @($taskInspection.NetworkSettings.Networks.PSObject.Properties.Value |
        Where-Object { $_.IPAddress } | ForEach-Object { $_.IPAddress }) | Select-Object -First 1
    if (-not $taskDatabaseAddress) { throw 'Cannot check PostgreSQL password: no container IPv4 address was found.' }
    $env:PGPASSWORD = $env:DB_PASSWORD
    try {
        # Loopback uses trust in the stock image; use its bridge address to actually verify the password.
        $null = Invoke-LocalDocker -DockerArguments @('exec', '--env', 'PGPASSWORD', $taskContainer, 'psql', "--host=$taskDatabaseAddress", '--username=repaircalc', '--dbname=repaircalc', '--no-password', '--command=SELECT 1')
    } finally { Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue }

    & .\mvnw.cmd -B -ntp '-DskipTests' package
    if ($LASTEXITCODE -ne 0) { throw 'Backend build failed; the server was not started.' }
    $taskJar = Join-Path $taskBackendRoot 'target/repaircalc-backend-0.1.0-SNAPSHOT.jar'
    if ($InitializeData) {
        & $taskJava -jar $taskJar --repaircalc.command=import-catalog --repaircalc.catalog.file=data/catalog-v1.json --repaircalc.catalog.mappings=data/work-mappings-v1.json
        if ($LASTEXITCODE -ne 0) { throw 'Catalog import failed; the server was not started.' }
        & $taskJava -jar $taskJar --repaircalc.command=import-rules --repaircalc.rules.file=data/rules-v1.json
        if ($LASTEXITCODE -ne 0) { throw 'Rules import failed; the server was not started.' }
    }
    if (-not $PrepareOnly) {
        Write-Host "Starting http://127.0.0.1:$env:SERVER_PORT (Ctrl+C stops Java; PostgreSQL data is retained)."
        & $taskJava -jar $taskJar
        if ($LASTEXITCODE -ne 0) { throw "Backend exited with code $LASTEXITCODE." }
    }
} catch {
    Write-Host "Local startup failed: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
} finally {
    Pop-Location
}
