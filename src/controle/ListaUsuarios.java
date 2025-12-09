package controle;

import java.io.Serializable;
import java.util.HashMap;
import java.security.MessageDigest;
import java.util.*;
import java.util.Base64.*;
import java.util.stream.Collectors;
import java.nio.charset.StandardCharsets;


// Classe que mantém a lista de usuários do sistema e é serializável para permitir
// replicação de estado via JGroups (getState/setState). Armazena usuários,
// permite cadastro, login, logout e também gera um hash do estado atual
// para atender ao requisito de “Hash Global” do PDF.
public class ListaUsuarios implements Serializable {

    private static final long serialVersionUID = 1L;

    // Estrutura central: mapa com nome do usuário → objeto Usuario.
    // Usar HashMap permite acesso rápido tanto para autenticação quanto cadastro.
    private final Map<String, Usuario> usuarios;

    public ListaUsuarios() {
        this.usuarios = new HashMap<>();
    }

    // Cadastro sincronizado para evitar race conditions quando vários nós/threads tentam
    // cadastrar usuários simultaneamente. Apenas cria o usuário se o nome ainda não existir.
    public synchronized boolean cadastrarUsuario(String nomeUsuario, String senha) {
        if (usuarios.containsKey(nomeUsuario)) {
            return false; // Usuário já existe
        }
        Usuario novoUsuario = new Usuario(nomeUsuario, senha);
        usuarios.put(nomeUsuario, novoUsuario);
        return true; // Usuário cadastrado com sucesso
    }

    // Autenticação segura e sincronizada: verifica se o usuário existe e se a senha confere.
    public synchronized Usuario autenticarUsuario(String nomeUsuario, String senha) {
        Usuario usuario = usuarios.get(nomeUsuario);
        if (usuario != null && usuario.getSenha().equals(senha)) {
            return usuario; // Autenticação bem-sucedida
        }
        return null; // Falha na autenticação
    }

    // Marca o usuário como offline. Isso é útil para controle de sessão
    // mas não necessariamente afeta a autenticação.
    public synchronized void logoutUsuario(String nomeUsuario) {
        Usuario usuario = usuarios.get(nomeUsuario);
        if (usuario != null) {
            usuario.setOnline(false); // Define o usuário como offline
        }
    }

    // Gera o hash global do estado da lista de usuários.
    // Esse hash é fundamental para o trabalho: permite que todos os ServidoresControle
    // comparem entre si se possuem exatamente o mesmo conjunto de usuários.
    // A ordenação garante determinismo, evitando diferenças mesmo que a ordem no HashMap varie.
    public synchronized String gerarHashEstado() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            // Converte cada usuário para string, garantindo consistência de estado.
            List<String> estadosUsuarios = usuarios.values().stream()
                    .filter(obj -> obj instanceof Usuario)              // Garante tipos válidos
                    .map(usuario -> ((Usuario) usuario).toString())     // Serializa cada usuário
                    .sorted()                                           // Ordena para garantir o mesmo hash entre nós
                    .collect(Collectors.toList());

            // Alimenta o hash incrementando com cada string de estado.
            for (String estado : estadosUsuarios) {
                digest.update(estado.getBytes(StandardCharsets.UTF_8));
            }

            // Finaliza o hash e converte para Base64 para facilitar exibição e transmissão.
            byte[] hashBytes = digest.digest();
            return Base64.getEncoder().encodeToString(hashBytes);

        } catch (Exception e) {
            throw new RuntimeException("Erro ao gerar hash do estado dos usuários", e);
        }
    }

    // Constrói uma string contendo informação de todos os usuários.
    // Usado principalmente para debug, logs ou sincronização de estado visível.
    @Override
    public synchronized String toString() {
        StringBuilder sb = new StringBuilder();
        for (Usuario usuario : usuarios.values()) {
            sb.append(usuario.toString()).append("\n");
        }
        return sb.toString();
    }
}
