@echo off
del %TEMP%\propnet*.dot.png
for %%i in (%TEMP%\propnet*.dot) do ((echo Rendering %%i) & (call render %%i))
