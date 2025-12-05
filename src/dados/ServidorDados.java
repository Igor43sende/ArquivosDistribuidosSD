package dados;

import org.jgroups.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.ArrayList;
import java.util.stream.Collectors;
import util.PersistenciaUtil;

public class ServidorDados extends ReceiverAdapter {

    private JChannel canal;
    private ListaArquivos listaArquivos;
    private final String DIRETORIO_BASE = "repositorio/";
    private static final String CAMINHO_ESTADO_DADOS = "estado_dados.bin";

    public void iniciar() throws Exception {
        carregarEstado();
        new File(DIRETORIO_BASE).mkdirs();

        canal = new JChannel("dados.xml");
        canal.setReceiver(this);
        canal.connect("ClusterDados");

        if (!canal.getAddress().equals(canal.getView().getMembers().get(0))) {
            System.out.println("[DADOS] Solicitando estado do cluster...");
            canal.getState(null, 10000);
        } else {
            System.out.println("[DADOS] Coordenador inicial do grupo.");
        }

        System.out.println("[DADOS] ServidorDados conectado ao cluster 'ClusterDados'. Address=" + canal.getAddress());
    }

    public void salvarArquivo(Arquivo arquivo) throws Exception {
        // adiciona à lista
        listaArquivos.adicionarArquivo(arquivo);
        // salva fisicamente
        if (arquivo.getConteudo() != null && arquivo.getConteudo().length > 0) {
            try (FileOutputStream fos = new FileOutputStream(DIRETORIO_BASE + arquivo.getUid())) {
                fos.write(arquivo.getConteudo());
            }
        }
        salvarEstado();

        // replicar metadados para o cluster (multicast) — envia cópia sem conteúdo para economizar banda
        Arquivo meta = new Arquivo(arquivo.getUid(), arquivo.getNome(), null, arquivo.getUsuario());
        try {
            canal.send(new Message(null, meta));
        } catch (Exception e) {
            System.err.println("[DADOS] Erro ao replicar metadado: " + e.getMessage());
            e.printStackTrace();
        }

        System.out.println("[DADOS] Arquivo salvo localmente. UID=" + arquivo.getUid());
    }

    @Override
    public void receive(Message msg) {
        // Evitar eco (não processar mensagens originadas por este mesmo nó)
        if (msg.getSrc() != null && msg.getSrc().equals(canal.getAddress())) return;

        Object obj = null;
        try {
            obj = msg.getObject();
        } catch (Exception e) {
            System.err.println("[DADOS] Erro ao desserializar objeto recebido: " + e.getMessage());
            e.printStackTrace();
            return;
        }

        // Debug: mostrar que mensagem chegou
        System.out.println("[DADOS] receive() de " + msg.getSrc() + " tipo=" + (obj != null ? obj.getClass().getSimpleName() : "NULL"));

        if (obj instanceof String comando) {
            tratarComando(comando, msg.getSrc());
            return;
        }

        if (obj instanceof Arquivo arquivo) {
            // Quando receber arquivo (upload), ele pode vir com conteúdo
            try {
                salvarArquivo(arquivo);
                System.out.println("[DADOS] Arquivo recebido e salvo. UID=" + arquivo.getUid());
            } catch (Exception e) {
                System.err.println("[DADOS] Erro ao salvar arquivo recebido: " + e.getMessage());
                e.printStackTrace();
            }
            return;
        }

        System.out.println("[DADOS] Mensagem inesperada: " + (obj != null ? obj.getClass().getName() : "NULL"));
    }

    private void tratarComando(String comando, Address remetente) {
        if (comando == null || comando.trim().isEmpty()) return;
        String[] partes = comando.split(";", 2);
        String acao = partes[0];

        System.out.println("[DADOS] Comando recebido de " + remetente + " -> " + comando);

        switch (acao) {
            case "LIST_USER": {
                String usuario = partes.length > 1 ? partes[1] : "";
                List<String> resultado = new ArrayList<>();
                for (Arquivo a : listaArquivos.listarArquivos()) {
                    if (a.getUsuario() != null && a.getUsuario().equals(usuario)) {
                        resultado.add(a.getUid() + ";" + a.getNome());
                    }
                }
                try {
                    Message m = new Message(remetente, resultado);
                    canal.send(m);
                    System.out.println("[DADOS] Enviada LIST_USER para " + remetente + " itens=" + resultado.size());
                } catch (Exception e) {
                    System.err.println("[DADOS] Erro ao enviar LIST_USER: " + e.getMessage());
                    e.printStackTrace();
                }
                break;
            }
            case "GET": {
                String uid = partes.length > 1 ? partes[1].trim() : "";
                System.out.println("[DADOS] GET solicitado UID=" + uid + " por " + remetente);
                Arquivo a = listaArquivos.buscarPorUid(uid);
                byte[] conteudo = null;
                boolean encontrado = false;

                if (a != null) {
                    // se o objeto já tem conteúdo em memória
                    if (a.getConteudo() != null && a.getConteudo().length > 0) {
                        conteudo = a.getConteudo();
                        encontrado = true;
                    } else {
                        // tenta ler do disco
                        try {
                            java.nio.file.Path p = Paths.get(DIRETORIO_BASE + uid);
                            if (Files.exists(p)) {
                                conteudo = Files.readAllBytes(p);
                                encontrado = true;
                            }
                        } catch (IOException ioe) {
                            System.err.println("[DADOS] Falha ao ler arquivo do disco: " + ioe.getMessage());
                            ioe.printStackTrace();
                        }
                    }
                }

                try {
                    if (encontrado && conteudo != null) {
                        Message m = new Message(remetente, conteudo);
                        canal.send(m);
                        System.out.println("[DADOS] Enviado conteúdo UID=" + uid + " para " + remetente + " bytes=" + (conteudo != null ? conteudo.length : 0));
                    } else {
                        // enviar mensagem explícita de não-encontrado para que o controlador trate isso
                        Message m = new Message(remetente, "NOT_FOUND");
                        canal.send(m);
                        System.out.println("[DADOS] Arquivo UID=" + uid + " não encontrado. Enviado NOT_FOUND para " + remetente);
                    }
                } catch (Exception e) {
                    System.err.println("[DADOS] Erro ao enviar GET: " + e.getMessage());
                    e.printStackTrace();
                }
                break;
            }
            case "DELETE": {
                String uid = partes.length > 1 ? partes[1].trim() : "";
                System.out.println("[DADOS] DELETE solicitado UID=" + uid + " por " + remetente);
                boolean r = listaArquivos.removerPorUid(uid);
                if (r) {
                    try { Files.deleteIfExists(Paths.get(DIRETORIO_BASE + uid)); } catch (IOException ignored) {}
                    salvarEstado();
                    System.out.println("[DADOS] DELETE OK UID=" + uid);
                } else {
                    System.out.println("[DADOS] DELETE falhou (não encontrado) UID=" + uid);
                }
                break;
            }
            case "SEARCH": {
                String nome = partes.length > 1 ? partes[1] : "";
                List<String> res = new ArrayList<>();
                for (Arquivo ar : listaArquivos.listarArquivos()) {
                    if (ar.getNome() != null && ar.getNome().toLowerCase().contains(nome.toLowerCase()))
                        res.add(ar.getUid() + ";" + ar.getNome());
                }
                try {
                    Message m = new Message(remetente, res);
                    canal.send(m);
                    System.out.println("[DADOS] Enviado SEARCH para " + remetente + " itens=" + res.size());
                } catch (Exception e) {
                    System.err.println("[DADOS] Erro ao enviar SEARCH: " + e.getMessage());
                    e.printStackTrace();
                }
                break;
            }
            default:
                System.out.println("[DADOS] Comando desconhecido: " + comando);
        }
    }

    @Override
    public void viewAccepted(View view) {
        System.out.println("[DADOS] Nova view: " + view);
        // solicitar estado se não for coordenador
        boolean souCoordenador = canal.getAddress().equals(canal.getView().getMembers().get(0));
        if (!souCoordenador) {
            try { canal.getState(null, 5000); } catch (Exception e) { System.err.println(e.getMessage()); }
        } else {
            System.out.println("[DADOS] Eu sou o coordenador do cluster.");
        }
    }

    @Override
    public void getState(OutputStream output) throws Exception {
        synchronized (listaArquivos) {
            ObjectOutputStream oos = new ObjectOutputStream(output);
            oos.writeObject(listaArquivos);
            oos.flush();
        }
    }

    @Override
    public void setState(InputStream input) throws Exception {
        ObjectInputStream ois = new ObjectInputStream(input);
        ListaArquivos estado = (ListaArquivos) ois.readObject();
        synchronized (listaArquivos) { listaArquivos = estado; }
        salvarEstado();
        // reconstruir arquivos físicos se houver conteúdo
        for (Arquivo a : listaArquivos.listarArquivos()) {
            if (a.getConteudo() != null && a.getConteudo().length > 0) {
                try (FileOutputStream fos = new FileOutputStream(DIRETORIO_BASE + a.getUid())) {
                    fos.write(a.getConteudo());
                } catch (IOException ignored) {}
            }
        }
    }

    private void salvarEstado() {
        if (listaArquivos != null) PersistenciaUtil.salvarObjeto(listaArquivos, CAMINHO_ESTADO_DADOS);
    }

    private void carregarEstado() {
        ListaArquivos estado = PersistenciaUtil.carregarObjeto(CAMINHO_ESTADO_DADOS);
        if (estado != null) {
            listaArquivos = estado;
            System.out.println("[DADOS] Estado restaurado do disco.");
        } else {
            listaArquivos = new ListaArquivos();
            System.out.println("[DADOS] Iniciando lista vazia.");
        }
    }

    public static void main(String[] args) {
        ServidorDados s = null;
        try {
            s = new ServidorDados();
            s.iniciar();
            System.out.println("ServidorDados em execução. Pressione ENTER para desligar...");
            new java.util.Scanner(System.in).nextLine();
            s.salvarEstado();
        } catch (Exception e) {
            e.printStackTrace();
            if (s != null) s.salvarEstado();
        }
    }
}
