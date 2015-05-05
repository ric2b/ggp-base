@echo off
perl fetch_snap_log.pl %1 junit
perl fetch_snap_log.pl %1 logs
pushd %TEMP%
7z x -y junit.tgz
rd /s /q junit
7z x -y junit.tar
7z x -y logs.tgz
rd /s /q logs
7z x -y logs.tar
junit\index.html
