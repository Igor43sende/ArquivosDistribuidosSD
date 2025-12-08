package controle;

import java.io.Serializable;
import java.util.UUID;

// Classe que representa um usuário do sistema de forma serializável.
// Ela é enviada entre nós no getState/setState, por isso precisa implementar Serializable.
// Contém informações essenciais: UID único, nome, senha e estado "online".
public class Usuario implements Serializable {

    private UUID uid;       // Identificador único e permanente do usuário (não é o nome)
    private String nome;    // Nome de login do usuário
    private String senha;   // Senha armazenada em texto simples (modelo didático)
    private boolean online; // Marca se o usuário está conectado no momento

    // Construtor principal:
    // - Gera automaticamente um UUID único
    // - Salva nome e senha fornecidos
    // - Define o usuário como offline inicialmente
    public Usuario(String nome, String senha) {
        this.uid = UUID.randomUUID();
        this.nome = nome;
        this.senha = senha;
        this.online = false;
    }

    // Getters e setter simples (padrão POJO)
    public UUID getUid() { return uid; }
    public String getNome() { return nome; }
    public String getSenha() { return senha; }
    public boolean isOnline() { return online; }
    public void setOnline(boolean online) { this.online = online; }

    // Representação do usuário como string.
    // Esse formato é usado no cálculo do HASH GLOBAL para garantir um estado consistente.
    @Override
    public String toString() {
        return uid.toString() + ":" + nome + ":" + (online ? "online" : "offline");
    }
}
