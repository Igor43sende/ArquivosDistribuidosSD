package controle;

import java.util.List;

// Interface que define todas as operações que o ServidorControle deve oferecer.
// Ela funciona como um “contrato”: qualquer classe que implemente IControle precisa
// fornecer exatamente estes serviços ao Gateway e aos Clientes.
// Aqui estão reunidas todas as funcionalidades exigidas pelo PDF do trabalho.
public interface IControle {

    // Cadastro de usuário no sistema (persistido e replicado pelos nós de controle).
    boolean cadastrarUsuario(String nome, String senha);

    // Autenticação simples de usuário, verificando nome + senha.
    boolean autenticarUsuario(String nome, String senha);

    // Envia um arquivo para o cluster de dados, retornando o UID gerado para ele.
    String enviarArquivos(String nome, byte[] dados, String usuario);

    // Atualização distribuída: permite substituir o conteúdo de um arquivo já existente.
    boolean atualizarArquivo(String uid, byte[] novoConteudo);

    // Solicita a lista de arquivos disponíveis no cluster de dados.
    List<String> solicitarListagem(String usuario);

    // Baixa o conteúdo de um arquivo diretamente pelo UID.
    byte[] downloadArquivo(String uid);

    // Remove um arquivo do sistema (se permitido pelo usuário/protocolo).
    boolean excluirArquivo(String uid);

    // Busca por arquivos pelo nome (suporte ao PDF: pesquisa global no sistema).
    List<String> buscarArquivos(String nome);

    // Gera o hash global do sistema — exigência do PDF.
    // Serve para garantir consistência entre os repositórios e detectar divergências.
    String obterHashGlobal();
}
