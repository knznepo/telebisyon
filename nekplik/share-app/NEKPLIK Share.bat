@echo off
rem NEKPLIK Share for Windows: double-click it, choose the folder with your movies, and it shares them
rem with NEKPLIK on the TV until this window is closed. Needs Python 3 (python.org) and share.py, one
rem folder up from this file.
title NEKPLIK Share
setlocal
set "HERE=%~dp0"
set "SHARER=%HERE%..\share.py"
if not exist "%SHARER%" set "SHARER=%HERE%share.py"

rem Python 3: the "py" launcher python.org installs, else "python".
set "PY="
where py >nul 2>nul && set "PY=py -3"
if not defined PY where python >nul 2>nul && set "PY=python"
if not defined PY (
  echo NEKPLIK Share needs Python 3. Opening its download page: install it, tick "Add python.exe to PATH",
  echo then double-click NEKPLIK Share again.
  start "" "https://www.python.org/downloads/"
  pause
  exit /b 1
)

rem The folder shared last time, or a new one chosen in a window.
set "SAVED=%APPDATA%\nekplik-share-folder.txt"
set "FOLDER="
if exist "%SAVED%" set /p FOLDER=<"%SAVED%"
if defined FOLDER if exist "%FOLDER%\" (
  choice /c YN /m "Share %FOLDER% again"
  if errorlevel 2 set "FOLDER="
) else set "FOLDER="
if not defined FOLDER (
  for /f "usebackq delims=" %%F in (`powershell -NoProfile -Command "Add-Type -AssemblyName System.Windows.Forms; $d = New-Object System.Windows.Forms.FolderBrowserDialog; $d.Description = 'Choose the folder with your movies: the one with Movies and Series in it, or a Movies folder.'; if ($d.ShowDialog() -eq 'OK') { $d.SelectedPath }"`) do set "FOLDER=%%F"
)
if not defined FOLDER exit /b 0
> "%SAVED%" echo %FOLDER%

echo.
echo If Windows asks whether Python may use the network, allow Private networks.
echo Close this window to stop sharing. Keep the computer from sleeping while you watch.
echo.
%PY% "%SHARER%" "%FOLDER%"
pause
