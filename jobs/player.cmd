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

start cmd.exe /C "java -XX:+UseG1GC -XX:MaxGCPauseMillis=800 -XX:+ExplicitGCInvokesConcurrent -cp
bin;bin\external\reflections\reflections-0.9.9-RC1.jar;bin\external\Guava\guava-14.0.1.jar;bin\external\javassist\javassist.jar;bin\external\JUnit\junit-4.11.jar;bin\external\jna\jna-platform-4.1.0.jar;bin\external\jna\jna-4.1.0.jar;bin\external\disruptor\disruptor-3.2.1.jar;bin\external\log4j\log4j-api-2.0-rc1.jar;bin\external\log4j\log4j-core-2.0-rc1.jar;bin\external\Commons\commons-codec-1.9\commons-codec-1.9.jar;bin\external\Commons\commons-compress-1.8.1\commons-compress-1.8.1.jar;bin\external\Log4J\log4j-core-2.0-rc1.jar;bin\external\Log4J\log4j-api-2.0-rc1.jar;bin\external\Lucene\lucene-core-4.8.1.jar org.ggp.base.apps.player.PlayerRunner %2 %3 %4 %5 %6 %7 %8 %9 >%1 2>&1"
