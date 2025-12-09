@echo off
del /s /q bin\*.class 2>nul
del /s /q *.bin 2>nul
if not exist bin ( mkdir bin )
javac -d bin -cp "lib\jgroups-4.2.20.Final.jar;." src\controle\*.java src\dados\*.java src\gateway\*.java src\util\*.java
pause
