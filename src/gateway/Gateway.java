package gateway;

import controle.IControle;

import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;

import java.util.List;

/**
 * Gateway
 *
 * Atua como a **ponte entre o Cliente (RMI)** e o **ServidorControle (cluster JGroups)**.
 *
 * Seu papel é extremamente importante:
 *  - expõe os métodos via RMI
 *  - recebe chamadas do Cliente
 *  - repassa diretamente para o ServidorControle
 *
 * O Gateway **NÃO IMPLEMENTA regra de negócio**.
 * Ele apenas "traduz" chamadas remotas em chamadas locais.
 *
 * Assim o Cliente não precisa conhecer JGroups — apenas RMI.
 */
public class Gateway extends UnicastRemoteObject implements IGateway {

    // Referência para o ServidorControle REAL
    // Tudo que chega do Cliente via RMI é repassado para ele.
    private final IControle servidorControle;

    /**
     * Construtor padrão.
     *
     * Ao chamar super(), o Java exporta automaticamente este objeto
     * e o torna acessível via RMI.
     *
     * @param controle instância concreta do ServidorControle
     */
    public Gateway(IControle controle) throws RemoteException {
        super(); // exporta o objeto automaticamente via RMI
        this.servidorControle = controle;
        System.out.println("[Gateway] Instanciado e vinculado ao servidor de controle.");
    }

    // ---------------------------------
    // BLOCO DE CADASTRO E AUTENTICAÇÃO
    // ---------------------------------

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

    // -------
    // UPLOAD
    // -------

    @Override
    public String enviarArquivos(String nomeArquivo, byte[] conteudo, String nomeUsuario) throws RemoteException {
        try {
            return servidorControle.enviarArquivos(nomeArquivo, conteudo, nomeUsuario);
        } catch (Exception e) {
            System.err.println("[Gateway] Erro ao enviar arquivos: " + e.getMessage());
            throw new RemoteException("Erro no envio de arquivos", e);
        }
    }

    // -------------------------------------------
    // UPDATE (operação mais complexa do sistema)
    // -------------------------------------------

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

    // ----------
    // LISTAGEM
    // ----------

    @Override
    public List<String> solicitarListagem(String nomeUsuario) throws RemoteException {
        try {
            return servidorControle.solicitarListagem(nomeUsuario);
        } catch (Exception e) {
            System.err.println("[Gateway] Erro ao solicitar listagem: " + e.getMessage());
            throw new RemoteException("Erro na listagem", e);
        }
    }

    // ---------
    // DOWNLOAD
    // ---------

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

    // ---------
    // EXCLUSÃO
    // ---------

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

    // -------------
    // BUSCA GLOBAL
    // -------------

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

    // ---------------------------------------------
    // HASH GLOBAL DO SISTEMA (Usuários + Arquivos)
    // ---------------------------------------------

    @Override
    public String obterHashGlobal() throws RemoteException {
        System.out.println("[Gateway] Requisição: obterHashGlobal()");
        try {
            return servidorControle.obterHashGlobal();
        } catch (Exception e) {
            System.err.println("[Gateway] Erro ao obter hash global: " + e.getMessage());
            throw new RemoteException("Erro ao obter hash global", e);
        }
    }
}
