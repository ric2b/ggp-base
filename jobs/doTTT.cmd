@echo off
setlocal
del record.json
call player 9147 Sancho
call player 9148 ScriptedPlayer "noop"
call server games.ggp.org/stanford tictactoe 15 15 1 127.0.0.1 9147 ScriptedPlayer1 127.0.0.1 9148 ScriptedPlayer2
copy ..\bin\oneshot\*.json record.json
check_move.pl 0 ";mark 1 1;mark 1 3;mark 3 1;mark 3 3;" record.json
