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
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

// Classe principal do cluster de CONTROLE.
// Ela coordena:
//  - autenticação e cadastro de usuários
//  - comunicação com cluster de dados (upload, download, listagem…)
//  - replicação de estado (ListaUsuarios) entre nós JGroups
//  - controle de versão do estado via STATE_VERSION_REQ/RESP
//  - implementação de um LOCK DISTRIBUÍDO atuando como coordenador do cluster Controle
//  - obtenção do HASH GLOBAL do sistema (usuários + arquivos)

public class ServidorControle extends ReceiverAdapter implements IControle {

    // Dois canais JGroups:
    //  canal  → cluster de CONTROLE (sincroniza usuários, locks, versão, etc.)
    //  canalDados → cluster de DADOS (onde ficam os arquivos)
    private JChannel canal;
    private JChannel canalDados;

    // Estrutura que guarda todos os usuários cadastrados no sistema
    private ListaUsuarios listaUsuarios;

    // Estas variáveis guardam temporariamente respostas vindas do cluster de dados:
    //  - listagem de arquivos
    //  - download (byte[] ou objeto Arquivo)
    //  - hash dos metadados (para o hash global)
    //
    // Elas são usadas porque a comunicação é assíncrona, então
    // o ServidorControle precisa esperar pela resposta usando wait()/notify().
    private List<String> ultimaRespostaListagem = new ArrayList<>();
    private Object ultimaRespostaDownload = null;
    private String ultimaRespostaHashArquivos = null;

    // Usado para sincronizar as esperas por respostas vindas do cluster de dados.
    private final Object responseLock = new Object();

    // Persistência local do estado do cluster de controle (ListaUsuarios),
    // garantindo durabilidade mesmo se a máquina for desligada.
    private static final String CAMINHO_ESTADO_CONTROLE = "estado_controle.bin";

    // ----- BLOCO DO LOCK DISTRIBUÍDO -----
    // O cluster de CONTROLE implementa um mecanismo próprio de locks:
    //
    //  - Apenas o coordenador decide quem recebe o lock.
    //  - Solicitações LOCK_REQ são enviadas ao coordenador.
    //  - Há uma fila para cada chave de lock.
    //  - O coordenador responde com LOCK_GRANTED.
    //
    //  Isso garante exclusão mútua entre uploads/updates/deletes.
    private final Map<String, Address> lockOwners = new HashMap<>();
    private final Map<String, Queue<Address>> lockFilas = new HashMap<>();
    private final Map<String, Object> monitoresLock = new HashMap<>();
    private final Map<String, Boolean> lockConcedido = new HashMap<>();


    // ----- CONTROLE DE VERSÃO DO ESTADO -----
    // Cada alteração em ListaUsuarios incrementa stateVersion.
    // Nós não-coordenadores perguntam ao coordenador qual é sua versão.
    // Se estiverem defasados, fazem getState() automaticamente.
    private long stateVersion = 0;
    private long versaoCoordenador = 0;
    private final Object stateLock = new Object();



    public ServidorControle() {
        this.listaUsuarios = new ListaUsuarios();
    }

    // Incrementa a versão sempre que o estado de controle muda
    private void incrementarVersao() {
        stateVersion++;
    }

    public void iniciar() throws Exception {

        // Carrega estado local salvo em disco
        carregarEstado();
        System.out.println("[CONTROLE] Iniciando ServidorControle...");

        // Conecta ao cluster de CONTROLE
        canal = new JChannel("controle.xml");
        canal.setReceiver(new ReceiverControle());
        canal.connect("ClusterControle");
        System.out.println("[CONTROLE] Canal CONTROLE conectado: " + canal.getAddress());

        // Conecta ao cluster de DADOS
        canalDados = new JChannel("dados.xml");
        canalDados.setReceiver(new ReceiverDados());
        canalDados.connect("ClusterDados");
        System.out.println("[CONTROLE] Canal DADOS conectado: " + canalDados.getAddress());

        // Se não sou coordenador, peço getState() para sincronizar ListaUsuarios
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

    // Cadastro de usuários com replicação:
    //  1) Salva no estado local
    //  2) Repassa mensagem CADASTRO:... ao cluster de controle
    //  3) Outros nós aplicam a mudança e também salvam em disco
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

    // UPLOAD DE ARQUIVO:
    //  - Para evitar conflitos, adquire lock distribuído baseado no nome do arquivo.
    //  - Gera UID, empacota Arquivo e envia ao coordenador do cluster de dados.
    //  - Aguarda e libera lock ao final.
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

            return uid;

        } catch (Exception e) {
            return "Erro ao enviar arquivo: " + e.getMessage();
        } finally {
            liberarLockDistribuido(chaveLock);
        }
    }

    // UPDATE DISTRIBUÍDO:
    // Mesmo protocolo do upload, mas:
    //  - O nome não é alterado
    //  - O campo update=true informa ao cluster de dados que é atualização
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

    // LISTAGEM E BUSCA:
    // Funcionam de maneira semelhante:
    //  - Mandam comando textual ao cluster de dados
    //  - Esperam uma List<String> no ReceiverDados
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

    // RECEIVER DO CLUSTER DE DADOS:
    // Interpreta todas as respostas vindas dos ServidoresDados:
    //
    //  - List<String> → resultado de SEARCH/LIST_USER
    //  - String → "NOT_FOUND" ou HASH de arquivos
    //  - Arquivo → resposta de download
    //
    // É aqui que as respostas são armazenadas nas variáveis temporárias
    // e liberam as threads que estavam aguardando notify().
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

    // RECEIVER DO CLUSTER DE CONTROLE:
    // Aqui são tratadas mensagens importantes:
    //
    //  - STATE_VERSION_REQ / RESP → protocolo de sincronização incremental
    //  - LOCK_REQ / REL / GRANTED → implementação do lock distribuído
    //  - CADASTRO:... → replicação de novos usuários
    //
    // Também trata eventos de viewAccepted para detectar troca de coordenador.
    private class ReceiverControle extends ReceiverAdapter {
        @Override
        public void receive(Message msg) {
            try {
                if (msg.getSrc() != null && msg.getSrc().equals(canal.getAddress())) return;

                Object o = msg.getObject();
                if (o instanceof String) {
                    String texto = (String) o;

                    // ---- Protocolo de versão de estado ----
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
                            incrementarVersao(); // Replica também conta como mudança de estado
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
            // Quando a composição do cluster muda:
            //  - Se sou coordenador, apenas aviso
            //  - Se não sou, comparo minha versão com a do coordenador
            //    e faço getState() apenas se estiver desatualizado.
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

    // getState/setState:
    // Replicam ListaUsuarios para novos nós ou nós recuperando estado.
    // Isso garante que o cluster de controle esteja sempre consistente
    // mesmo após falhas, quedas ou novas instâncias entrando.
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

    // ---------- HASH GLOBAL ----------
    // Junta:
    //   1) Hash dos usuários (gerado pela ListaUsuarios)
    //   2) Hash dos arquivos (pedido ao cluster de dados)
    // e aplica SHA-256 sobre ambos.
    //
    // Esse valor final representa o "estado completo do sistema".

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

    // ----- LOCK DISTRIBUÍDO -----
    // Protocolo de 3 operações:
    //
    //  - adquirirLockDistribuido():
    //         envia LOCK_REQ e espera LOCK_GRANTED
    //
    //  - liberarLockDistribuido():
    //         informa ao coordenador que terminou
    //
    //  - processarLockReq() / processarLockRelease():
    //         lógica do coordenador para fila de locks
    //
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

    public static void main(String[] args) {

        // Inicializa todo o servidor, conecta aos clusters e aguarda ENTER.
        // Salva estado antes de sair.
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
