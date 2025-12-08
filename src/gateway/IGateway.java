package gateway;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;

public interface IGateway extends Remote {

    boolean cadastrarUsuario(String nome, String senha) throws RemoteException;

    boolean autenticarUsuario(String nome, String senha) throws RemoteException;

    String enviarArquivos(String nome, byte[] dados, String usuarioLogado) throws RemoteException;

    // ★ NOVO ★ — atualização distribuída de arquivo
    boolean atualizarArquivo(String uid, byte[] novoConteudo) throws RemoteException;

    List<String> solicitarListagem(String usuarioLogado) throws RemoteException;

    byte[] downloadArquivo(String uid) throws RemoteException;

    boolean excluirArquivo(String uid) throws RemoteException;

    List<String> buscarArquivos(String nome) throws RemoteException;

    String obterHashEstado() throws RemoteException;
}
