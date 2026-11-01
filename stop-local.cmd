@echo off
rem Keep this file ASCII-only; Chinese output comes from run.py.
chcp 65001 >nul
cd /d "%~dp0"
python -X utf8 tools\dev\run.py stop
if errorlevel 1 (
  echo.
  echo Stop failed. Fix the message above and retry.
  pause
  exit /b 1
)
exit /b 0
