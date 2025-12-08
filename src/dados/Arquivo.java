package dados;

import java.io.Serializable;

// Classe que representa um arquivo dentro do cluster de DADOS.
// Ela é serializável porque precisa trafegar entre servidores via JGroups
// e também pode ser gravada em disco.
//
// Contém:
//  - UID único do arquivo
//  - Nome original enviado pelo usuário
//  - Conteúdo em bytes
//  - Nome do usuário responsável
//
// *** Novos campos ***
//  - update: indica se esta operação é uma atualização de um arquivo existente
//  - timestamp: versão temporal usada para ordenação e resolução de conflitos
public class Arquivo implements Serializable {
    private static final long serialVersionUID = 1L;

    private String uid;          // Identificador único do arquivo
    private String nome;         // Nome que será exibido ao usuário
    private byte[] conteudo;     // Conteúdo real do arquivo (download)
    private String nomeUsuario;  // Quem fez upload/alteração

    // Marcadores adicionais usados pelo mecanismo de UPDATE distribuído:
    private boolean update = false;   // true se esta mensagem representa atualização
    private long timestamp = 0;       // versão do arquivo, definida no ServidorDados

    // Construtor principal: recebe metadados e conteúdo do arquivo.
    // O timestamp será atribuído mais tarde pelo ServidorDados.
    public Arquivo(String uid, String nome, byte[] conteudo, String nomeUsuario) {
        this.uid = uid;
        this.nome = nome;
        this.conteudo = conteudo;
        this.nomeUsuario = nomeUsuario;
    }

    // ---------- Getters ----------
    public String getUid() {
        return uid;
    }

    public String getNome() {
        return nome;
    }

    public byte[] getConteudo() {
        return conteudo;
    }

    public String getUsuario() {
        return nomeUsuario;
    }

    // *** UPDATE DISTRIBUÍDO ***
    // Esse campo indica que o arquivo é uma atualização.
    // Ele permite que o ServidorDados trate esse objeto de forma especial:
    // substituindo conteúdo, preservando nome e controlando versões.
    public void setUpdate(boolean update) {
        this.update = update;
    }

    public boolean isUpdate() {
        return update;
    }

    // *** CONTROLE DE VERSÃO DO ARQUIVO ***
    // O timestamp é usado para decidir qual atualização é mais recente
    // quando múltiplos nós enviam updates concorrentes.
    public void setTimestamp(long ts) {
        this.timestamp = ts;
    }

    public long getTimestamp() {
        return timestamp;
    }

    // Representação textual do arquivo.
    // Essencial para logs, debug, cálculo de hash e mensagens internas.
    @Override
    public String toString() {
        return "Arquivo[uid=" + uid +
                ", nome=" + nome +
                ", usuario=" + nomeUsuario +
                ", timestamp=" + timestamp +
                ", update=" + update + "]";
    }
}
