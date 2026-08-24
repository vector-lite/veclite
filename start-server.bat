@echo off
title VecLite Standalone Server
echo ========================================================
echo          Starting VecLite Web Service on :8080
echo ========================================================
echo.
cd /d "%~dp0"
call gradlew.bat bootRun
pause
