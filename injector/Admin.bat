@echo off
REM Uruchamia Panel Admina (dla Ciebie, haslo 2137)
cd /d "%~dp0"
if not exist ThunderAdmin.jar call Build.bat
start "" javaw -jar ThunderAdmin.jar
