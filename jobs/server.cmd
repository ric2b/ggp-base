@echo off
setlocal
cd ..\bin
rd /s /q oneshot
java -cp .;external\Guava\guava-14.0.1.jar org.ggp.base.apps.utilities.GameServerRunner oneshot %* 127.0.0.1 9147 ScriptedPlayer1 127.0.0.1 9148 ScriptedPlayer2
type oneshot\*.json
