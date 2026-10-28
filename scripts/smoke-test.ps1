[CmdletBinding()]
param(
    [ValidateRange(1, 65535)]
    [int]$DbPort = $(if ($env:POSTGRES_PORT) { [int]$env:POSTGRES_PORT } else { 55432 }),

    [ValidateNotNullOrEmpty()]
    [string]$BackendUrl = $(if ($env:BACKEND_URL) { $env:BACKEND_URL } else { 'http://127.0.0.1:8080' }),

    [ValidateRange(10, 600)]
    [int]$StartupTimeoutSeconds = $(if ($env:SMOKE_STARTUP_TIMEOUT_SECONDS) { [int]$env:SMOKE_STARTUP_TIMEOUT_SECONDS } else { 180 }),

    [ValidateRange(10, 600)]
    [int]$RequestTimeoutSeconds = $(if ($env:SMOKE_REQUEST_TIMEOUT_SECONDS) { [int]$env:SMOKE_REQUEST_TIMEOUT_SECONDS } else { 180 }),

    [ValidateRange(10, 600)]
    [int]$IndexTimeoutSeconds = $(if ($env:SMOKE_INDEX_TIMEOUT_SECONDS) { [int]$env:SMOKE_INDEX_TIMEOUT_SECONDS } else { 120 })
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
Add-Type -AssemblyName System.Net.Http

$repoRoot = Split-Path -Parent $PSScriptRoot
$backendProcess = $null
$backendStdout = $null
$backendStderr = $null
$locationPushed = $false
$backendUri = [Uri]$BackendUrl
$smokeUsername = $null
$smokeUgcId = $null
$smokeMarker = $null
$smokeRegistrationAttempted = $false
$smokeSummary = $null
$runFailure = $null
$cleanupFailures = New-Object System.Collections.Generic.List[string]
$managedEnvironmentNames = @(
    'POSTGRES_PORT',
    'DB_URL',
    'DB_USERNAME',
    'DB_PASSWORD',
    'SERVER_PORT',
    'JWT_SECRET'
)
$originalEnvironment = @{}
foreach ($name in $managedEnvironmentNames) {
    $originalEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

if (-not $backendUri.IsAbsoluteUri -or $backendUri.Scheme -ne 'http' -or -not $backendUri.IsLoopback) {
    throw 'BackendUrl must be an absolute loopback HTTP URL.'
}

function Write-Step {
    param([Parameter(Mandatory = $true)][string]$Message)
    Write-Host "`n==> $Message" -ForegroundColor Cyan
}

function ConvertTo-NativeArgument {
    param([AllowEmptyString()][string]$Argument)

    if ($Argument.Length -eq 0) {
        return '""'
    }
    if ($Argument -notmatch '[\s"]') {
        return $Argument
    }

    $escaped = [regex]::Replace($Argument, '(\\*)"', '$1$1\"')
    $escaped = [regex]::Replace($escaped, '(\\+)$', '$1$1')
    return '"' + $escaped + '"'
}

function Stop-ProcessTreeById {
    param(
        [Parameter(Mandatory = $true)][int]$ProcessId,
        [ValidateRange(1, 60)][int]$TimeoutSeconds = 10
    )

    if (-not (Get-Process -Id $ProcessId -ErrorAction SilentlyContinue)) {
        return
    }

    $killer = Start-Process `
        -FilePath 'taskkill.exe' `
        -ArgumentList "/PID $ProcessId /T /F" `
        -WindowStyle Hidden `
        -PassThru
    $null = $killer.Handle
    if (-not $killer.WaitForExit($TimeoutSeconds * 1000)) {
        $killer.Kill()
        throw "taskkill timed out for process tree PID $ProcessId."
    }
    $killer.Refresh()
    if ($killer.ExitCode -ne 0 -and (Get-Process -Id $ProcessId -ErrorAction SilentlyContinue)) {
        throw "taskkill failed with exit code $($killer.ExitCode) for process tree PID $ProcessId."
    }
}

function Invoke-BoundedCommand {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)]
        [ValidateRange(1, 3600)]
        [int]$TimeoutSeconds,
        [switch]$CaptureOutput,
        [switch]$Quiet
    )

    $stdoutPath = [IO.Path]::GetTempFileName()
    $stderrPath = [IO.Path]::GetTempFileName()
    $process = $null
    $stdout = ''
    $stderr = ''
    try {
        $argumentLine = ($Arguments | ForEach-Object {
            ConvertTo-NativeArgument -Argument $_
        }) -join ' '
        $startFilePath = $FilePath
        if ([IO.Path]::GetExtension($FilePath) -in @('.cmd', '.bat')) {
            $commandLine = ConvertTo-NativeArgument -Argument $FilePath
            if (-not [string]::IsNullOrWhiteSpace($argumentLine)) {
                $commandLine += ' ' + $argumentLine
            }
            $startFilePath = $env:ComSpec
            $argumentLine = '/d /s /c "' + $commandLine + '"'
        }
        $process = Start-Process `
            -FilePath $startFilePath `
            -ArgumentList $argumentLine `
            -WorkingDirectory $repoRoot `
            -RedirectStandardOutput $stdoutPath `
            -RedirectStandardError $stderrPath `
            -WindowStyle Hidden `
            -PassThru
# 在短生命周期进程退出前缓存原生句柄；否则 Windows PowerShell 5.1 在重定向后可能暴露空 ExitCode。
        $null = $process.Handle
        if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
            try {
                Stop-ProcessTreeById -ProcessId $process.Id
            } catch {
                throw "Command timed out after $TimeoutSeconds seconds and its process tree could not be stopped: $FilePath. $($_.Exception.Message)"
            }
            throw "Command timed out after $TimeoutSeconds seconds: $FilePath"
        }
        $process.WaitForExit()
        $process.Refresh()
        $stdoutContent = Get-Content -Raw -LiteralPath $stdoutPath -ErrorAction SilentlyContinue
        $stderrContent = Get-Content -Raw -LiteralPath $stderrPath -ErrorAction SilentlyContinue
        $stdout = if ($null -eq $stdoutContent) { '' } else { [string]$stdoutContent }
        $stderr = if ($null -eq $stderrContent) { '' } else { [string]$stderrContent }
        if ($process.ExitCode -ne 0) {
            $detail = (@($stderr, $stdout) | Where-Object {
                -not [string]::IsNullOrWhiteSpace($_)
            }) -join [Environment]::NewLine
            if ($detail.Length -gt 2000) {
                $detail = $detail.Substring(0, 2000) + '...'
            }
            throw "Command failed with exit code $($process.ExitCode): $FilePath. $detail"
        }

        if ($CaptureOutput) {
            return $stdout.TrimEnd()
        }
        if (-not $Quiet) {
            if (-not [string]::IsNullOrWhiteSpace($stdout)) {
                Write-Host $stdout.TrimEnd()
            }
            if (-not [string]::IsNullOrWhiteSpace($stderr)) {
                Write-Host $stderr.TrimEnd()
            }
        }
    } finally {
        Remove-Item -LiteralPath $stdoutPath, $stderrPath -Force -ErrorAction SilentlyContinue
    }
}

function Invoke-Api {
    param(
        [Parameter(Mandatory = $true)]
        [ValidateSet('GET', 'POST', 'PUT', 'PATCH', 'DELETE')]
        [string]$Method,

        [Parameter(Mandatory = $true)][string]$Path,
        [AllowNull()][object]$Body,
        [AllowNull()][string]$Token
    )

    $headers = @{}
    if (-not [string]::IsNullOrWhiteSpace($Token)) {
        $headers.Authorization = "Bearer $Token"
    }

    $request = @{
        Method = $Method
        Uri = $BackendUrl.TrimEnd('/') + $Path
        Headers = $headers
        TimeoutSec = $RequestTimeoutSeconds
    }
    if ($null -ne $Body) {
        $request.ContentType = 'application/json; charset=utf-8'
        $request.Body = $Body | ConvertTo-Json -Depth 10 -Compress
    }

    $response = Invoke-RestMethod @request
    if ($null -eq $response -or $null -eq $response.code) {
        throw "API response from $Path does not follow ApiResponse."
    }
    if ([int]$response.code -ne 0) {
        throw "API $Method $Path failed with code $($response.code): $($response.message)"
    }
    return $response.data
}

function Wait-Postgres {
    $deadline = (Get-Date).AddSeconds($StartupTimeoutSeconds)
    $lastFailure = $null
    while ((Get-Date) -lt $deadline) {
        $remainingSeconds = [int][Math]::Ceiling(($deadline - (Get-Date)).TotalSeconds)
        if ($remainingSeconds -le 0) {
            break
        }
        try {
            Invoke-BoundedCommand `
                -FilePath 'docker' `
                -Arguments @('compose', 'exec', '-T', 'postgres', 'pg_isready', '-U', 'college', '-d', 'college') `
                -TimeoutSeconds ([Math]::Min(10, $remainingSeconds)) `
                -Quiet
            return
        } catch {
            $lastFailure = $_.Exception.Message
        }
        Start-Sleep -Seconds 2
    }
    throw "PostgreSQL did not become ready within $StartupTimeoutSeconds seconds. Last failure: $lastFailure"
}

function Assert-BackendPortAvailable {
    $listeners = [System.Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners()
    if ($listeners | Where-Object { $_.Port -eq $backendUri.Port }) {
        throw "Backend port $($backendUri.Port) is already in use. Stop the existing listener or choose another BackendUrl."
    }
}

function Wait-Backend {
    $deadline = (Get-Date).AddSeconds($StartupTimeoutSeconds)
    $client = New-Object System.Net.Http.HttpClient
    $client.Timeout = [TimeSpan]::FromSeconds(2)
    try {
        while ((Get-Date) -lt $deadline) {
            if ($backendProcess -and $backendProcess.HasExited) {
                throw "Backend exited before becoming ready. See $backendStdout and $backendStderr."
            }

            $started = $false
            if (Test-Path -LiteralPath $backendStdout -PathType Leaf) {
                $started = [bool](Select-String -LiteralPath $backendStdout `
                    -SimpleMatch 'Started CollegeAssistantApplication' `
                    -Quiet `
                    -ErrorAction SilentlyContinue)
            }
            if ($started) {
                $response = $null
                try {
                    $response = $client.GetAsync(
                        $BackendUrl.TrimEnd('/') + '/api/profile').GetAwaiter().GetResult()
                    $contentType = $response.Content.Headers.ContentType
                    $mediaType = if ($contentType) { [string]$contentType.MediaType } else { '' }
                    if ([int]$response.StatusCode -eq 401 `
                            -and $mediaType -eq 'application/json' `
                            -and -not $backendProcess.HasExited) {
                        return
                    }
                } catch {
# 应用仍在启动。
                } finally {
                    if ($response) {
                        $response.Dispose()
                    }
                }
            }
            Start-Sleep -Seconds 2
        }
    } finally {
        $client.Dispose()
    }
    throw "Backend did not become ready within $StartupTimeoutSeconds seconds. See $backendStdout and $backendStderr."
}

function Invoke-PostgresScalar {
    param(
        [Parameter(Mandatory = $true)][string]$Sql,
        [ValidateRange(1, 600)][int]$TimeoutSeconds = $RequestTimeoutSeconds
    )

    $output = Invoke-BoundedCommand `
        -FilePath 'docker' `
        -Arguments @('compose', 'exec', '-T', 'postgres', 'psql', '-v', 'ON_ERROR_STOP=1', '-U', 'college', '-d', 'college', '-tA', '-c', $Sql) `
        -TimeoutSeconds $TimeoutSeconds `
        -CaptureOutput `
        -Quiet
    return (($output -split "`r?`n") | Select-Object -First 1).Trim()
}

function Invoke-PostgresCommand {
    param([Parameter(Mandatory = $true)][string]$Sql)

    $null = Invoke-BoundedCommand `
        -FilePath 'docker' `
        -Arguments @('compose', 'exec', '-T', 'postgres', 'psql', '-v', 'ON_ERROR_STOP=1', '-U', 'college', '-d', 'college', '-q', '-c', $Sql) `
        -TimeoutSeconds $RequestTimeoutSeconds `
        -CaptureOutput `
        -Quiet
}

function Wait-UgcIndex {
    param([Parameter(Mandatory = $true)][long]$UgcId)

    $deadline = (Get-Date).AddSeconds($IndexTimeoutSeconds)
    $sql = "SELECT count(*) FROM embedding_chunk WHERE doc_ref_type='UGC' AND source_type='UGC' AND doc_ref_id=$UgcId;"
    $lastFailure = $null
    while ((Get-Date) -lt $deadline) {
        $remainingSeconds = [int][Math]::Ceiling(($deadline - (Get-Date)).TotalSeconds)
        if ($remainingSeconds -le 0) {
            break
        }
        try {
            if ([int](Invoke-PostgresScalar `
                    -Sql $sql `
                    -TimeoutSeconds ([Math]::Min(10, $remainingSeconds))) -gt 0) {
                return
            }
        } catch {
            $lastFailure = $_.Exception.Message
        }
        Start-Sleep -Seconds 2
    }
    throw "UGC $UgcId was not indexed within $IndexTimeoutSeconds seconds. Check the embedding provider and backend logs. Last failure: $lastFailure"
}

function Invoke-SseChat {
    param(
        [Parameter(Mandatory = $true)][string]$Token,
        [Parameter(Mandatory = $true)][string]$Question,
        [Parameter(Mandatory = $true)][long]$ExpectedUgcId,
        [Parameter(Mandatory = $true)][string]$ExpectedMarker
    )

    $chunkIdCsv = Invoke-PostgresScalar -Sql (
        "SELECT string_agg(id::text, ',' ORDER BY id) FROM embedding_chunk " +
        "WHERE doc_ref_type='UGC' AND doc_ref_id=$ExpectedUgcId;")
    if ([string]::IsNullOrWhiteSpace($chunkIdCsv)) {
        throw "UGC $ExpectedUgcId has no indexed chunks."
    }
    $expectedChunkIds = @($chunkIdCsv.Split(',') | ForEach-Object { [long]$_ })

    $handler = New-Object System.Net.Http.HttpClientHandler
    $client = New-Object System.Net.Http.HttpClient($handler)
    $client.Timeout = [TimeSpan]::FromSeconds($RequestTimeoutSeconds)
    $request = $null
    $response = $null
    $reader = $null
    $content = $null

    try {
        $client.DefaultRequestHeaders.Authorization =
            New-Object System.Net.Http.Headers.AuthenticationHeaderValue('Bearer', $Token)
        $client.DefaultRequestHeaders.Accept.ParseAdd('text/event-stream')

        $payload = @{
            question = $Question
            useMemory = $true
            maxSources = 8
        } | ConvertTo-Json -Compress
        $content = New-Object System.Net.Http.StringContent(
            $payload,
            [Text.Encoding]::UTF8,
            'application/json')
        $request = New-Object System.Net.Http.HttpRequestMessage(
            [System.Net.Http.HttpMethod]::Post,
            ($BackendUrl.TrimEnd('/') + '/api/chat/stream'))
        $request.Content = $content

        $response = $client.SendAsync(
            $request,
            [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead).GetAwaiter().GetResult()
        if (-not $response.IsSuccessStatusCode) {
            throw "SSE request failed with HTTP $([int]$response.StatusCode)."
        }
        $contentType = $response.Content.Headers.ContentType
        $mediaType = if ($contentType) { [string]$contentType.MediaType } else { '' }
        if ($mediaType -ne 'text/event-stream') {
            throw "SSE response Content-Type must be text/event-stream but was '$mediaType'."
        }

        $reader = New-Object System.IO.StreamReader(
            $response.Content.ReadAsStreamAsync().GetAwaiter().GetResult())
        $eventName = 'message'
        $dataLines = New-Object System.Collections.Generic.List[string]
        $meta = $null
        $done = $null
        $deltas = New-Object System.Collections.Generic.List[string]
        $sources = New-Object System.Collections.Generic.List[object]

        $readDeadline = [DateTime]::UtcNow.AddSeconds($RequestTimeoutSeconds)
        while ($true) {
            $remainingMilliseconds = [int][Math]::Ceiling(
                ($readDeadline - [DateTime]::UtcNow).TotalMilliseconds)
            if ($remainingMilliseconds -le 0) {
                throw "SSE stream did not finish within $RequestTimeoutSeconds seconds."
            }
            $readTask = $reader.ReadLineAsync()
            if (-not $readTask.Wait($remainingMilliseconds)) {
                throw "SSE stream did not finish within $RequestTimeoutSeconds seconds."
            }
            $line = $readTask.Result
            if ($null -eq $line) {
                break
            }
            if ($line -eq '') {
                $json = $dataLines -join "`n"
                if (-not [string]::IsNullOrWhiteSpace($json)) {
                    $eventData = $json | ConvertFrom-Json
                    switch ($eventName) {
                        'meta' { $meta = $eventData }
                        'delta' {
                            if (-not [string]::IsNullOrWhiteSpace([string]$eventData.content)) {
                                $deltas.Add([string]$eventData.content)
                            }
                        }
                        'source' { $sources.Add($eventData) }
                        'done' { $done = $eventData }
                        'error' {
                            throw "SSE error $($eventData.code): $($eventData.message)"
                        }
                    }
                }
                if ($eventName -eq 'done') {
                    break
                }
                $eventName = 'message'
                $dataLines.Clear()
                continue
            }

            if ($line.StartsWith('event:')) {
                $eventName = $line.Substring(6).Trim()
            } elseif ($line.StartsWith('data:')) {
                $dataLines.Add($line.Substring(5).TrimStart())
            }
        }

        if ($null -eq $meta) {
            throw 'SSE stream did not contain a meta event.'
        }
        if ($null -eq $done) {
            throw 'SSE stream did not contain a done event.'
        }

        $doneSources = @($done.sources)
        if ($done.answerStatus -ne 'ANSWERED') {
            throw "Expected ANSWERED but received $($done.answerStatus)."
        }
        if ($deltas.Count -eq 0) {
            throw 'SSE stream did not contain a non-empty delta event.'
        }
        if (-not ($sources.ToArray() | Where-Object {
            $_.sourceType -eq 'UGC' `
                -and $_.referenceType -eq 'UGC' `
                -and $null -eq $_.documentId `
                -and $null -eq $_.externalSourceUrl `
                -and $expectedChunkIds -contains [long]$_.chunkId
        })) {
            throw "SSE source events did not cite the smoke-test UGC $ExpectedUgcId."
        }
        if (-not ($doneSources | Where-Object {
            $_.sourceType -eq 'UGC' `
                -and $_.referenceType -eq 'UGC' `
                -and $null -eq $_.documentId `
                -and $null -eq $_.externalSourceUrl `
                -and $expectedChunkIds -contains [long]$_.chunkId
        })) {
            throw "SSE done event did not cite the smoke-test UGC $ExpectedUgcId."
        }
        if (-not ([string]$done.answer).Contains($ExpectedMarker)) {
            throw "Chat answer did not contain the expected marker '$ExpectedMarker'."
        }

        return [pscustomobject]@{
            SessionId = $done.sessionId
            AssistantMessageId = $done.assistantMessageId
            AnswerStatus = $done.answerStatus
            Answer = [string]$done.answer
            SourceCount = $doneSources.Count
        }
    } finally {
        if ($reader) { $reader.Dispose() }
        if ($response) { $response.Dispose() }
        if ($request) { $request.Dispose() }
        if ($content) { $content.Dispose() }
        $client.Dispose()
        $handler.Dispose()
    }
}

function Stop-BackendProcessTree {
    if ($null -eq $backendProcess) {
        return
    }
    if (-not $backendProcess.HasExited) {
        Stop-ProcessTreeById -ProcessId $backendProcess.Id
        if (-not $backendProcess.WaitForExit(5000)) {
            throw "Backend process PID $($backendProcess.Id) did not exit after taskkill."
        }
    }
}

function Remove-SmokeData {
    if (-not $smokeRegistrationAttempted -or [string]::IsNullOrWhiteSpace($smokeUsername)) {
        return
    }

    $safeUsername = $smokeUsername.Replace("'", "''")
    $safeMarker = if ($smokeMarker) { $smokeMarker.Replace("'", "''") } else { '' }
    $statements = New-Object System.Collections.Generic.List[string]
    $statements.Add(
        "DELETE FROM embedding_chunk WHERE doc_ref_type='UGC' AND doc_ref_id IN (" +
        "SELECT p.id FROM ugc_post p JOIN users u ON u.id=p.author_id " +
        "WHERE u.username='$safeUsername');")
    $statements.Add("DELETE FROM users WHERE username='$safeUsername';")
    Invoke-PostgresCommand -Sql ($statements -join ' ')

    $userCount = [int](Invoke-PostgresScalar -Sql "SELECT count(*) FROM users WHERE username='$safeUsername';")
    $chunkCount = 0
    if ($smokeUgcId -and $smokeUgcId -gt 0) {
        $chunkCount = [int](Invoke-PostgresScalar -Sql (
            "SELECT count(*) FROM embedding_chunk WHERE doc_ref_type='UGC' AND doc_ref_id=$smokeUgcId;"))
    }
    $markerCount = 0
    if (-not [string]::IsNullOrWhiteSpace($safeMarker)) {
        $markerCount = [int](Invoke-PostgresScalar -Sql (
            "SELECT count(*) FROM embedding_chunk WHERE doc_ref_type='UGC' " +
            "AND position('$safeMarker' in chunk_text) > 0;"))
    }
    if ($userCount -ne 0 -or $chunkCount -ne 0 -or $markerCount -ne 0) {
        throw "Smoke-test data cleanup incomplete (users=$userCount, chunks=$chunkCount, markerChunks=$markerCount)."
    }
}

function Restore-ManagedEnvironment {
    foreach ($name in $managedEnvironmentNames) {
        [Environment]::SetEnvironmentVariable(
            $name,
            $originalEnvironment[$name],
            'Process')
    }
}

Push-Location $repoRoot
$locationPushed = $true
try {
    if ([string]::IsNullOrWhiteSpace($env:DB_PASSWORD)) {
        throw 'Set DB_PASSWORD in the current shell before running the smoke test.'
    }
    $suffix = [Guid]::NewGuid().ToString('N').Substring(0, 12)
    $username = "smoke-$suffix"
    $smokeUsername = $username
    $password = [Guid]::NewGuid().ToString('N') + 'Aa1!'
    $marker = "campus-smoke-$suffix"
    $smokeMarker = $marker

    Write-Step 'Start PostgreSQL with Docker Compose'
    $env:POSTGRES_PORT = [string]$DbPort
    Invoke-BoundedCommand `
        -FilePath 'docker' `
        -Arguments @('compose', 'up', '-d', 'postgres') `
        -TimeoutSeconds $StartupTimeoutSeconds
    Wait-Postgres

    Write-Step 'Run Flyway migrations'
    $flywayUrl = "jdbc:postgresql://127.0.0.1:$DbPort/college"
    Invoke-BoundedCommand `
        -FilePath (Join-Path $repoRoot 'mvnw.cmd') `
        -Arguments @(
            '-q',
            "-Dflyway.url=$flywayUrl",
            '-Dflyway.user=college',
            'flyway:migrate') `
        -TimeoutSeconds $StartupTimeoutSeconds

    Write-Step 'Start backend'
    $env:DB_URL = $flywayUrl
    $env:DB_USERNAME = 'college'
    $env:SERVER_PORT = [string]$backendUri.Port
    $env:JWT_SECRET = [Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N')
    $backendStdout = Join-Path $env:TEMP "college-assistant-smoke-$suffix.out.log"
    $backendStderr = Join-Path $env:TEMP "college-assistant-smoke-$suffix.err.log"
    Assert-BackendPortAvailable
    $backendProcess = Start-Process `
        -FilePath (Join-Path $repoRoot 'mvnw.cmd') `
        -ArgumentList @('-q', '-DskipTests', 'spring-boot:run') `
        -WorkingDirectory $repoRoot `
        -RedirectStandardOutput $backendStdout `
        -RedirectStandardError $backendStderr `
        -WindowStyle Hidden `
        -PassThru
    Wait-Backend

    Write-Step 'Register smoke user'
    $smokeRegistrationAttempted = $true
    $registration = Invoke-Api -Method POST -Path '/api/auth/register' -Body @{
        username = $username
        password = $password
        displayName = 'Smoke Test User'
    } -Token $null
    $studentToken = [string]$registration.accessToken
    if ([string]::IsNullOrWhiteSpace($studentToken)) {
        throw 'Registration response did not contain accessToken.'
    }

    Write-Step 'Update user profile'
    $null = Invoke-Api -Method PUT -Path '/api/profile' -Body @{
        major = 'Computer Science'
        grade = 2026
        campus = 'West Campus'
        degree = 'UNDERGRADUATE'
        college = 'School of Computing'
        lifecycleStage = 'ENROLLED'
        courseRecords = @()
    } -Token $studentToken

    Write-Step 'Submit UGC'
    $ugc = Invoke-Api -Method POST -Path '/api/ugc' -Body @{
        title = "Smoke knowledge $suffix"
        content = "The verification phrase is $marker. This content exists only for the local smoke test."
        tags = @('smoke', 'verification')
        relatedCourse = $null
        relatedLocation = $null
    } -Token $studentToken
    $ugcId = [long]$ugc.id
    $smokeUgcId = $ugcId
    if ($ugcId -le 0) {
        throw 'UGC response did not contain a valid id.'
    }

    Write-Step 'Promote smoke user to ADMIN and login again'
    $safeUsername = $username.Replace("'", "''")
    $updated = Invoke-PostgresScalar -Sql (
        "UPDATE users SET role='ADMIN', token_version=token_version + 1 " +
        "WHERE username='$safeUsername' RETURNING id;")
    if ([string]::IsNullOrWhiteSpace($updated)) {
        throw 'Failed to promote the smoke user to ADMIN.'
    }
    $adminLogin = Invoke-Api -Method POST -Path '/api/auth/login' -Body @{
        username = $username
        password = $password
    } -Token $null
    $adminToken = [string]$adminLogin.accessToken
    if ([string]::IsNullOrWhiteSpace($adminToken)) {
        throw 'Admin login response did not contain accessToken.'
    }

    Write-Step 'Approve UGC'
    $null = Invoke-Api -Method POST -Path "/api/admin/ugc/$ugcId/review" -Body @{
        decision = 'APPROVE'
        reason = $null
    } -Token $adminToken

    Write-Step 'Wait for approved UGC to enter the vector index'
    Wait-UgcIndex -UgcId $ugcId

    Write-Step 'Call Chat SSE'
    $chat = Invoke-SseChat `
        -Token $adminToken `
        -Question "What is the verification phrase ${marker}?" `
        -ExpectedUgcId $ugcId `
        -ExpectedMarker $marker
    $answerPreview = $chat.Answer
    if ($answerPreview.Length -gt 240) {
        $answerPreview = $answerPreview.Substring(0, 240) + '...'
    }

    $smokeSummary = [pscustomobject]@{
        Username = $username
        UgcId = $ugcId
        SessionId = $chat.SessionId
        AnswerStatus = $chat.AnswerStatus
        SourceCount = $chat.SourceCount
        AnswerPreview = $answerPreview
    }
} catch {
    $runFailure = $_
} finally {
    try {
        Stop-BackendProcessTree
    } catch {
        $cleanupFailures.Add("Could not stop backend process tree: $($_.Exception.Message)")
    }
    try {
        Remove-SmokeData
    } catch {
        $cleanupFailures.Add("Could not remove smoke-test data: $($_.Exception.Message)")
    }
    try {
        Restore-ManagedEnvironment
    } catch {
        $cleanupFailures.Add("Could not restore process environment: $($_.Exception.Message)")
    }
    if ($locationPushed) {
        try {
            Pop-Location
        } catch {
            $cleanupFailures.Add("Could not restore working directory: $($_.Exception.Message)")
        }
    }
    if ($null -eq $runFailure -and $cleanupFailures.Count -eq 0) {
        try {
            foreach ($logPath in @($backendStdout, $backendStderr)) {
                if ($logPath -and (Test-Path -LiteralPath $logPath)) {
                    Remove-Item -LiteralPath $logPath -Force -ErrorAction Stop
                }
            }
        } catch {
            $cleanupFailures.Add("Could not remove temporary backend logs: $($_.Exception.Message)")
        }
    }
}

if ($runFailure) {
    Write-Error -ErrorRecord $runFailure -ErrorAction Continue
}
foreach ($cleanupFailure in $cleanupFailures) {
    Write-Error -Message $cleanupFailure -ErrorAction Continue
}
if ($runFailure -or $cleanupFailures.Count -gt 0) {
    if ($backendStdout) { Write-Host "Backend stdout: $backendStdout" }
    if ($backendStderr) { Write-Host "Backend stderr: $backendStderr" }
    exit 1
}

Write-Host "`nSmoke test passed." -ForegroundColor Green
$smokeSummary | Format-List
