package gateway;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;

/**
 * Interface IGateway
 *
 * Esta interface define todos os métodos que o Cliente pode chamar via RMI.
 * O Gateway a implementa, e o ServidorControle é quem realmente executa as operações.
 *
 * Em resumo:
 *  - Cliente chama IGateway → Gateway repassa → ServidorControle executa.
 *
 * Isso permite que o sistema esconda completamente o uso de JGroups
 * da camada do Cliente.
 */
public interface IGateway extends Remote {

    // CADASTRO E LOGIN
    boolean cadastrarUsuario(String nome, String senha) throws RemoteException;
    boolean autenticarUsuario(String nome, String senha) throws RemoteException;

    // UPLOAD
    String enviarArquivos(String nome, byte[] dados, String usuarioLogado) throws RemoteException;

    // UPDATE — operação distribuída com controle de versão
    // Esta função envia o novo conteúdo para o ServidorControle,
    // que por sua vez enviará a atualização ao cluster de dados.
    boolean atualizarArquivo(String uid, byte[] novoConteudo) throws RemoteException;

    // LISTAGEM E DOWNLOAD
    List<String> solicitarListagem(String usuarioLogado) throws RemoteException;
    byte[] downloadArquivo(String uid) throws RemoteException;

    // EXCLUSÃO
    boolean excluirArquivo(String uid) throws RemoteException;

    // BUSCA GLOBAL
    List<String> buscarArquivos(String nome) throws RemoteException;

    // HASH GLOBAL DO SISTEMA
    // Combina o hash dos usuários e dos arquivos para exibir
    // um "estado consolidado" do sistema.
    String obterHashGlobal() throws RemoteException;
}
