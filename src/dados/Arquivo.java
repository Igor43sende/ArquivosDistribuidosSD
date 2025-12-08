package dados;

import java.io.Serializable;

public class Arquivo implements Serializable {
    private static final long serialVersionUID = 1L;

    private String uid;
    private String nome;
    private byte[] conteudo;
    private String nomeUsuario;

    // *** NOVO ***
    private boolean update = false;       // indica operação de UPDATE
    private long timestamp = 0;           // versão do arquivo

    public Arquivo(String uid, String nome, byte[] conteudo, String nomeUsuario) {
        this.uid = uid;
        this.nome = nome;
        this.conteudo = conteudo;
        this.nomeUsuario = nomeUsuario;
        // timestamp será atribuído no ServidorDados se ainda for zero
    }

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

    // *** NOVO *** — marca arquivo como UPDATE
    public void setUpdate(boolean update) {
        this.update = update;
    }

    public boolean isUpdate() {
        return update;
    }

    // *** NOVO *** — timestamp do arquivo
    public void setTimestamp(long ts) {
        this.timestamp = ts;
    }

    public long getTimestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        return "Arquivo[uid=" + uid +
                ", nome=" + nome +
                ", usuario=" + nomeUsuario +
                ", timestamp=" + timestamp +
                ", update=" + update + "]";
    }
}
