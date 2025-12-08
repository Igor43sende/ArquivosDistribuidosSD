package dados;

import org.jgroups.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.ArrayList;
import util.PersistenciaUtil;

// Servidor responsável pelo cluster de DADOS.
// Cada instância:
//  - entra no grupo "ClusterDados" via JGroups;
//  - armazena arquivos fisicamente em disco (por UID);
//  - mantém metadados dos arquivos em ListaArquivos;
//  - responde comandos vindos do ServidorControle (GET, LIST_USER, SEARCH, DELETE, HASH);
//  - trata upload, update e replicação de metadados entre nós;
//  - implementa controle de versão de estado (stateVersion) + sincronização via getState/setState.
public class ServidorDados extends ReceiverAdapter {

    private JChannel canal;
    private ListaArquivos listaArquivos;
    private final String DIRETORIO_BASE;
    private final String CAMINHO_ESTADO_DADOS;

    // Controle de versão do estado local, usado para saber se um nó está defasado
    private long stateVersion = 0;
    private long versaoCoordenador = 0;

    // Lock usado por nós secundários para esperar resposta de versão do coordenador
    private final Object stateLock = new Object();


    public ServidorDados() {
        // Usa um ID aleatório para que cada nó tenha seu repositório e arquivo de estado separados.
        String id = java.util.UUID.randomUUID().toString().substring(0, 8);

        this.DIRETORIO_BASE = "repositorio_" + id + "/";
        this.CAMINHO_ESTADO_DADOS = "estado_dados_" + id + ".bin";
    }


    // Inicializa o servidor de dados:
    //  - carrega estado salvo (se existir)
    //  - garante diretório base
    //  - conecta ao cluster "ClusterDados"
    //  - identifica se é coordenador ou nó secundário
    public void iniciar() throws Exception {
        carregarEstado();
        new File(DIRETORIO_BASE).mkdirs();

        canal = new JChannel("dados.xml");
        canal.setReceiver(this);
        canal.connect("ClusterDados");

        boolean souCoordenador = canal.getAddress().equals(canal.getView().getMembers().get(0));
        if (souCoordenador) {
            System.out.println("[DADOS] Coordenador inicial do grupo.");
        } else {
            System.out.println("[DADOS] Nó secundário conectado ao cluster 'ClusterDados'.");
        }

        System.out.println("[DADOS] ServidorDados conectado ao cluster 'ClusterDados'. Address=" + canal.getAddress());
    }

    // Incrementa a versão do estado sempre que há modificação
    // Sempre que a ListaArquivos muda (upload, update, delete), incrementamos stateVersion
    // para permitir que outros nós saibam se estão desatualizados.

    private void incrementarVersao() {
        stateVersion++;
    }

    // Salva arquivo LOCALMENTE, sem replicação de metadados
    //  - garante timestamp para o Arquivo (se ainda for 0);
    //  - registra na ListaArquivos;
    //  - grava o conteúdo em disco (se tiver);
    //  - salva o estado em arquivo binário.
    private void salvarArquivoLocal(Arquivo arquivo) throws Exception {

        if (arquivo.getTimestamp() == 0) {
            arquivo.setTimestamp(System.currentTimeMillis());
        }

        listaArquivos.adicionarArquivo(arquivo);
        incrementarVersao(); // estado mudou

        if (arquivo.getConteudo() != null && arquivo.getConteudo().length > 0) {
            try (FileOutputStream fos = new FileOutputStream(DIRETORIO_BASE + arquivo.getUid())) {
                fos.write(arquivo.getConteudo());
            }
        }

        salvarEstado();
    }

    // Chamado apenas pelo nó que recebe o upload real: salva e replica metadado
    // Este método é usado apenas para o nó que recebe o arquivo do ServidorControle.
    //  - salva o arquivo localmente (conteúdo em disco + metadados);
    //  - cria um Arquivo "meta" sem conteúdo;
    //  - replica esse metadado para os demais nós do cluster via JGroups.
    public void salvarArquivoComReplicacao(Arquivo arquivo) throws Exception {

        salvarArquivoLocal(arquivo);

        Arquivo meta = new Arquivo(arquivo.getUid(), arquivo.getNome(), null, arquivo.getUsuario());

        meta.setTimestamp(arquivo.getTimestamp());

        try {
            canal.send(new Message(null, meta));
        } catch (Exception e) {
            System.err.println("[DADOS] Erro ao replicar metadado: " + e.getMessage());
            e.printStackTrace();
        }

        System.out.println("[DADOS] Arquivo salvo localmente. UID=" + arquivo.getUid());
    }

    // Método principal de recepção de mensagens do cluster de dados.
    //  - comandos de texto (LIST_USER, GET, DELETE, SEARCH, HASH, STATE_VERSION_REQ/RESP)
    //  - objetos Arquivo (upload, metadados, updates)
    @Override
    public void receive(Message msg) {
        if (msg.getSrc() != null && msg.getSrc().equals(canal.getAddress())) return;

        Object obj;
        try {
            obj = msg.getObject();
        } catch (Exception e) {
            System.err.println("[DADOS] Erro ao desserializar objeto recebido: " + e.getMessage());
            e.printStackTrace();
            return;
        }

        System.out.println("[DADOS] receive() de " + msg.getSrc() + " tipo=" + (obj != null ? obj.getClass().getSimpleName() : "NULL"));

        // Aqui são tratados comandos internos "STATE_VERSION_REQ" e "STATE_VERSION_RESP"
        // para sincronização de nós reingressantes.
        if (obj instanceof String s) {

            // Pedido de versão enviado para o coordenador
            if (s.equals("STATE_VERSION_REQ")) {
                // Apenas o coordenador responde
                Address coordenador = canal.getView().getMembers().get(0);
                if (canal.getAddress().equals(coordenador)) {
                    try {
                        Message resp = new Message(msg.getSrc(), "STATE_VERSION_RESP;" + stateVersion);
                        canal.send(resp);
                        System.out.println("[DADOS] STATE_VERSION_REQ recebido. Enviando versao=" + stateVersion + " para " + msg.getSrc());
                    } catch (Exception e) {
                        System.err.println("[DADOS] Erro ao enviar STATE_VERSION_RESP: " + e.getMessage());
                    }
                }
                return;
            }

            // Resposta do coordenador para o nó reingressante
            if (s.startsWith("STATE_VERSION_RESP;")) {
                try {
                    String[] p = s.split(";", 2);
                    versaoCoordenador = Long.parseLong(p[1]);
                    System.out.println("[DADOS] STATE_VERSION_RESP recebido. versaoCoordenador=" + versaoCoordenador);
                } catch (Exception e) {
                    System.err.println("[DADOS] Erro ao parsear STATE_VERSION_RESP: " + e.getMessage());
                }
                synchronized (stateLock) {
                    stateLock.notifyAll();
                }
                return;
            }

            // Caso não seja comando de versão de estado, trata como comando normal
            tratarComando(s, msg.getSrc());
            return;
        }

        // Caso seja um Arquivo, pode ser:
        //  - upload real (com conteúdo)
        //  - metadado replicado (sem conteúdo)
        //  - atualização (update = true)
        if (obj instanceof Arquivo arquivo) {

            if (arquivo.isUpdate()) {
                try {
                    atualizarArquivo(arquivo);
                } catch (Exception e) {
                    System.err.println("[DADOS] Erro ao atualizar arquivo: " + e.getMessage());
                }
                return;
            }

            try {
                if (arquivo.getConteudo() != null && arquivo.getConteudo().length > 0) {
                    salvarArquivoComReplicacao(arquivo);
                    System.out.println("[DADOS] Arquivo recebido (com conteúdo) e salvo. UID=" + arquivo.getUid());
                } else {
                    salvarArquivoLocal(arquivo);
                    System.out.println("[DADOS] Metadado recebido e aplicado. UID=" + arquivo.getUid());
                }
            } catch (Exception e) {
                System.err.println("[DADOS] Erro ao salvar arquivo/metadado recebido: " + e.getMessage());
                e.printStackTrace();
            }
            return;
        }

        System.out.println("[DADOS] Mensagem inesperada: " + (obj != null ? obj.getClass().getName() : "NULL"));
    }

    // Atualização real de arquivo (overwrite).
    // Este método aplica um UPDATE:
    //  - garante timestamp;
    //  - ignora updates atrasados;
    //  - grava novo conteúdo no disco;
    //  - atualiza ListaArquivos com nova versão;
    //  - replica metadado atualizado para os outros nós.
    private void atualizarArquivo(Arquivo arquivo) throws Exception {

        if (arquivo.getTimestamp() == 0) {
            arquivo.setTimestamp(System.currentTimeMillis());
        }

        Arquivo anterior = listaArquivos.buscarPorUid(arquivo.getUid());

        if (anterior == null) {
            System.out.println("[DADOS] UPDATE recebido para UID inexistente, tratando como novo upload. UID=" + arquivo.getUid());
            salvarArquivoComReplicacao(arquivo);
            return;
        }

        if (anterior.getTimestamp() > arquivo.getTimestamp()) {
            System.out.println("[DADOS] Ignorando UPDATE atrasado para UID=" + arquivo.getUid());
            return;
        }

        if (arquivo.getConteudo() != null) {
            try (FileOutputStream fos = new FileOutputStream(DIRETORIO_BASE + arquivo.getUid())) {
                fos.write(arquivo.getConteudo());
            }
        }

        Arquivo atualizado = new Arquivo(
                anterior.getUid(),
                anterior.getNome(),
                arquivo.getConteudo(),
                anterior.getUsuario()
        );
        atualizado.setTimestamp(arquivo.getTimestamp());

        listaArquivos.atualizarArquivo(atualizado);
        incrementarVersao(); // estado mudou
        salvarEstado();

        Arquivo meta = new Arquivo(
                atualizado.getUid(),
                atualizado.getNome(),
                null,
                atualizado.getUsuario()
        );
        meta.setTimestamp(atualizado.getTimestamp());
        canal.send(new Message(null, meta));

        System.out.println("[DADOS] UPDATE aplicado e replicado. UID=" + atualizado.getUid());
    }

    // Trata comandos em texto vindos do ServidorControle (via cluster de dados).
    // Comandos suportados:
    //  - LIST_USER;<usuario>
    //  - GET;<uid>
    //  - DELETE;<uid>
    //  - SEARCH;<nome>
    //  - HASH
    private void tratarComando(String comando, Address remetente) {
        if (comando == null || comando.trim().isEmpty()) return;
        String[] partes = comando.split(";", 2);
        String acao = partes[0];

        System.out.println("[DADOS] Comando recebido de " + remetente + " -> " + comando);

        switch (acao) {

            case "UPDATE": {
                // não usado diretamente aqui, update vem como Arquivo.isUpdate()
                break;
            }

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
                    if (a.getConteudo() != null && a.getConteudo().length > 0) {
                        conteudo = a.getConteudo();
                        encontrado = true;
                    } else {
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

                        Arquivo resposta = new Arquivo(
                                uid,
                                a != null ? a.getNome() : "desconhecido",
                                conteudo,
                                a != null ? a.getUsuario() : null
                        );

                        resposta.setTimestamp(a.getTimestamp());

                        Message m = new Message(remetente, resposta);
                        canal.send(m);

                        System.out.println("[DADOS] Enviado Arquivo UID=" + uid +
                                " para " + remetente + " bytes=" + conteudo.length);
                    } else {
                        Message m = new Message(remetente, "NOT_FOUND");
                        canal.send(m);
                        System.out.println("[DADOS] Arquivo UID=" + uid +
                                " não encontrado. Enviado NOT_FOUND para " + remetente);
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
                    incrementarVersao(); // estado mudou
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

             // Quando o ServidorControle solicitar "HASH",
             // Enviamos o hash dos metadados da ListaArquivos.

            case "HASH": {
                try {
                    String hash = listaArquivos.gerarHashMetadados();
                    Message resposta = new Message(remetente, hash);
                    canal.send(resposta);
                    System.out.println("[DADOS] HASH solicitado → enviado hash de metadados para " + remetente);
                } catch (Exception e) {
                    System.err.println("[DADOS] Erro ao enviar HASH: " + e.getMessage());
                }
                break;
            }

            default:
                System.out.println("[DADOS] Comando desconhecido: " + comando);
        }
    }

    // Chamado sempre que a visão do cluster muda (entrada/saída de nós).
    // Aqui o nó:
    //  - verifica se é coordenador;
    //  - se NÃO for, pede ao coordenador a versão do estado;
    //  - se estiver desatualizado, pede o snapshot completo via getState().
    @Override
    public void viewAccepted(View view) {
        System.out.println("[DADOS] Nova view: " + view);
        boolean souCoordenador = view.getMembers().get(0).equals(canal.getAddress());

        if (souCoordenador) {
            System.out.println("[DADOS] Eu sou o coordenador do cluster.");
            return;
        }

        try {
            // 1) Solicita a versão do estado ao coordenador
            Address coordenador = view.getMembers().get(0);
            Message req = new Message(coordenador, "STATE_VERSION_REQ");
            canal.send(req);
            System.out.println("[DADOS] Solicitando versao de estado ao coordenador " + coordenador);

            // 2) Aguarda resposta
            synchronized (stateLock) {
                stateLock.wait(1000); // até 1 segundo
            }

            System.out.println("[DADOS] Minha stateVersion=" + stateVersion + " | versaoCoordenador=" + versaoCoordenador);

            // 3) Se estiver desatualizado, pede snapshot
            if (versaoCoordenador > stateVersion) {
                System.out.println("[DADOS] Estado desatualizado. Solicitando snapshot via getState()...");
                canal.getState(null, 5000);
            } else {
                System.out.println("[DADOS] Estado já sincronizado ou não há diferença relevante.");
            }

        } catch (Exception e) {
            System.err.println("[DADOS] Erro ao sincronizar estado no viewAccepted: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // Envia o snapshot completo de ListaArquivos para um nó que chamou getState().
    @Override
    public void getState(OutputStream output) throws Exception {
        synchronized (listaArquivos) {
            ObjectOutputStream oos = new ObjectOutputStream(output);
            oos.writeObject(listaArquivos);
            oos.flush();
        }
    }

    // Recebe snapshot completo de outro nó (coordenador) e substitui o estado local.
    // Também regrava arquivos em disco para garantir consistência física.
    @Override
    public void setState(InputStream input) throws Exception {
        ObjectInputStream ois = new ObjectInputStream(input);
        ListaArquivos estado = (ListaArquivos) ois.readObject();
        synchronized (listaArquivos) {
            listaArquivos = estado;
            // ao receber snapshot do coordenador, iguala a versão local à dele
            stateVersion = versaoCoordenador;
        }
        salvarEstado();
        for (Arquivo a : listaArquivos.listarArquivos()) {
            if (a.getConteudo() != null && a.getConteudo().length > 0) {
                try (FileOutputStream fos = new FileOutputStream(DIRETORIO_BASE + a.getUid())) {
                    fos.write(a.getConteudo());
                } catch (IOException ignored) {}
            }
        }
        System.out.println("[DADOS] setState() aplicado. stateVersion=" + stateVersion);
    }

    // Salva o estado da ListaArquivos em disco (arquivo binário).
    private void salvarEstado() {
        if (listaArquivos != null) PersistenciaUtil.salvarObjeto(listaArquivos, CAMINHO_ESTADO_DADOS);
    }

    // Carrega o estado da ListaArquivos do disco, ou cria uma lista vazia se não existir.
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

    // Ponto de entrada para executar um ServidorDados standalone.
    // Fica rodando até o usuário apertar ENTER no terminal.
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
