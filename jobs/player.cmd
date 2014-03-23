@echo off
setlocal

REM Purpose: Launch a player
REM
REM Parameters
REM
REM %1  - File for stdout & stderr
REM %2  - Port
REM %3  - Player class
REM %4+ - Optional player-specific configuration

start cmd.exe /C "java -cp bin;bin\external\reflections\reflections-0.9.9-RC1.jar;bin\external\Guava\guava-14.0.1.jar;bin\external\javassist\javassist.jar;bin\external\JUnit\junit-4.11.jar org.ggp.base.apps.player.PlayerRunner %2 %3 %4 %5 %6 %7 %8 %9 >%1 2>&1"
