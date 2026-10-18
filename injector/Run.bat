@echo off
REM Uruchamia Injector (produkt dla kupujacego)
cd /d "%~dp0"
if not exist ThunderInjector.jar call Build.bat
start "" javaw -jar ThunderInjector.jar
