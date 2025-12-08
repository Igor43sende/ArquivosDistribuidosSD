package controle;

import dados.Arquivo;

import org.jgroups.*;

import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.Queue;
import java.util.LinkedList;

import util.PersistenciaUtil;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.security.MessageDigest;   // ★★ HASH GLOBAL ★★
import java.nio.charset.StandardCharsets; // ★★ HASH GLOBAL ★★

public class ServidorControle extends ReceiverAdapter implements IControle {

    private JChannel canal;          // cluster de controle
    private JChannel canalDados;     // cluster de dados

    private ListaUsuarios listaUsuarios;

    private List<String> ultimaRespostaListagem = new ArrayList<>();

    // Pode receber byte[] (conteúdo), String (erros), ou resposta HASH
    private Object ultimaRespostaDownload = null;

    // ★★ HASH GLOBAL ★★
    private String ultimaRespostaHashArquivos = null;

    private final Object responseLock = new Object();
    private static final String CAMINHO_ESTADO_CONTROLE = "estado_controle.bin";

    // ---- Lock distribuído (coordenador do cluster Controle) ----
    private final Map<String, Address> lockOwners = new HashMap<>();
    private final Map<String, Queue<Address>> lockFilas = new HashMap<>();

    // Estruturas locais para aguardar LOCK_GRANTED
    private final Map<String, Object> monitoresLock = new HashMap<>();
    private final Map<String, Boolean> lockConcedido = new HashMap<>();

    // ---- NOVO: controle de versão do estado de CONTROLE ----
    private long stateVersion = 0;        // versão local do estado
    private long versaoCoordenador = 0;   // versão reportada pelo coordenador
    private final Object stateLock = new Object(); // para sincronizar recebimento de STATE_VERSION_RESP


    public ServidorControle() {
        this.listaUsuarios = new ListaUsuarios();
    }

    // Incrementa a versão sempre que o estado de controle muda
    private void incrementarVersao() {
        stateVersion++;
    }

    public void iniciar() throws Exception {
        carregarEstado();
        System.out.println("[CONTROLE] Iniciando ServidorControle...");

        canal = new JChannel("controle.xml");
        canal.setReceiver(new ReceiverControle());
        canal.connect("ClusterControle");
        System.out.println("[CONTROLE] Canal CONTROLE conectado: " + canal.getAddress());

        canalDados = new JChannel("dados.xml");
        canalDados.setReceiver(new ReceiverDados());
        canalDados.connect("ClusterDados");
        System.out.println("[CONTROLE] Canal DADOS conectado: " + canalDados.getAddress());

        if (!canal.getAddress().equals(canal.getView().getMembers().get(0))) {
            System.out.println("[CONTROLE] Solicitando estado do coordenador...");
            canal.getState(null, 10000);
        } else {
            System.out.println("[CONTROLE] Coordenador inicial do ClusterControle.");
        }
    }

    @Override
    public boolean autenticarUsuario(String nomeUsuario, String senha) {
        Usuario u = listaUsuarios.autenticarUsuario(nomeUsuario, senha);
        boolean ok = (u != null);
        System.out.println("[CONTROLE] autenticarUsuario('" + nomeUsuario + "') = " + ok);
        return ok;
    }

    @Override
    public boolean cadastrarUsuario(String nomeUsuario, String senha) {
        try {
            boolean sucesso = listaUsuarios.cadastrarUsuario(nomeUsuario, senha);
            if (sucesso) {
                salvarEstado();
                incrementarVersao(); // NOVO: estado de controle mudou
            }
            canal.send(new Message(null, "CADASTRO:" + nomeUsuario + ":" + senha));
            return sucesso;
        } catch (Exception e) {
            System.err.println("[CONTROLE] Erro ao cadastrar: " + e.getMessage());
            return false;
        }
    }

    @Override
    public String enviarArquivos(String nomeArquivo, byte[] conteudo, String usuario) {
        String chaveLock = "file:name:" + nomeArquivo;

        boolean acquired = adquirirLockDistribuido(chaveLock, 5000);
        if (!acquired) {
            return "Outro usuário está acessando esse nome de arquivo.";
        }

        try {
            String uid = UUID.randomUUID().toString();
            Arquivo arquivo = new Arquivo(uid, nomeArquivo, conteudo, usuario);

            Address dest = canalDados.getView().getMembers().get(0);
            if (dest != null) {
                canalDados.send(new Message(null, arquivo));
            } else {
                canalDados.send(new Message(null, arquivo));
            }

            System.out.println("[CONTROLE] Upload enviado. UID=" + uid);

            // Opcional: se você considerar que mudanças de arquivos também contam
            // como parte do "estado global", pode incrementar a versão aqui
            // incrementarVersao();

            return uid;

        } catch (Exception e) {
            return "Erro ao enviar arquivo: " + e.getMessage();
        } finally {
            liberarLockDistribuido(chaveLock);
        }
    }

    @Override
    public boolean atualizarArquivo(String uid, byte[] novoConteudo) {
        String chaveLock = "file:uid:" + uid;

        boolean acquired = adquirirLockDistribuido(chaveLock, 5000);
        if (!acquired) {
            System.err.println("[CONTROLE][UPDATE] Não foi possível adquirir lock para UID=" + uid);
            return false;
        }

        try {
            long ts = System.currentTimeMillis();

            Arquivo atualizado = new Arquivo(uid, null, novoConteudo, null);
            atualizado.setUpdate(true);
            atualizado.setTimestamp(ts);

            Address dest = canalDados.getView().getMembers().get(0);
            if (dest != null) {
                canalDados.send(new Message(null, atualizado));
            } else {
                canalDados.send(new Message(null, atualizado));
            }

            System.out.println("[CONTROLE][UPDATE] Atualização enviada → UID=" + uid + " TS=" + ts);

            // Opcional: idem ao comentário do upload
            // incrementarVersao();

            return true;

        } catch (Exception e) {
            System.err.println("[CONTROLE][UPDATE] Erro ao atualizar arquivo UID=" + uid + ": " + e.getMessage());
            return false;
        } finally {
            liberarLockDistribuido(chaveLock);
        }
    }

    @Override
    public byte[] downloadArquivo(String uid) {
        synchronized (responseLock) {
            ultimaRespostaDownload = null;
            ultimaRespostaListagem = new ArrayList<>();
            ultimaRespostaHashArquivos = null;

            try {
                canalDados.send(new Message(null, "GET;" + uid));
                System.out.println("[CONTROLE] GET enviado → UID=" + uid);
            } catch (Exception e) {
                throw new RuntimeException("Erro no envio GET", e);
            }

            try {
                responseLock.wait(5000); // espera até 5s por resposta
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            if (ultimaRespostaDownload == null) {
                throw new RuntimeException("Nenhuma resposta recebida do cluster de dados.");
            }

            if (ultimaRespostaDownload instanceof byte[]) {
                return (byte[]) ultimaRespostaDownload;
            } else if (ultimaRespostaDownload instanceof String) {
                String s = (String) ultimaRespostaDownload;
                if ("NOT_FOUND".equals(s)) {
                    throw new RuntimeException("Arquivo não encontrado no cluster de dados.");
                } else {
                    throw new RuntimeException("Resposta inesperada do cluster de dados: " + s);
                }
            } else {
                Object o = ultimaRespostaDownload;
                if (o instanceof dados.Arquivo) {
                    dados.Arquivo arq = (dados.Arquivo) o;
                    if (arq.getConteudo() != null) return arq.getConteudo();
                }
                throw new RuntimeException("Tipo de resposta desconhecido do cluster de dados: " + o.getClass());
            }
        }
    }

    @Override
    public boolean excluirArquivo(String uid) {

        String chaveLock = "file:uid:" + uid;

        // 1) tenta adquirir lock distribuído
        boolean acquired = adquirirLockDistribuido(chaveLock, 5000);
        if (!acquired) {
            System.err.println("[CONTROLE][DELETE] Não foi possível adquirir lock para UID=" + uid);
            return false;
        }

        try {
            // 2) envia comando DELETE para o cluster de dados
            canalDados.send(new Message(null, "DELETE;" + uid));
            System.out.println("[CONTROLE] DELETE enviado → UID=" + uid);

            // Opcional: idem upload/update
            // incrementarVersao();

            return true;

        } catch (Exception e) {
            System.err.println("[CONTROLE][DELETE] Erro ao enviar DELETE: " + e.getMessage());
            return false;

        } finally {
            // 3) libera lock
            liberarLockDistribuido(chaveLock);
        }
    }


    @Override
    public List<String> solicitarListagem(String usuario) {
        synchronized (responseLock) {
            ultimaRespostaListagem = new ArrayList<>();
            ultimaRespostaDownload = null;
            ultimaRespostaHashArquivos = null;

            try {
                canalDados.send(new Message(null, "LIST_USER;" + usuario));
            } catch (Exception e) {
                System.err.println("[CONTROLE] Erro ao enviar LIST_USER: " + e.getMessage());
            }

            try {
                responseLock.wait(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            return new ArrayList<>(ultimaRespostaListagem);
        }
    }

    @Override
    public List<String> buscarArquivos(String nome) {
        synchronized (responseLock) {
            ultimaRespostaListagem = new ArrayList<>();
            ultimaRespostaDownload = null;
            ultimaRespostaHashArquivos = null;

            try {
                canalDados.send(new Message(null, "SEARCH;" + nome));
            } catch (Exception e) {
                System.err.println("[CONTROLE] Erro ao enviar SEARCH: " + e.getMessage());
            }

            try {
                responseLock.wait(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            return new ArrayList<>(ultimaRespostaListagem);
        }
    }

    // ------------------------------------------------------------
    //   Receiver do canal de dados
    // ------------------------------------------------------------
    private class ReceiverDados extends ReceiverAdapter {
        @SuppressWarnings("unchecked")
        @Override
        public void receive(Message msg) {
            if (msg.getSrc() != null && msg.getSrc().equals(canalDados.getAddress()))
                return;

            try {
                Object o = msg.getObject();
                if (o == null) return;

                // 1) LIST_USER / SEARCH → lista de strings (uid;nome)
                if (o instanceof List<?> lista) {
                    synchronized (responseLock) {
                        ultimaRespostaListagem = new ArrayList<>((List<String>) lista);
                        ultimaRespostaDownload = null;
                        // não mexe em ultimaRespostaHashArquivos
                        responseLock.notifyAll();
                    }
                    System.out.println("[CONTROLE][DADOS] Lista recebida. itens=" + lista.size());
                    return;
                }

                // 2) String → pode ser:
                //    - "NOT_FOUND" (resposta de GET)
                //    - hash dos metadados de arquivos (resposta de "HASH")
                if (o instanceof String s) {
                    synchronized (responseLock) {
                        if ("NOT_FOUND".equals(s)) {
                            ultimaRespostaDownload = s;
                        } else {
                            // assumimos que aqui é resposta de HASH
                            ultimaRespostaHashArquivos = s;
                        }
                        responseLock.notifyAll();
                    }
                    System.out.println("[CONTROLE][DADOS] String recebida: " + s);
                    return;
                }

                // 3) Arquivo vindo do cluster de dados (download)
                if (o instanceof dados.Arquivo arq) {
                    if (arq.getConteudo() != null && arq.getConteudo().length > 0) {
                        synchronized (responseLock) {
                            ultimaRespostaDownload = arq;
                            ultimaRespostaListagem = new ArrayList<>();
                            responseLock.notifyAll();
                        }
                        System.out.println("[CONTROLE][DADOS] Arquivo recebido para download. UID=" +
                                arq.getUid() + " bytes=" + arq.getConteudo().length);
                    } else {
                        System.out.println("[CONTROLE][DADOS] Metadado Arquivo recebido: UID=" +
                                arq.getUid() + " nome=" + arq.getNome());
                    }
                    return;
                }

                // 4) Qualquer outra coisa
                System.out.println("[CONTROLE][DADOS] Objeto inesperado: " + o.getClass());

            } catch (Exception e) {
                System.err.println("[CONTROLE][DADOS] Erro ao processar mensagem: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    // ------------------------------------------------------------
    //   Receiver do canal de CONTROLE
    // ------------------------------------------------------------
    private class ReceiverControle extends ReceiverAdapter {
        @Override
        public void receive(Message msg) {
            try {
                if (msg.getSrc() != null && msg.getSrc().equals(canal.getAddress())) return;

                Object o = msg.getObject();
                if (o instanceof String) {
                    String texto = (String) o;

                    // ---- NOVO: Protocolo de versão de estado ----
                    if (texto.equals("STATE_VERSION_REQ")) {
                        if (ehCoordenadorControle()) {
                            try {
                                canal.send(new Message(msg.getSrc(), "STATE_VERSION_RESP;" + stateVersion));
                                System.out.println("[CONTROLE] STATE_VERSION_REQ recebido. Enviando versao=" + stateVersion);
                            } catch (Exception e) {
                                System.err.println("[CONTROLE] Erro ao enviar STATE_VERSION_RESP: " + e.getMessage());
                            }
                        }
                        return;
                    }

                    if (texto.startsWith("STATE_VERSION_RESP;")) {
                        try {
                            String[] p = texto.split(";", 2);
                            versaoCoordenador = Long.parseLong(p[1]);
                            System.out.println("[CONTROLE] STATE_VERSION_RESP recebido. versaoCoordenador=" + versaoCoordenador);
                        } catch (Exception e) {
                            System.err.println("[CONTROLE] Erro ao parsear STATE_VERSION_RESP: " + e.getMessage());
                        }
                        synchronized (stateLock) {
                            stateLock.notifyAll();
                        }
                        return;
                    }

                    // ---- Protocolo de lock distribuído ----
                    if (texto.startsWith("LOCK_REQ:")) {
                        if (ehCoordenadorControle()) {
                            String chave = texto.substring("LOCK_REQ:".length());
                            processarLockReq(chave, msg.getSrc());
                        }
                        return;
                    }

                    if (texto.startsWith("LOCK_REL:")) {
                        if (ehCoordenadorControle()) {
                            String chave = texto.substring("LOCK_REL:".length());
                            processarLockRelease(chave, msg.getSrc());
                        }
                        return;
                    }

                    if (texto.startsWith("LOCK_GRANTED:")) {
                        String chave = texto.substring("LOCK_GRANTED:".length());
                        sinalizarLockConcedido(chave);
                        return;
                    }

                    // ---- Replicação de cadastro de usuários ----
                    if (texto.startsWith("CADASTRO:")) {
                        String[] p = texto.split(":");
                        if (p.length >= 3) {
                            listaUsuarios.cadastrarUsuario(p[1], p[2]);
                            salvarEstado();
                            incrementarVersao(); // NOVO: replica também conta como mudança de estado
                            System.out.println("[CONTROLE][CTRL] Cadastro replicado: " + p[1]);
                        }
                        return;
                    }

                    return;
                }

                if (o instanceof ListaUsuarios) {
                    listaUsuarios = (ListaUsuarios) o;
                    salvarEstado();
                    // recebemos snapshot do coordenador → alinhamos nossa versão com a dele
                    stateVersion = versaoCoordenador;
                    System.out.println("[CONTROLE][CTRL] Estado de usuarios recebido e aplicado.");
                    return;
                }

            } catch (Exception e) {
                System.err.println("[CONTROLE][CTRL] Erro: " + e.getMessage());
            }
        }

        @Override
        public void viewAccepted(View view) {
            System.out.println("[CONTROLE][CTRL] Nova view: " + view);

            boolean souCoordenador = ehCoordenadorControle();
            if (souCoordenador) {
                System.out.println("[CONTROLE][CTRL] Eu sou o coordenador do cluster de controle.");
                return;
            }

            // Nó secundário: verifica se está desatualizado em relação ao coordenador
            try {
                Address coord = view.getMembers().get(0);
                if (coord == null) return;

                // pede versão de estado ao coordenador
                canal.send(new Message(coord, "STATE_VERSION_REQ"));
                System.out.println("[CONTROLE][CTRL] STATE_VERSION_REQ enviado ao coordenador...");

                synchronized (stateLock) {
                    stateLock.wait(1000); // espera até 1s pela resposta
                }

                System.out.println("[CONTROLE][CTRL] Minha stateVersion=" + stateVersion +
                        " | versaoCoordenador=" + versaoCoordenador);

                if (versaoCoordenador > stateVersion) {
                    System.out.println("[CONTROLE][CTRL] Estou desatualizado. Solicitando getState()...");
                    canal.getState(null, 5000);
                } else {
                    System.out.println("[CONTROLE][CTRL] Estado já sincronizado ou não há diferença relevante.");
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                System.err.println("[CONTROLE][CTRL] Erro ao sincronizar estado: " + e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------
    //   Estado replicado do canal de CONTROLE (listaUsuarios)
    // ------------------------------------------------------------
    @Override
    public void getState(OutputStream out) throws Exception {
        synchronized (listaUsuarios) {
            ObjectOutputStream oos = new ObjectOutputStream(out);
            oos.writeObject(listaUsuarios);
            oos.flush();
        }
    }

    @Override
    public void setState(InputStream in) throws Exception {
        ObjectInputStream ois = new ObjectInputStream(in);
        listaUsuarios = (ListaUsuarios) ois.readObject();
        salvarEstado();
        // Se setState foi chamado, provavelmente viemos de um getState solicitado
        // após receber STATE_VERSION_RESP; alinhamos com versaoCoordenador
        stateVersion = versaoCoordenador;
        System.out.println("[CONTROLE] Estado de usuarios restaurado via getState/setState.");
    }

    private void salvarEstado() {
        PersistenciaUtil.salvarObjeto(listaUsuarios, CAMINHO_ESTADO_CONTROLE);
    }

    private void carregarEstado() {
        ListaUsuarios estado = PersistenciaUtil.carregarObjeto(CAMINHO_ESTADO_CONTROLE);
        if (estado != null) {
            listaUsuarios = estado;
            System.out.println("[CONTROLE] Estado de usuarios carregado do disco.");
        }
    }

    // ------------------------------------------------------------
    //   ★★ HASH GLOBAL ★★
    // ------------------------------------------------------------
    @Override
    public String obterHashGlobal() {
        String hashUsuarios;
        String hashArquivosLocal;

        // 1) Hash dos usuários (já existente na ListaUsuarios)
        try {
            hashUsuarios = listaUsuarios.gerarHashEstado();
        } catch (Exception e) {
            hashUsuarios = "ERRO_USUARIOS";
        }

        // 2) Pedir hash dos metadados de arquivos ao cluster de dados
        synchronized (responseLock) {
            ultimaRespostaHashArquivos = null;
            ultimaRespostaDownload = null;
            ultimaRespostaListagem = new ArrayList<>();

            try {
                canalDados.send(new Message(null, "HASH"));
            } catch (Exception e) {
                return "ERRO_ENVIO_HASH";
            }

            try {
                responseLock.wait(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        hashArquivosLocal = (ultimaRespostaHashArquivos != null)
                ? ultimaRespostaHashArquivos
                : "ERRO_ARQUIVOS";

        // 3) Combinar ambos e aplicar SHA-256
        String combinado = hashUsuarios + ":" + hashArquivosLocal;
        return sha256(combinado);
    }

    // Função auxiliar SHA-256
    private String sha256(String str) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(str.getBytes(StandardCharsets.UTF_8));

            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();

        } catch (Exception e) {
            return "ERRO_SHA256";
        }
    }

    // ------------------------------------------------------------
    //   Métodos auxiliares para LOCK DISTRIBUÍDO
    // ------------------------------------------------------------

    private boolean ehCoordenadorControle() {
        try {
            View v = canal.getView();
            return v != null && canal.getAddress().equals(v.getMembers().get(0));
        } catch (Exception e) {
            return false;
        }
    }

    private Address getCoordenadorControle() {
        try {
            View v = canal.getView();
            if (v == null || v.getMembers().isEmpty()) return null;
            return v.getMembers().get(0);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean adquirirLockDistribuido(String chaveLock, long timeoutMs) {
        Address coord = getCoordenadorControle();
        if (coord == null) {
            System.err.println("[CONTROLE][LOCK] Nenhum coordenador disponível para lock em " + chaveLock);
            return false;
        }

        Object monitor;
        synchronized (monitoresLock) {
            monitor = monitoresLock.get(chaveLock);
            if (monitor == null) {
                monitor = new Object();
                monitoresLock.put(chaveLock, monitor);
            }
            lockConcedido.put(chaveLock, Boolean.FALSE);
        }

        try {
            canal.send(new Message(coord, "LOCK_REQ:" + chaveLock));
        } catch (Exception e) {
            System.err.println("[CONTROLE][LOCK] Erro ao enviar LOCK_REQ: " + e.getMessage());
            return false;
        }

        long deadline = System.currentTimeMillis() + timeoutMs;
        boolean ok;

        synchronized (monitor) {
            while (true) {
                Boolean concedido;
                synchronized (monitoresLock) {
                    concedido = lockConcedido.get(chaveLock);
                }
                if (Boolean.TRUE.equals(concedido)) {
                    ok = true;
                    break;
                }
                long restante = deadline - System.currentTimeMillis();
                if (restante <= 0) {
                    ok = false;
                    break;
                }
                try {
                    monitor.wait(restante);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    ok = false;
                    break;
                }
            }
        }

        if (!ok) {
            System.err.println("[CONTROLE][LOCK] Timeout ao aguardar lock para " + chaveLock);
            synchronized (monitoresLock) {
                lockConcedido.remove(chaveLock);
            }
        }

        return ok;
    }

    private void liberarLockDistribuido(String chaveLock) {
        Address coord = getCoordenadorControle();
        if (coord == null) return;

        try {
            canal.send(new Message(coord, "LOCK_REL:" + chaveLock));
        } catch (Exception e) {
            System.err.println("[CONTROLE][LOCK] Erro ao enviar LOCK_REL: " + e.getMessage());
        } finally {
            synchronized (monitoresLock) {
                lockConcedido.remove(chaveLock);
            }
        }
    }

    private void sinalizarLockConcedido(String chaveLock) {
        Object monitor;
        synchronized (monitoresLock) {
            lockConcedido.put(chaveLock, Boolean.TRUE);
            monitor = monitoresLock.get(chaveLock);
        }
        if (monitor != null) {
            synchronized (monitor) {
                monitor.notifyAll();
            }
        }
        System.out.println("[CONTROLE][LOCK] Lock concedido para " + chaveLock);
    }

    private void processarLockReq(String chaveLock, Address solicitante) {
        synchronized (lockOwners) {
            Address atual = lockOwners.get(chaveLock);
            if (atual == null) {
                lockOwners.put(chaveLock, solicitante);
                try {
                    canal.send(new Message(solicitante, "LOCK_GRANTED:" + chaveLock));
                } catch (Exception e) {
                    System.err.println("[CONTROLE][LOCK] Erro ao enviar LOCK_GRANTED: " + e.getMessage());
                }
            } else {
                Queue<Address> fila = lockFilas.get(chaveLock);
                if (fila == null) {
                    fila = new LinkedList<>();
                    lockFilas.put(chaveLock, fila);
                }
                fila.add(solicitante);
                System.out.println("[CONTROLE][LOCK] Lock ocupado para " + chaveLock +
                        ", solicitante enfileirado: " + solicitante);
            }
        }
    }

    private void processarLockRelease(String chaveLock, Address solicitante) {
        synchronized (lockOwners) {
            Address atual = lockOwners.get(chaveLock);
            if (atual == null || !atual.equals(solicitante)) return;

            Queue<Address> fila = lockFilas.get(chaveLock);
            if (fila != null && !fila.isEmpty()) {
                Address prox = fila.poll();
                lockOwners.put(chaveLock, prox);
                try {
                    canal.send(new Message(prox, "LOCK_GRANTED:" + chaveLock));
                } catch (Exception e) {
                    System.err.println("[CONTROLE][LOCK] Erro ao enviar LOCK_GRANTED (fila): " + e.getMessage());
                }
            } else {
                lockOwners.remove(chaveLock);
            }
        }

        System.out.println("[CONTROLE][LOCK] Lock liberado para " + chaveLock + " por " + solicitante);
    }

    // ------------------------------------------------------------
    //   main()
    // ------------------------------------------------------------
    public static void main(String[] args) {
        ServidorControle s = null;
        try {
            s = new ServidorControle();
            s.iniciar();
            System.out.println("ServidorControle em execução. Pressione ENTER para desligar...");
            new java.util.Scanner(System.in).nextLine();
            s.salvarEstado();
        } catch (Exception e) {
            e.printStackTrace();
            if (s != null) try { s.salvarEstado(); } catch (Exception ignored) {}
        }
    }
}
