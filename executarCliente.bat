@echo off
start "ServidorDados" cmd /k java -cp "bin;lib\jgroups-4.2.20.Final.jar" dados.ServidorDados
start "ServidorControle" cmd /k java -cp "bin;lib\jgroups-4.2.20.Final.jar" controle.ServidorControle
start "Cliente" cmd /k java -cp "bin;lib\jgroups-4.2.20.Final.jar" gateway.Cliente
pause