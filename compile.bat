@echo off
echo LIMPEZA COMPLETA DO PROJETO...

REM Apagar classes
del /s /q bin\*.class 2>nul

REM Apagar arquivos .bin em qualquer pasta
del /s /q *.bin 2>nul

REM Apagar metadados JGroups (muito importante!!)
del /s /q .jgroups* 2>nul

REM Apagar locks
del /s /q *.lock 2>nul
del /s /q *.tmp 2>nul

REM Apagar arquivos de dados persistidos (se existirem)
del /s /q dados\*.bin 2>nul
del /s /q storage\*.bin 2>nul

REM Recriar pasta bin
if not exist bin ( mkdir bin )

echo RECOMPILANDO...
javac -d bin -cp "lib\jgroups-4.2.20.Final.jar;." src\controle\*.java src\dados\*.java src\gateway\*.java src\util\*.java

echo LIMPEZA E COMPILACAO FINALIZADAS.
pause
