# Windows equivalent of scripts/fetch_native_deps.sh (PowerShell 5.1+, Windows 10 1803+ for tar.exe).
$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

$Root = Split-Path -Parent $PSScriptRoot
$SherpaVersion = if ($env:SHERPA_VERSION) { $env:SHERPA_VERSION } else { "1.13.8" }
$LlamaTag = if ($env:LLAMA_TAG) { $env:LLAMA_TAG } else { "b12000" }
$OrtHeaderTag = if ($env:ORT_HEADER_TAG) { $env:ORT_HEADER_TAG } else { "v1.22.0" }

$ThirdParty = Join-Path $Root "app\src\main\cpp\third_party"
$JniDir = Join-Path $Root "app\src\main\jniLibs\arm64-v8a"
$Cache = Join-Path $Root ".deps-cache"
New-Item -ItemType Directory -Force -Path (Join-Path $ThirdParty "sherpa-onnx"), (Join-Path $ThirdParty "onnxruntime"), $JniDir, $Cache | Out-Null

function Get-File([string]$Url, [string]$Out) {
    if ((Test-Path $Out) -and ((Get-Item $Out).Length -gt 0)) { Write-Host "  cached  $(Split-Path -Leaf $Out)"; return }
    Write-Host "  fetch   $Url"
    for ($i = 1; $i -le 5; $i++) {
        try { Invoke-WebRequest -Uri $Url -OutFile "$Out.part" -UseBasicParsing; Move-Item -Force "$Out.part" $Out; return }
        catch { if ($i -eq 5) { throw }; Start-Sleep -Seconds 3 }
    }
}

Write-Host "[1/3] sherpa-onnx v$SherpaVersion prebuilt Android libraries"
$Tarball = Join-Path $Cache "sherpa-onnx-v$SherpaVersion-android.tar.bz2"
Get-File "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$SherpaVersion/sherpa-onnx-v$SherpaVersion-android.tar.bz2" $Tarball
if (-not (Test-Path (Join-Path $JniDir "libsherpa-onnx-c-api.so")) -or -not (Test-Path (Join-Path $JniDir "libonnxruntime.so"))) {
    tar -xjf $Tarball -C $Cache ./jniLibs/arm64-v8a/libsherpa-onnx-c-api.so ./jniLibs/arm64-v8a/libonnxruntime.so
    if ($LASTEXITCODE -ne 0) { throw "tar extraction failed" }
    Copy-Item (Join-Path $Cache "jniLibs\arm64-v8a\libsherpa-onnx-c-api.so") $JniDir -Force
    Copy-Item (Join-Path $Cache "jniLibs\arm64-v8a\libonnxruntime.so") $JniDir -Force
}

Write-Host "[2/3] C API headers"
Get-File "https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/v$SherpaVersion/sherpa-onnx/c-api/c-api.h" (Join-Path $ThirdParty "sherpa-onnx\c-api.h")
Get-File "https://raw.githubusercontent.com/microsoft/onnxruntime/$OrtHeaderTag/include/onnxruntime/core/session/onnxruntime_c_api.h" (Join-Path $ThirdParty "onnxruntime\onnxruntime_c_api.h")

Write-Host "[3/3] llama.cpp $LlamaTag"
$LlamaDir = Join-Path $ThirdParty "llama.cpp"
if (-not (Test-Path (Join-Path $LlamaDir "CMakeLists.txt"))) {
    if (Test-Path $LlamaDir) { Remove-Item -Recurse -Force $LlamaDir }
    git -c advice.detachedHead=false clone --depth 1 --branch $LlamaTag https://github.com/ggml-org/llama.cpp $LlamaDir
    if ($LASTEXITCODE -ne 0) { throw "git clone llama.cpp failed" }
} else { Write-Host "  cached  llama.cpp" }

Write-Host "Native dependencies ready."
Get-ChildItem $JniDir
