@echo off
perl fetch_snap_log.pl %1 %2 junit
perl fetch_snap_log.pl %1 %2 logs
pushd %TEMP%
7z x -y junit.tgz
rd /s /q junit
7z x -y junit.tar
7z x -y logs.tgz
rd /s /q logs
7z x -y logs.tar
ren junit junit.%1.%2
ren logs logs.%1.%2
junit.%1.%2\index.html
