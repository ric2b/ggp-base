@echo off
setlocal
rd /s /q oneshot >NUL 2>&1
java -cp bin;bin\external\Guava\guava-14.0.1.jar org.ggp.base.apps.utilities.GameServerRunner oneshot %* >server.log 2>&1
