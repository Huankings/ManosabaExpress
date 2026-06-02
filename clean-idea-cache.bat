@echo off
setlocal
cd /d "%~dp0"

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0clean-idea-cache.ps1" %*
set "EXIT_CODE=%ERRORLEVEL%"

echo.
if not "%EXIT_CODE%"=="0" (
  echo IDEA cache cleanup failed. Exit code: %EXIT_CODE%
  pause
  exit /b %EXIT_CODE%
)

echo IDEA cache cleanup finished.
echo Re-open the project in IntelliJ IDEA and wait for Gradle sync.
pause
exit /b 0
