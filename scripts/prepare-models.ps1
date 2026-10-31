param([switch]$CheckOnly)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
$manifest = Get-Content -LiteralPath "$taskRoot/models/manifest.json" -Encoding UTF8 -Raw | ConvertFrom-Json
foreach ($model in $manifest.models) {
    if ($model.directory -notmatch '^[\w-]+$') { throw 'Invalid model directory' }
    $destination = "$taskRoot/android/app/src/main/assets/models/$($model.directory)"
    New-Item -ItemType Directory -Path $destination -Force | Out-Null
    if (($model.files | Measure-Object bytes -Sum).Sum -ne $model.bytes) { throw 'Model registry size mismatch' }
    foreach ($entry in $model.files) {
        if ($entry.file -notmatch '^[\w.-]+$' -or $entry.sha256 -notmatch '^[a-f0-9]{64}$') { throw 'Invalid model entry' }
        $target = Join-Path $destination $entry.file
        if (!(Test-Path -LiteralPath $target)) {
            if ($CheckOnly) { throw "Missing $($entry.file)" }
            $partial = "$target.part"
            if ($entry.source -eq 'unidic-lite-1.0.8') {
                & python "$PSScriptRoot/prepare-unidic.py"
                if ($LASTEXITCODE -ne 0) { throw 'Dictionary preparation failed' }
                Copy-Item -LiteralPath "$taskRoot/.cache/unidic-runtime/$($entry.file)" -Destination $partial
            } else {
                $url = "https://huggingface.co/$($entry.repo)/resolve/$($entry.revision)/$($entry.path)"
                Write-Output "Downloading $($entry.file)"
                & curl.exe --silent --show-error --fail --location --retry 4 --continue-at - --output $partial $url
                if ($LASTEXITCODE -ne 0) { throw "Download failed: $($entry.file)" }
            }
            if ((Get-Item -LiteralPath $partial).Length -ne $entry.bytes -or (Get-FileHash -LiteralPath $partial -Algorithm SHA256).Hash.ToLowerInvariant() -ne $entry.sha256) { throw 'Downloaded model verification failed' }
            Move-Item -LiteralPath $partial -Destination $target
        }
        if ((Get-Item -LiteralPath $target).Length -ne $entry.bytes -or (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant() -ne $entry.sha256) { throw "Invalid model: $($entry.file)" }
        Write-Output "Verified $($entry.file)"
    }
}
Copy-Item -LiteralPath "$taskRoot/models/manifest.json" -Destination "$taskRoot/android/app/src/main/assets/model-manifest.json"

