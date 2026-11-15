@echo off
cd /d "%~dp0"
git submodule update --init
python server\app.py --lan
pause
