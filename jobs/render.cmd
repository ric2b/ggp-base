@echo off
call dot -Goverlap=false -Goutputorder=nodesfirst -Tpng -Kneato -O %1
