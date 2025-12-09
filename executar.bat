@echo off
start "ServidorDados" cmd /k java -cp "bin;lib\jgroups-4.2.20.Final.jar" dados.ServidorDados
start "ServidorControle" cmd /k java -cp "bin;lib\jgroups-4.2.20.Final.jar" controle.ServidorControle
start "Gateway" cmd /k java -cp "bin;lib\jgroups-4.2.20.Final.jar" gateway.MainGateway

echo.
echo 🔍 Aguardando Gateway iniciar...

:espera_gateway
netstat -an | find "1099" >nul
if %errorlevel%==0 goto iniciado
timeout /t 1 >nul
goto espera_gateway

:iniciado
echo Gateway iniciado!

start "Cliente" cmd /k java -cp "bin;lib\jgroups-4.2.20.Final.jar" gateway.Cliente
pause
