# Build libvpn_engine.so for Android (arm64-v8a, x86_64) and copy into jniLibs.
$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $PSScriptRoot
$Engine = Join-Path $Root "native\vpn-engine"
$JniLibs = Join-Path $Root "app\src\main\jniLibs"

$Sdk = $env:ANDROID_SDK_ROOT
if (-not $Sdk) { $Sdk = $env:ANDROID_HOME }
if (-not $Sdk) { $Sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk" }

$NdkVersions = Get-ChildItem (Join-Path $Sdk "ndk") -Directory -ErrorAction SilentlyContinue |
    Sort-Object Name -Descending
if (-not $NdkVersions) {
    throw "Android NDK not found under $Sdk\ndk. Install NDK via Android Studio SDK Manager."
}
$Ndk = $NdkVersions[0].FullName
$env:ANDROID_NDK_HOME = $Ndk
Write-Host "Using NDK: $Ndk"

$CargoBin = Join-Path $env:USERPROFILE ".cargo\bin"
$env:Path = "$CargoBin;$env:Path"

rustup target add aarch64-linux-android x86_64-linux-android | Out-Host

$Api = 26
$Toolchain = Join-Path $Ndk "toolchains\llvm\prebuilt\windows-x86_64"
$Clang = Join-Path $Toolchain "bin"

$targets = @(
    @{ Triple = "aarch64-linux-android"; Abi = "arm64-v8a"; Cc = "aarch64-linux-android$Api-clang.cmd" },
    @{ Triple = "x86_64-linux-android"; Abi = "x86_64"; Cc = "x86_64-linux-android$Api-clang.cmd" }
)

Push-Location $Engine
try {
    foreach ($t in $targets) {
        $cc = Join-Path $Clang $t.Cc
        if (-not (Test-Path $cc)) {
            # Some NDKs ship without .cmd
            $cc = Join-Path $Clang ($t.Cc -replace '\.cmd$','')
        }
        $env:CC = $cc
        $env:AR = Join-Path $Clang "llvm-ar.exe"
        $env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER = $(if ($t.Triple -eq "aarch64-linux-android") { $cc } else { $env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER })
        $env:CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER = $(if ($t.Triple -eq "x86_64-linux-android") { $cc } else { $env:CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER })

        if ($t.Triple -eq "aarch64-linux-android") {
            $env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER = $cc
        } else {
            $env:CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER = $cc
        }

        Write-Host "Building $($t.Triple) ..."
        cargo build --release --target $t.Triple
        if ($LASTEXITCODE -ne 0) { throw "cargo build failed for $($t.Triple)" }

        $outDir = Join-Path $JniLibs $t.Abi
        New-Item -ItemType Directory -Force -Path $outDir | Out-Null
        $src = Join-Path $Engine "target\$($t.Triple)\release\libvpn_engine.so"
        Copy-Item $src (Join-Path $outDir "libvpn_engine.so") -Force
        Write-Host "Installed $($t.Abi)/libvpn_engine.so"
    }
}
finally {
    Pop-Location
}

Write-Host "Native libraries ready under app\src\main\jniLibs"
