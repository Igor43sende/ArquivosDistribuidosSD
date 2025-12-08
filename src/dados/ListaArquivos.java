package dados;

import java.util.List;
import java.util.ArrayList;
import java.io.Serializable;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.stream.Collectors;

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

    /** Atualiza arquivo existente apenas se a versão recebida for mais recente. */
    public synchronized void atualizarArquivo(Arquivo novo) {
        Arquivo antigo = buscarPorUid(novo.getUid());

        if (antigo != null) {
            if (novo.getTimestamp() < antigo.getTimestamp()) {
                System.out.println("[ListaArquivos] Ignorando UPDATE atrasado para UID=" + novo.getUid());
                return;
            }
            arquivos.remove(antigo);
        }

        arquivos.add(novo);
        System.out.println("[ListaArquivos] UPDATE aplicado: UID=" + novo.getUid());
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

    /** Busca arquivo pelo nome */
    public synchronized Arquivo buscarPorNome(String nome) {
        for (Arquivo a : arquivos) {
            if (a.getNome().equalsIgnoreCase(nome)) return a;
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

    // ----------------------------------------------------------------------
    // *** NOVO *** Funções exigidas pelo PDF
    // ----------------------------------------------------------------------

    /** Retorna arquivos cujo timestamp seja maior que o informado */
    public synchronized List<Arquivo> arquivosMaisRecentesQue(long timestamp) {
        return arquivos.stream()
                .filter(a -> a.getTimestamp() > timestamp)
                .collect(Collectors.toList());
    }

    /**
     * Hash global do estado inteiro da ListaArquivos (metadados)
     * Usado pelo ServidorControle ou auditoria interna
     */
    public synchronized String gerarHashEstado() {
        return gerarHash(true);
    }

    /**
     * ★★★ NOVO ★★★
     * Hash apenas dos METADADOS — este é o método chamado pelo ServidorDados
     *
     * O PDF exige que:
     * - o ServidorControle peça "HASH"
     * - o ServidorDados gere o hash dos metadados de arquivos
     */
    public synchronized String gerarHashMetadados() {
        return gerarHash(true);
    }

    /**
     * Função interna auxiliar de criação de hash (ordenado → determinístico).
     */
    private synchronized String gerarHash(boolean apenasMetadados) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            // Ordena por UID para gerar hashes consistentes entre nós
            List<Arquivo> ordenados = new ArrayList<>(arquivos);
            ordenados.sort(Comparator.comparing(Arquivo::getUid));

            for (Arquivo a : ordenados) {

                // Conteúdo NUNCA entra no hash → só metadados
                String linha = a.getUid() + "|" +
                        a.getNome() + "|" +
                        a.getUsuario() + "|" +
                        a.getTimestamp();

                digest.update(linha.getBytes("UTF-8"));
            }

            byte[] hash = digest.digest();

            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));

            return sb.toString();

        } catch (Exception e) {
            return "HASH_ERROR";
        }
    }
}
