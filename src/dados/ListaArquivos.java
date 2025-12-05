package dados;

import java.util.List;
import java.util.ArrayList;
import java.io.Serializable;

public class ListaArquivos implements Serializable {
    private static final long serialVersionUID = 1L;

    private final List<Arquivo> arquivos = new ArrayList<>();

    /** Adiciona um novo arquivo (ou atualiza se UID já existir) */
    public synchronized void adicionarArquivo(Arquivo arquivo) {
        Arquivo existente = buscarPorUid(arquivo.getUid());
        if (existente != null) {
            arquivos.remove(existente);
        }
        arquivos.add(arquivo);
        System.out.println("[ListaArquivos] Arquivo adicionado/atualizado: " + arquivo);
    }

    /** Lista todos os arquivos armazenados */
    public synchronized List<Arquivo> listarArquivos() {
        return new ArrayList<>(arquivos);
    }

    /** Busca arquivo pelo UID */
    public synchronized Arquivo buscarPorUid(String uid) {
        for (Arquivo a : arquivos) {
            if (a.getUid().equals(uid)) return a;
        }
        return null;
    }

    /** Busca arquivo pelo nome (primeira ocorrência encontrada) */
    public synchronized Arquivo buscarPorNome(String nomeUsuario) {
        for (Arquivo a : arquivos) {
            if (a.getNome().equalsIgnoreCase(nomeUsuario)) return a;
        }
        return null;
    }

    /** Remove um arquivo pelo UID */
    public synchronized boolean removerPorUid(String uid) {
        Arquivo a = buscarPorUid(uid);
        if (a != null) {
            arquivos.remove(a);
            System.out.println("[ListaArquivos] Arquivo removido: " + a);
            return true;
        }
        return false;
    }

    /** Verifica se um arquivo pertence ao usuário */
    public synchronized boolean pertenceAoUsuario(String uid, String nomeUsuario) {
        Arquivo a = buscarPorUid(uid);
        return a != null && a.getUsuario().equals(nomeUsuario);
    }
}
