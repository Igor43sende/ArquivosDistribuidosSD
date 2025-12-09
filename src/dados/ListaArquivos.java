package dados;

import java.util.List;
import java.util.ArrayList;
import java.io.Serializable;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.stream.Collectors;

// Classe responsável por manter a lista de arquivos do sistema distribuído.
// Ela funciona como o "banco de dados" de metadados do cluster de dados.
// Aqui ficam armazenados os arquivos enviados, atualizados ou removidos,
// e também é onde se gera o hash determinístico exigido pelo PDF.
// Todos os métodos são synchronized para proteger a lista em ambiente
// concorrente, já que ServidorDados pode processar requisições simultâneas.
public class ListaArquivos implements Serializable {
    private static final long serialVersionUID = 1L;

    // Estrutura central: lista de todos os arquivos armazenados pelo cluster.
    // O ServidorDados usa essa lista para listagem, busca, update e delete.
    private final List<Arquivo> arquivos = new ArrayList<>();

    // Adiciona um novo arquivo (ou atualiza se UID já existir)
    // Método usado principalmente no UPLOAD inicial.
    // Caso o UID já exista, significa que o arquivo foi restaurado/reescrito,
    // então ele é substituído na lista.
    public synchronized void adicionarArquivo(Arquivo arquivo) {
        Arquivo existente = buscarPorUid(arquivo.getUid());
        if (existente != null) {
            arquivos.remove(existente);
        }
        arquivos.add(arquivo);
        System.out.println("[ListaArquivos] Arquivo adicionado/atualizado: " + arquivo);
    }

    // Atualiza arquivo existente apenas se a versão recebida for mais recente.
    // Este método implementa o controle de versionamento via timestamp.
    // - Se o arquivo não existe → adiciona.
    // - Se existe, mas o timestamp novo é mais antigo → ignora update atrasado.
    // - Caso contrário aplica a atualização.
    // Isto evita condições de corrida e mantém consistência entre ServidoresDados.

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

    // Lista todos os arquivos armazenados
    // Retorna uma cópia da lista original para evitar modificações externas.
    public synchronized List<Arquivo> listarArquivos() {
        return new ArrayList<>(arquivos);
    }

    // Busca arquivo pelo UID
    // Operação usada para GET, UPDATE e DELETE.
    public synchronized Arquivo buscarPorUid(String uid) {
        for (Arquivo a : arquivos) {
            if (a.getUid().equals(uid)) return a;
        }
        return null;
    }

    // Busca arquivo pelo nome
    // Suporte ao comando SEARCH enviado pelo ServidorControle.
    public synchronized Arquivo buscarPorNome(String nome) {
        for (Arquivo a : arquivos) {
            if (a.getNome().equalsIgnoreCase(nome)) return a;
        }
        return null;
    }

    // Remove um arquivo pelo UID
    // Usado pelo comando DELETE. Retorna true se o arquivo existia.
    public synchronized boolean removerPorUid(String uid) {
        Arquivo a = buscarPorUid(uid);
        if (a != null) {
            arquivos.remove(a);
            System.out.println("[ListaArquivos] Arquivo removido: " + a);
            return true;
        }
        return false;
    }

    // Verifica se um arquivo pertence ao usuário
    // Útil caso regras de permissão ou auditoria sejam aplicadas.
    public synchronized boolean pertenceAoUsuario(String uid, String nomeUsuario) {
        Arquivo a = buscarPorUid(uid);
        return a != null && a.getUsuario().equals(nomeUsuario);
    }

    // Retorna arquivos cujo timestamp seja maior que o informado
    // Suporte a replicações incrementais entre nós do cluster de dados.
    public synchronized List<Arquivo> arquivosMaisRecentesQue(long timestamp) {
        return arquivos.stream()
                .filter(a -> a.getTimestamp() > timestamp)
                .collect(Collectors.toList());
    }


     // Hash global do estado inteiro da ListaArquivos (metadados)
     // Usado pelo ServidorControle ou auditorias internas do cluster.

    public synchronized String gerarHashEstado() {
        return gerarHash(true);
    }

     // Hash apenas dos METADADOS — chamado pelo ServidorDados quando recebe "HASH".
     // ServidorControle envia comando "HASH"
     // - ServidorDados responde com hash apenas dos metadados dos arquivos
     // Isso permite verificar divergências entre repositórios.

    public synchronized String gerarHashMetadados() {
        return gerarHash(true);
    }


     // Função interna auxiliar de criação de hash.
     // Detalhes importantes:
     // - Ordena por UID → torna o hash determinístico entre nós.
     // - Usa apenas metadados (conteúdo NUNCA entra no hash).
     // - Aplica SHA-256 e retorna em formato hexadecimal.
     // Isso garante que ServidorControle e ServidorDados possam comparar estados
     // com exatidão e detectar inconsistências em replicação.

    private synchronized String gerarHash(boolean apenasMetadados) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            // Ordena os arquivos por UID para gerar hash idêntico entre nós
            List<Arquivo> ordenados = new ArrayList<>(arquivos);
            ordenados.sort(Comparator.comparing(Arquivo::getUid));

            for (Arquivo a : ordenados) {

                // Conteúdo REAL do arquivo não entra — somente METADADOS
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
