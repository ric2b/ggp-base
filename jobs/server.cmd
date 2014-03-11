@echo off
setlocal
cd ..\bin
rd /s /q oneshot >NUL 2>&1
java -cp .;external\Guava\guava-14.0.1.jar org.ggp.base.apps.utilities.GameServerRunner oneshot %* >NUL
