@echo off
setlocal
call player 9147 ScriptedPlayer "mark 1 1,noop,mark 3 3,noop,mark 3 2,noop,mark 1 3,noop,mark 2 1"
call player 9148 ScriptedPlayer "noop,mark 2 2,noop,mark 1 2,noop,mark 3 1,noop,mark 2 3,noop"
call server games.ggp.org/stanford tictactoe 30 15 0
