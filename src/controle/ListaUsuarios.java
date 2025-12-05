package controle;

import java.io.Serializable;
import java.util.HashMap;
import java.security.MessageDigest;
import java.util.*;
import java.util.Base64.*;
import java.util.stream.Collectors;
import java.nio.charset.StandardCharsets;


public class ListaUsuarios implements Serializable {

    private static final long serialVersionUID = 1L;

    private final Map<String, Usuario> usuarios;

    public ListaUsuarios() {
        this.usuarios = new HashMap<>();
    }

    public synchronized boolean cadastrarUsuario(String nomeUsuario, String senha) {
        if (usuarios.containsKey(nomeUsuario)) {
            return false; // Usuário já existe
        }
        Usuario novoUsuario = new Usuario(nomeUsuario, senha);
        usuarios.put(nomeUsuario, novoUsuario);
        return true; // Usuário cadastrado com sucesso
    }

    public synchronized Usuario autenticarUsuario(String nomeUsuario, String senha) {
        Usuario usuario = usuarios.get(nomeUsuario);
        if (usuario != null && usuario.getSenha().equals(senha)) {
            return usuario; // Autenticação bem-sucedida
        }
        return null; // Falha na autenticação
    }

    public synchronized void logoutUsuario(String nomeUsuario) {
        Usuario usuario = usuarios.get(nomeUsuario);
        if (usuario != null) {
            usuario.setOnline(false); // Define o usuário como offline
        }
    }

    public synchronized String gerarHashEstado() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            // Garantimos que apenas objetos Usuario sejam processados
            List<String> estadosUsuarios = usuarios.values().stream()
                    .filter(obj -> obj instanceof Usuario)              // Filtra apenas objetos válidos
                    .map(usuario -> ((Usuario) usuario).toString())     // Converte para String
                    .sorted()                                           // Ordena para consistência
                    .collect(Collectors.toList());

            // Adiciona cada estado ao hash
            for (String estado : estadosUsuarios) {
                digest.update(estado.getBytes(StandardCharsets.UTF_8));
            }

            // Finaliza e converte para Base64
            byte[] hashBytes = digest.digest();
            return Base64.getEncoder().encodeToString(hashBytes);

        } catch (Exception e) {
            throw new RuntimeException("Erro ao gerar hash do estado dos usuários", e);
        }
    }

    @Override
    public synchronized String toString() {
        StringBuilder sb = new StringBuilder();
        for (Usuario usuario : usuarios.values()) {
            sb.append(usuario.toString()).append("\n");
        }
        return sb.toString();
    }
}