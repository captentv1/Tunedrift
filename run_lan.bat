@echo off
cd /d "%~dp0"
git submodule update --init
python server\mfp_server.py --lan
pause
