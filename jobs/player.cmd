@echo off
setlocal
cd ..\bin
start java -cp .;external\reflections\reflections-0.9.9-RC1.jar;external\Guava\guava-14.0.1.jar;external\javassist\javassist.jar;external\JUnit\junit-4.11.jar org.ggp.base.apps.player.PlayerRunner %*
