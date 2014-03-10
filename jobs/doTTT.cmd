@echo off
setlocal
del record.json
call player 9147 Sancho
call player 9148 ScriptedPlayer "noop"
call server games.ggp.org/stanford tictactoe 15 15 1
copy ..\bin\oneshot\*.json record.json
check_move.pl 0 ";move 1 1;move 1 3;move 3 1;move 3 3;" record.json
