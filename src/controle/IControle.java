package controle;

import java.util.List;

public interface IControle {

    boolean cadastrarUsuario(String nome, String senha);

    boolean autenticarUsuario(String nome, String senha);

    String enviarArquivos(String nome, byte[] dados, String usuario);

    // suporte ao update distribuído
    boolean atualizarArquivo(String uid, byte[] novoConteudo);

    List<String> solicitarListagem(String usuario);

    byte[] downloadArquivo(String uid);

    boolean excluirArquivo(String uid);

    List<String> buscarArquivos(String nome);

    // ★★ HASH GLOBAL DO SISTEMA ★★
    String obterHashGlobal();
}
