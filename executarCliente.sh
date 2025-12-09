#!/bin/bash

# Caminho do JAR do JGroups
JGROUPS_JAR="lib/jgroups-4.2.20.Final.jar"

# Caminho das classes compiladas
CLASSPATH="bin:$JGROUPS_JAR"

# Iniciar ServidorDados em um terminal separado
gnome-terminal --title="ServidorDados" -- bash -c "java -cp \"$CLASSPATH\" dados.ServidorDados; exec bash"

# Iniciar ServidorControle em um terminal separado
gnome-terminal --title="ServidorControle" -- bash -c "java -cp \"$CLASSPATH\" controle.ServidorControle; exec bash"

# Iniciar Cliente em outro terminal separado
gnome-terminal --title="Cliente" -- bash -c "java -cp \"$CLASSPATH\" gateway.Cliente; exec bash"
