@echo off
cd /d "%~dp0"
title FarAway - Minecraft 26.3
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "& { & '.\build.ps1' -Tasks @('runClient', '-PfarawayWorldGrid'); exit $LASTEXITCODE }"
if errorlevel 1 (
    echo.
    echo FarAway baslatilamadi. Hata ayrintilari yukarida gorunuyor.
    pause
)
