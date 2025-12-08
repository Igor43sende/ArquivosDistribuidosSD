@echo off
start "ServidorControle" cmd /k java -cp "bin;lib\jgroups-4.2.20.Final.jar" controle.ServidorControle
start "ServidorDados" cmd /k java -cp "bin;lib\jgroups-4.2.20.Final.jar" dados.ServidorDados
start "Gateway" cmd /k java -cp "bin;lib\jgroups-4.2.20.Final.jar" gateway.MainGateway
start "Cliente" cmd /k java -cp "bin;lib\jgroups-4.2.20.Final.jar" gateway.Cliente
pause
