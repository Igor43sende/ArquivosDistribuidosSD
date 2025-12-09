#!/bin/bash

# Abre cada servidor em um terminal separado
gnome-terminal -- bash -c "java -cp 'bin:lib/jgroups-4.2.20.Final.jar' dados.ServidorDados; exec bash"
gnome-terminal -- bash -c "java -cp 'bin:lib/jgroups-4.2.20.Final.jar' controle.ServidorControle; exec bash"
gnome-terminal -- bash -c "java -cp 'bin:lib/jgroups-4.2.20.Final.jar' gateway.MainGateway; exec bash"

echo
echo "🔍 Aguardando Gateway iniciar na porta 1099..."

# Loop até porta 1099 ficar ativa
while ! netstat -tuln 2>/dev/null | grep -q ":1099"; do
    sleep 1
done

echo "Gateway iniciado!"

# Abrir cliente
gnome-terminal -- bash -c "java -cp 'bin:lib/jgroups-4.2.20.Final.jar' gateway.Cliente; exec bash"

read -p "Pressione ENTER para sair..."
