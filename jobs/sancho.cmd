@echo off
setlocal
cd ..\bin
:restart
call java -cp .;external\reflections\reflections-0.9.9-RC1.jar;external\Guava\guava-14.0.1.jar;external\javassist\javassist.jar;external\JUnit\junit-4.11.jar;external\jna\jna-platform-4.1.0.jar;external\jna\jna-4.1.0.jar;external\disruptor\disruptor-3.2.1.jar org.ggp.base.apps.player.PlayerRunner 9147 Sancho
goto restart
