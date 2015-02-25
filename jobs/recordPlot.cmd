@echo off
setlocal
set GREP_OPTIONS=
echo|set /P="YVALUE=" > logs\speedChess.properties
grep -P -oh -m 1 "Direct state machine rollouts per second: (\d+)" ..\builds\%BUILD_NUMBER%\log | grep -P -oh "\d+" >> speedChess.properties
