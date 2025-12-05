package dados;

import java.io.Serializable;

public class Arquivo implements Serializable {
    private static final long serialVersionUID = 1L;

    private String uid;
    private String nome;
    private byte[] conteudo;
    private String nomeUsuario;

    public Arquivo(String uid, String nome, byte[] conteudo, String nomeUsuario) {
        this.uid = uid;
        this.nome = nome;
        this.conteudo = conteudo;
        this.nomeUsuario = nomeUsuario;
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
    @Override
    public String toString() {
        return "Arquivo[uid=" + uid + ", nome=" + nome + ", usuario=" + nomeUsuario + "]";
    }
}