package controle;

import java.util.List;

public interface IControle {

    boolean cadastrarUsuario(String nome, String senha);

    boolean autenticarUsuario(String nome, String senha);

    // Alinhado com a implementação em ServidorControle
    String enviarArquivos(String nome, byte[] dados, String usuario);

    // ★ NOVO ★ — suporte ao update distribuído
    boolean atualizarArquivo(String uid, byte[] novoConteudo);

    // Alinhado com o método da implementação
    List<String> solicitarListagem(String usuario);

    byte[] downloadArquivo(String uid);

    boolean excluirArquivo(String uid);

    List<String> buscarArquivos(String nome);

    String obterHashEstado();
}
