@echo off
cd /d "%~dp0"
python -X utf8 tools\dev\run.py stop
if errorlevel 1 pause
