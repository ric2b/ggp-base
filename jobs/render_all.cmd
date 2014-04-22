@echo off
del c:\temp\propnet*.dot.png
for %%i in (c:\temp\propnet*.dot) do ((echo Rendering %%i) & (call render %%i))
