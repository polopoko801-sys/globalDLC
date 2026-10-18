@echo off
REM Uruchamia ThunderWatch - podglad kto uzywa klienta (haslo 2137)
cd /d "%~dp0"
if not exist ThunderWatch.jar call Build.bat
start "" javaw -jar ThunderWatch.jar
