package controle;

import java.io.Serializable;
import java.util.UUID;

public class Usuario implements Serializable {

    private UUID uid;
    private String nome;
    private String senha;
    private boolean online;

    public Usuario(String nome, String senha) {
        this.uid = UUID.randomUUID();
        this.nome = nome;
        this.senha = senha;
        this.online = false;
    }

    public UUID getUid() { return uid; }
    public String getNome() { return nome; }
    public String getSenha() { return senha; }
    public boolean isOnline() { return online; }
    public void setOnline(boolean online) { this.online = online; }

    @Override
    public String toString() {
        return uid.toString() + ":" + nome + ":" + (online ? "online" : "offline");
    }
}