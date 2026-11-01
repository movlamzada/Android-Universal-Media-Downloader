@echo off
setlocal
cd /d "%~dp0"
echo ================================================
echo Universal Media Downloader - Android APK Build
echo ================================================
echo.
echo This build needs Android SDK Platform 36, JDK 17+ (JDK 21 recommended), and Python 3.10-3.15.
echo The project automatically detects the Python selected by "py -3". No pip packages are required.
echo The first run downloads Gradle and project dependencies.
echo.
py -3 --version
if errorlevel 1 (
    echo ERROR: Windows Python Launcher was not found. Make sure "py -3 --version" works.
    pause
    exit /b 1
)
echo.
call "%~dp0gradlew.bat" --no-daemon :app:assembleDebug
if errorlevel 1 (
    echo.
    echo BUILD FAILED. Read the error above.
    pause
    exit /b 1
)
echo.
echo BUILD SUCCESSFUL.
copy /Y "%~dp0app\build\outputs\apk\debug\app-debug.apk" "%~dp0UniversalMediaDownloader.apk" >nul
echo APK: %~dp0UniversalMediaDownloader.apk
explorer.exe /select,"%~dp0UniversalMediaDownloader.apk"
pause
