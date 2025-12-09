📌 ArquivosDistribuidosSD – Sistema Distribuído com JGroups
📝 Descrição do Projeto

Este projeto implementa um Sistema Distribuído utilizando Java + JGroups, composto por três principais módulos:

ServidorControle

ServidorDados

Gateway

Cliente

A comunicação entre processos ocorre por meio de grupos JGroups, garantindo confiabilidade, replicação e tolerância a falhas.

📁 Estrutura do Projeto
src/
 ├── controle/      → ServidorControle (gerência de operações)
 ├── dados/         → ServidorDados (persistência distribuída)
 ├── gateway/       → Gateway / Cliente
 ├── util/          → Utilidades auxiliares
bin/                 → Saída das classes compiladas
lib/jgroups.jar      → Biblioteca de comunicação distribuída

🚀 Como compilar o projeto

Execute:

compilar_tudo.bat


Ele irá:

✔ Remover arquivos antigos
✔ Criar a pasta bin
✔ Compilar todos os módulos

▶ Como executar o sistema distribuído

Após compilar, execute:

executar.bat


Isso abrirá 4 janelas:

ServidorControle

ServidorDados

Gateway

Cliente

Cada módulo comunica-se via canais do JGroups 4.2.20.Final.

📦 Dependências

Java 8+

jgroups-4.2.20.Final.jar

✨ Funcionalidades Gerais

Comunicação distribuída entre múltiplos servidores

Replicação de mensagens entre nós via JGroups

Gateway atuando como mediador entre cliente e servidores

Cliente interativo para envio de comandos

Separação de funções por módulos distribuídos

👨‍💻 Autor

Projeto desenvolvido para disciplina de Sistemas Distribuídos, pelos desenvolvedores Igor Resende Brito, Iury Dias Ribeiro e Tiago José Nery.
