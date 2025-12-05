package util;

import java.io.*;

/**
 * Utilitário simples para persistência de objetos via serialização Java.
 *
 * Uso:
 *   PersistenciaUtil.salvarObjeto(meuObjeto, "estado.bin");
 *   MinhaClasse obj = PersistenciaUtil.carregarObjeto("estado.bin");
 *
 * Observações:
 * - O objeto salvo deve implementar java.io.Serializable (ou suas partes).
 * - A gravação é feita de forma "atômica": escreve em arquivo temporário e renomeia.
 * - Em caso de erro, os métodos retornam false / null e imprimem stacktrace no stderr.
 */
public final class PersistenciaUtil {

    private PersistenciaUtil() { /* utilitário — não instanciável */ }

    /**
     * Salva um objeto em disco serializado.
     * Retorna true se salvou com sucesso.
     *
     * @param obj objeto serializável
     * @param caminho caminho do arquivo de destino
     * @return true se salvou com sucesso
     */
    public static boolean salvarObjeto(Object obj, String caminho) {
        if (obj == null || caminho == null) return false;

        File target = new File(caminho);
        File tmp = new File(caminho + ".tmp");

        ObjectOutputStream oos = null;
        FileOutputStream fos = null;
        try {
            // Garante que o diretório exista
            File parent = target.getAbsoluteFile().getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();

            fos = new FileOutputStream(tmp);
            oos = new ObjectOutputStream(new BufferedOutputStream(fos));
            oos.writeObject(obj);
            oos.flush();
            oos.close();
            oos = null;
            fos.close();
            fos = null;

            // Move (rename) atômico (na maioria dos sistemas)
            if (!tmp.renameTo(target)) {
                // fallback: tentar copiar manualmente
                try (FileInputStream in = new FileInputStream(tmp);
                     FileOutputStream out = new FileOutputStream(target)) {
                    byte[] buf = new byte[8192];
                    int r;
                    while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
                }
                tmp.delete();
            }

            return true;
        } catch (Exception e) {
            System.err.println("[PersistenciaUtil] Erro ao salvar objeto em " + caminho + " : " + e.getMessage());
            e.printStackTrace(System.err);
            // tenta remover tmp em caso de falha
            try { if (oos != null) oos.close(); } catch (Exception ignored) {}
            try { if (fos != null) fos.close(); } catch (Exception ignored) {}
            try { tmp.delete(); } catch (Exception ignored) {}
            return false;
        }
    }

    /**
     * Carrega um objeto serializado do caminho informado.
     * Retorna null em caso de falha ou se o arquivo não existir.
     *
     * Uso típico:
     *   MinhaClasse obj = PersistenciaUtil.carregarObjeto("estado.bin");
     *
     * @param caminho caminho do arquivo salvo
     * @param <T> tipo esperado (cast feito internamente)
     * @return objeto carregado ou null se não existir/erro
     */
    @SuppressWarnings("unchecked")
    public static <T> T carregarObjeto(String caminho) {
        if (caminho == null) return null;
        File f = new File(caminho);
        if (!f.exists() || !f.isFile()) return null;

        try (FileInputStream fis = new FileInputStream(f);
             ObjectInputStream ois = new ObjectInputStream(new BufferedInputStream(fis))) {

            Object obj = ois.readObject();
            return (T) obj;

        } catch (Exception e) {
            System.err.println("[PersistenciaUtil] Erro ao carregar objeto de " + caminho + " : " + e.getMessage());
            e.printStackTrace(System.err);
            return null;
        }
    }
}
