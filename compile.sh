#!/bin/bash

echo "Limpando arquivos .class..."
rm -f bin/*.class 2>/dev/null
rm -f *.bin 2>/dev/null

# Cria pasta bin se não existir
mkdir -p bin

echo "Compilando projeto..."

javac -d bin \
    -cp "lib/jgroups-4.2.20.Final.jar:." \
    src/controle/*.java \
    src/dados/*.java \
    src/gateway/*.java \
    src/util/*.java

echo "✔ Compilação finalizada!"
read -p "Pressione ENTER para sair..."
