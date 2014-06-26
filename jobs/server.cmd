@echo off
setlocal
rd /s /q oneshot >NUL 2>&1
java -cp bin;bin\external\Guava\guava-14.0.1.jar;bin\external\jna\jna-platform-4.1.0.jar;bin\external\jna\jna-4.1.0.jar;bin\external\Commons\commons-codec-1.9\commons-codec-1.9.jar;bin\external\Commons\commons-compress-1.8.1\commons-compress-1.8.1.jar org.ggp.base.apps.utilities.GameServerRunner oneshot %* >server.log 2>&1
