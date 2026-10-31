param([switch]$CheckModels)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
Push-Location $taskRoot
try {
    & "$PSScriptRoot/prepare-models.ps1" -CheckOnly:$CheckModels
    $manifest = Get-Content models/manifest.json -Encoding UTF8 -Raw | ConvertFrom-Json
    $path = 'third_party/mecab'
    if (!(Test-Path -LiteralPath "$path/.revision")) {
        if (Test-Path -LiteralPath $path) { throw 'Unverified MeCab source directory; preserve it before rebuilding' }
        New-Item -ItemType Directory -Path '.cache/mecab-source-rebuild' -Force | Out-Null
        $archive = '.cache/mecab-source-rebuild/source.zip'
        & curl.exe --silent --show-error --fail --location --retry 4 --output $archive "https://github.com/taku910/mecab/archive/$($manifest.mecabRevision).zip"
        if ($LASTEXITCODE -ne 0 -or (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $manifest.mecabArchiveSha256) { throw 'MeCab source verification failed' }
        Expand-Archive -LiteralPath $archive -DestinationPath '.cache/mecab-source-rebuild' -Force
        Copy-Item -LiteralPath ".cache/mecab-source-rebuild/mecab-$($manifest.mecabRevision)" -Destination $path -Recurse
        Set-Content -LiteralPath "$path/.revision" -Value $manifest.mecabRevision -NoNewline
    }
    if ((Get-Content -LiteralPath "$path/.revision" -Raw) -ne $manifest.mecabRevision) { throw 'MeCab revision mismatch' }
    $env:CI = 'true'
    & flutter build apk --release --target-platform android-arm64
    if ($LASTEXITCODE -ne 0) { throw 'APK build failed' }
} finally { Pop-Location }

