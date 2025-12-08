package gateway;

import controle.IControle;

import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;

import java.util.List;

/**
 * Gateway
 *
 * Atua como front-end via RMI, recebendo requisições do cliente
 * e encaminhando ao ServidorControle (back-end distribuído).
 *
 * Não executa regras de negócio — apenas repassa as chamadas.
 */
public class Gateway extends UnicastRemoteObject implements IGateway {

    private final IControle servidorControle;

    /**
     * Construtor padrão.
     *
     * @param controle instância do servidor de controle (implementa IControle)
     */
    public Gateway(IControle controle) throws RemoteException {
        super(); // exporta o objeto automaticamente via RMI
        this.servidorControle = controle;
        System.out.println("[Gateway] Instanciado e vinculado ao servidor de controle.");
    }

    @Override
    public boolean cadastrarUsuario(String nomeUsuario, String senha) throws RemoteException {
        System.out.println("[Gateway] Requisição: cadastrar usuário -> " + nomeUsuario);
        try {
            return servidorControle.cadastrarUsuario(nomeUsuario, senha);
        } catch (Exception e) {
            System.err.println("[Gateway] Erro ao cadastrar usuário: " + e.getMessage());
            return false;
        }
    }

    @Override
    public boolean autenticarUsuario(String nomeUsuario, String senha) throws RemoteException {
        System.out.println("[Gateway] Requisição: autenticar usuário -> " + nomeUsuario);
        try {
            return servidorControle.autenticarUsuario(nomeUsuario, senha);
        } catch (Exception e) {
            System.err.println("[Gateway] Erro ao autenticar usuário: " + e.getMessage());
            return false;
        }
    }

    @Override
    public String enviarArquivos(String nomeArquivo, byte[] conteudo, String nomeUsuario) throws RemoteException {
        try {
            return servidorControle.enviarArquivos(nomeArquivo, conteudo, nomeUsuario);
        } catch (Exception e) {
            System.err.println("[Gateway] Erro ao enviar arquivos: " + e.getMessage());
            throw new RemoteException("Erro no envio de arquivos", e);
        }
    }

    // ★ NOVO ★ — atualização de arquivo via RMI
    @Override
    public boolean atualizarArquivo(String uid, byte[] novoConteudo) throws RemoteException {
        System.out.println("[Gateway] Requisição UPDATE para UID=" + uid);
        try {
            return servidorControle.atualizarArquivo(uid, novoConteudo);
        } catch (Exception e) {
            System.err.println("[Gateway] Erro no UPDATE do arquivo: " + e.getMessage());
            throw new RemoteException("Falha no update", e);
        }
    }

    @Override
    public List<String> solicitarListagem(String nomeUsuario) throws RemoteException {
        try {
            return servidorControle.solicitarListagem(nomeUsuario);
        } catch (Exception e) {
            System.err.println("[Gateway] Erro ao solicitar listagem: " + e.getMessage());
            throw new RemoteException("Erro na listagem", e);
        }
    }

    @Override
    public byte[] downloadArquivo(String uid) throws RemoteException {
        System.out.println("[Gateway] Requisição de download do arquivo UID: " + uid);
        try {
            return servidorControle.downloadArquivo(uid);
        } catch (Exception e) {
            System.err.println("[Gateway] Erro ao baixar arquivo: " + e.getMessage());
            throw new RemoteException("Falha ao baixar arquivo", e);
        }
    }

    @Override
    public boolean excluirArquivo(String uid) throws RemoteException {
        System.out.println("[Gateway] Requisição de exclusão do arquivo UID: " + uid);
        try {
            return servidorControle.excluirArquivo(uid);
        } catch (Exception e) {
            System.err.println("[Gateway] Erro ao excluir arquivo: " + e.getMessage());
            throw new RemoteException("Falha ao excluir arquivo", e);
        }
    }

    @Override
    public List<String> buscarArquivos(String nome) throws RemoteException {
        System.out.println("[Gateway] Requisição de busca por arquivos com nome: " + nome);
        try {
            return servidorControle.buscarArquivos(nome);
        } catch (Exception e) {
            System.err.println("[Gateway] Erro na busca: " + e.getMessage());
            throw new RemoteException("Erro ao buscar arquivos", e);
        }
    }

    @Override
    public String obterHashEstado() throws RemoteException {
        System.out.println("[Gateway] Requisição: obterHashEstado()");
        try {
            return servidorControle.obterHashEstado();
        } catch (Exception e) {
            System.err.println("[Gateway] Erro ao obter hash do estado: " + e.getMessage());
            throw new RemoteException("Erro ao obter hash", e);
        }
    }
}
