@echo off
REM PolyLingua Edge - one-command release build (Windows 10/11).
REM   build_apk.bat            build release-output\PolyLingua_POCO_M4_Pro_Release.apk
REM   build_apk.bat --install  ...and install it on a USB-connected phone with adb
REM Requires JDK 17+, Android SDK (platform 34, build-tools 34), NDK r26b+ (27.2.12479018
REM recommended), CMake 3.22.1, PowerShell 5+. Set KEYSTORE_PASSWORD to sign with the same
REM release key as CI (needs Python 3 + "pip install cryptography"); otherwise debug-signed.
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"
set "APK_NAME=PolyLingua_POCO_M4_Pro_Release.apk"

echo ==^> Checking JDK
where java >nul 2>nul || (echo [ERROR] java not found - install JDK 17 ^(Temurin 17^). & exit /b 1)
set "JAVA_MAJOR="
for /f "tokens=3" %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do set "JAVA_VER=%%~v"
for /f "tokens=1,2 delims=." %%a in ("%JAVA_VER%") do (if "%%a"=="1" (set "JAVA_MAJOR=%%b") else (set "JAVA_MAJOR=%%a"))
if not defined JAVA_MAJOR (echo [ERROR] Could not read the Java version. & exit /b 1)
if %JAVA_MAJOR% LSS 17 (echo [ERROR] JDK %JAVA_MAJOR% found; JDK 17 or newer is required. & exit /b 1)
echo JDK %JAVA_MAJOR%

echo ==^> Locating the Android SDK
set "SDK=%ANDROID_HOME%"
if not defined SDK set "SDK=%ANDROID_SDK_ROOT%"
if not defined SDK if exist "%LOCALAPPDATA%\Android\Sdk" set "SDK=%LOCALAPPDATA%\Android\Sdk"
if not defined SDK (echo [ERROR] Android SDK not found. Set ANDROID_HOME or install Android Studio. & exit /b 1)
if not exist "%SDK%" (echo [ERROR] Android SDK path "%SDK%" does not exist. & exit /b 1)
set "ANDROID_HOME=%SDK%"
if not exist local.properties (
  set "SDK_ESC=%SDK:\=\\%"
  echo sdk.dir=!SDK_ESC!> local.properties
)
echo SDK: %SDK%

set "SDKM=%SDK%\cmdline-tools\latest\bin\sdkmanager.bat"
call :need "platforms\android-34" "platforms;android-34" || exit /b 1
call :need "build-tools\34.0.0" "build-tools;34.0.0" || exit /b 1
call :need "cmake\3.22.1" "cmake;3.22.1" || exit /b 1

echo ==^> Selecting the NDK (r26b or newer)
set "NDK_VERSION="
if exist "%SDK%\ndk" (
  for /f "delims=" %%d in ('dir /b /ad /o:n "%SDK%\ndk"') do (
    for /f "tokens=1 delims=." %%m in ("%%d") do if %%m GEQ 26 set "NDK_VERSION=%%d"
  )
)
if not defined NDK_VERSION (
  call :need "ndk\27.2.12479018" "ndk;27.2.12479018" || exit /b 1
  set "NDK_VERSION=27.2.12479018"
)
echo NDK %NDK_VERSION%

echo ==^> Fetching native dependencies
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\fetch_native_deps.ps1 || (echo [ERROR] Native dependency download failed. & exit /b 1)

echo ==^> Preparing signing
if defined KEYSTORE_PASSWORD (
  python -c "import cryptography" 2>nul || python -m pip install --user --quiet "cryptography>=42"
  python scripts\derive_release_keystore.py keystore\polylingua-release.p12 || (echo [ERROR] Key derivation failed. & exit /b 1)
  echo Release key derived ^(same certificate as CI builds^).
) else (
  echo KEYSTORE_PASSWORD not set - using the debug signing key.
)

echo ==^> Building (Gradle assembleRelease + unit tests)
call gradlew.bat --no-daemon -Ppolylingua.ndkVersion=%NDK_VERSION% :app:assembleRelease :app:testReleaseUnitTest || (echo [ERROR] Gradle build failed. & exit /b 1)

echo ==^> Collecting the APK
if not exist release-output mkdir release-output
for %%f in (app\build\outputs\apk\release\*.apk) do copy /y "%%f" "release-output\%APK_NAME%" >nul
if exist "%SDK%\build-tools\34.0.0\apksigner.bat" call "%SDK%\build-tools\34.0.0\apksigner.bat" verify "release-output\%APK_NAME%"
certutil -hashfile "release-output\%APK_NAME%" SHA256
echo Done: %CD%\release-output\%APK_NAME%

if "%~1"=="--install" (
  echo ==^> Installing with adb
  "%SDK%\platform-tools\adb.exe" install -r "release-output\%APK_NAME%"
)
exit /b 0

:need
if exist "%SDK%\%~1" exit /b 0
if exist "%SDKM%" (
  echo Installing %~2 ...
  echo y| call "%SDKM%" --licenses >nul 2>nul
  call "%SDKM%" --install "%~2"
  exit /b 0
)
echo [ERROR] Missing %~2 and no sdkmanager found. Install it from Android Studio ^> SDK Manager.
exit /b 1
