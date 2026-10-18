@echo off
REM Kompiluje Injector + Panel Admina + Watch + GlobalDLC App. Wbudowuje klienta do injectora i GlobalDLC App.
REM "Build.bat silent" - bez pause (dla launcherow .vbs)
setlocal enabledelayedexpansion
cd /d "%~dp0"
set "HOLD=pause"
if /i "%~1"=="silent" set "HOLD=rem"

echo [Gombal] Kompilacja...
if not exist out mkdir out
javac -encoding UTF-8 -d out src\*.java
if errorlevel 1 (
    echo [BLAD] Kompilacja nie powiodla sie.
    %HOLD%
    exit /b 1
)

REM --- wbuduj klienta (pomijajac -sources) do zasobow injectora i Gombal App ---
REM Jar nazywa sie GombalBeta-*.jar; stara nazwa thunderhack-*.jar tez lapana, zeby
REM build ze starszego checkoutu dalej dzialal.
REM Kolejnosc ma znaczenie: kopiowany jest po kolei kazdy pasujacy plik, wiec ostatni wygrywa -
REM dlatego stara nazwa leci pierwsza, a GombalBeta na koncu ja nadpisuje.
if exist out\client.jar del /q out\client.jar
for %%f in ("..\build\libs\thunderhack-*.jar") do (
    echo %%~nxf | findstr /i "sources" >nul || copy /y "%%f" "out\client.jar" >nul
)
for %%f in ("..\build\libs\GombalBeta-*.jar") do (
    echo %%~nxf | findstr /i "sources" >nul || copy /y "%%f" "out\client.jar" >nul
)
if exist out\client.jar (
    echo [OK] Wbudowano klienta: client.jar
) else (
    echo [UWAGA] Brak jara klienta w ..\build\libs - injector uzyje fallbacku.
)

REM Kazdy jar sprawdzany osobno: otwarta aplikacja trzyma swoj plik i jar sie na nim wywala.
REM Bez tego skrypt wypisywal stos bledu, a zaraz pod nim "[OK] Zbudowano" - czyli klamal.
set "FAILED="

echo Main-Class: ThunderInjector> out\mi.txt
jar cfm ThunderInjector.jar out\mi.txt -C out .
if errorlevel 1 set "FAILED=!FAILED! ThunderInjector.jar"

REM GlobalDLC App tez niesie klienta: stempluje go UID-em przy eksporcie.
echo Main-Class: GlobalDLCApp> out\mg.txt
jar cfm GlobalDLCApp.jar out\mg.txt -C out .
if errorlevel 1 set "FAILED=!FAILED! GlobalDLCApp.jar"

REM Panel admina i Watch nie potrzebuja klienta - usun go przed pakowaniem
if exist out\client.jar del /q out\client.jar
echo Main-Class: ThunderAdmin> out\ma.txt
jar cfm ThunderAdmin.jar out\ma.txt -C out .
if errorlevel 1 set "FAILED=!FAILED! ThunderAdmin.jar"

echo Main-Class: ThunderWatch> out\mwa.txt
jar cfm ThunderWatch.jar out\mwa.txt -C out .
if errorlevel 1 set "FAILED=!FAILED! ThunderWatch.jar"

echo.
if defined FAILED (
    echo [BLAD] Nie zapisano:!FAILED!
    echo        Plik jest uzywany - zamknij ta aplikacje i odpal build jeszcze raz.
    %HOLD%
    exit /b 1
)
echo [OK] Zbudowano ThunderInjector.jar i GlobalDLCApp.jar (z wbudowanym klientem), ThunderAdmin.jar oraz ThunderWatch.jar
%HOLD%
