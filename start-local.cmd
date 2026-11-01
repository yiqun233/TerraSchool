@echo off
rem Keep this file ASCII-only: cmd.exe parses .cmd in the OEM code page.
chcp 65001 >nul
cd /d "%~dp0"
echo Starting TeraSchool local environment (first run migrates DB and builds, may take minutes)...
python -X utf8 tools\dev\run.py start %*
set RC=%ERRORLEVEL%
if "%RC%"=="3" goto already
if "%RC%"=="2" goto failed
if "%RC%"=="1" goto failed
echo.
echo Service ended (Ctrl+C or window closed). Database and data are preserved.
pause
exit /b 0
:already
echo.
echo Already running; the page was opened in your browser.
echo To restart: stop-local.cmd, then run this script again.
echo This window can be closed; closing it does not stop the running services.
pause
exit /b 0
:failed
echo.
echo Start failed. Fix the message above and retry.
echo Logs: .local\dev\ (backend.log, backend-build.log, frontend.log)
pause
exit /b 1
