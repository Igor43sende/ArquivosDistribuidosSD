package controle;

import dados.Arquivo;

import org.jgroups.*;
import org.jgroups.blocks.locking.LockService;

import java.util.concurrent.locks.Lock;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;

import util.PersistenciaUtil;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import java.util.concurrent.TimeUnit;

public class ServidorControle extends ReceiverAdapter implements IControle {

    private JChannel canal;          // cluster de controle
    private JChannel canalDados;     // cluster de dados

    private ListaUsuarios listaUsuarios;
    private LockService lockService;

    private List<String> ultimaRespostaListagem = new ArrayList<>();
    // Pode receber byte[] (conteúdo), String (mensagem de erro/not found) ou outro objeto
    private Object ultimaRespostaDownload = null;
    private final Object responseLock = new Object();
    private static final String CAMINHO_ESTADO_CONTROLE = "estado_controle.bin";

    public ServidorControle() {
        this.listaUsuarios = new ListaUsuarios();
    }

    public void iniciar() throws Exception {
        carregarEstado();
        System.out.println("[CONTROLE] Iniciando ServidorControle...");

        canal = new JChannel("controle.xml");
        canal.setReceiver(new ReceiverControle());
        canal.connect("ClusterControle");
        System.out.println("[CONTROLE] Canal CONTROLE conectado: " + canal.getAddress());

        lockService = new LockService(canal);

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
            if (sucesso) salvarEstado();
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
        Lock lock = lockService.getLock(chaveLock);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(5, TimeUnit.SECONDS);
            if (!acquired) return "Outro usuário está acessando esse nome de arquivo.";

            String uid = UUID.randomUUID().toString();
            Arquivo arquivo = new Arquivo(uid, nomeArquivo, conteudo, usuario);

            // Enviar UPLOAD por UNICAST para o coordenador do cluster de dados
            Address dest = canalDados.getView().getMembers().get(0);
            if (dest != null) {
                Message m = new Message(dest, arquivo);
                canalDados.send(m);
            } else {
                canalDados.send(new Message(null, arquivo));
            }

            System.out.println("[CONTROLE] Upload enviado. UID=" + uid);
            return uid;

        } catch (Exception e) {
            return "Erro ao enviar arquivo: " + e.getMessage();
        } finally {
            if (acquired) lock.unlock();
        }
    }

    @Override
    public byte[] downloadArquivo(String uid) {
        synchronized (responseLock) {
            ultimaRespostaDownload = null;
            try {
                // envia pedido GET ao cluster de dados
                canalDados.send(new Message(null, "GET;" + uid));
                System.out.println("[CONTROLE] GET enviado ? UID = " + uid);
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

            // tratar os possíveis tipos de resposta
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
                // caso improvável: outro tipo (ex: Arquivo serializado) — tentamos extrair bytes
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
        try {
            canalDados.send(new Message(null, "DELETE;" + uid));
            System.out.println("[CONTROLE] DELETE enviado → UID=" + uid);
            return true;
        } catch (Exception e) {
            System.err.println("[CONTROLE] Erro ao enviar DELETE: " + e.getMessage());
            return false;
        }
    }

    @Override
    public List<String> solicitarListagem(String usuario) {
        synchronized (responseLock) {
            ultimaRespostaListagem = new ArrayList<>();
            try { canalDados.send(new Message(null, "LIST_USER;" + usuario)); } catch (Exception e) { System.err.println(e.getMessage()); }
            try { responseLock.wait(3000); } catch (InterruptedException e) {}
            return new ArrayList<>(ultimaRespostaListagem);
        }
    }

    @Override
    public List<String> buscarArquivos(String nome) {
        synchronized (responseLock) {
            ultimaRespostaListagem = new ArrayList<>();
            try { canalDados.send(new Message(null, "SEARCH;" + nome)); } catch (Exception e) { System.err.println(e.getMessage()); }
            try { responseLock.wait(3000); } catch (InterruptedException e) {}
            return new ArrayList<>(ultimaRespostaListagem);
        }
    }

    // Receiver do canal de dados: recebe byte[] e List<String>
    // Receiver do canal de dados: recebe byte[] e List<String>
    private class ReceiverDados extends ReceiverAdapter {
        @SuppressWarnings("unchecked")
        @Override
        public void receive(Message msg) {
            // evita processar mensagens originadas por este mesmo nó
            if (msg.getSrc() != null && msg.getSrc().equals(canalDados.getAddress())) return;

            try {
                // 1) Tentar ler buffer binário (usado para enviar conteúdo de arquivo)
                byte[] buf = msg.getBuffer();
                if (buf != null && buf.length > 0) {
                    synchronized (responseLock) {
                        ultimaRespostaDownload = buf;
                        responseLock.notifyAll();
                    }
                    System.out.println("[CONTROLE][DADOS] byte[] recebido (" + buf.length + " bytes) de " + msg.getSrc());
                    return;
                }

                // 2) Se não havia buffer, tentar desserializar objeto (String, List, Arquivo, etc)
                Object o = null;
                try {
                    o = msg.getObject();
                } catch (Throwable t) {
                    // falha ao desserializar: log e sair (não notifica o lock porque não temos resposta válida)
                    System.err.println("[CONTROLE][DADOS] Falha ao desserializar objeto de " + msg.getSrc() + ": " + t.getMessage());
                    t.printStackTrace();
                    return;
                }

                if (o == null) return;

                // resposta para LIST/SEARCH -> lista de strings (uid;nome)
                if (o instanceof List<?>) {
                    List<String> lista = (List<String>) o;
                    synchronized (responseLock) {
                        ultimaRespostaListagem = new ArrayList<>(lista);
                        responseLock.notifyAll();
                    }
                    System.out.println("[CONTROLE][DADOS] Lista recebida de " + msg.getSrc() + ". itens=" + lista.size());
                    return;
                }

                // resposta textual (ex.: NOT_FOUND ou mensagens de erro)
                if (o instanceof String) {
                    String s = (String) o;
                    synchronized (responseLock) {
                        ultimaRespostaDownload = s;
                        responseLock.notifyAll();
                    }
                    System.out.println("[CONTROLE][DADOS] String recebida de " + msg.getSrc() + ": " + s);
                    return;
                }

                // se receberem um objeto Arquivo (metadata replicada), apenas logar ou atualizar cache
                if (o instanceof dados.Arquivo) {
                    dados.Arquivo arq = (dados.Arquivo) o;
                    System.out.println("[CONTROLE][DADOS] Metadado Arquivo recebido de " + msg.getSrc() + ": UID=" + arq.getUid() + " nome=" + arq.getNome());
                    // opcional: atualizar lista/cache de metadados se desejar
                    return;
                }

                System.out.println("[CONTROLE][DADOS] Mensagem inesperada de " + msg.getSrc() + ": " + o.getClass().getName());
            } catch (Exception e) {
                System.err.println("[CONTROLE][DADOS] Erro ao processar mensagem: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }



    // Receiver do canal controle
    private class ReceiverControle extends ReceiverAdapter {
        @Override
        public void receive(Message msg) {
            try {
                if (msg.getSrc() != null && msg.getSrc().equals(canal.getAddress())) return;

                Object o = msg.getObject();
                if (o instanceof String) {
                    String texto = (String) o;
                    if (texto.startsWith("CADASTRO:")) {
                        String[] p = texto.split(":");
                        if (p.length >= 3) {
                            listaUsuarios.cadastrarUsuario(p[1], p[2]);
                            salvarEstado();
                            System.out.println("[CONTROLE][CTRL] Cadastro replicado: " + p[1]);
                        }
                    }
                    return;
                }
                if (o instanceof ListaUsuarios) {
                    listaUsuarios = (ListaUsuarios) o;
                    salvarEstado();
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
        }
    }

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
    }

    private void salvarEstado() {
        PersistenciaUtil.salvarObjeto(listaUsuarios, CAMINHO_ESTADO_CONTROLE);
    }

    private void carregarEstado() {
        ListaUsuarios estado = PersistenciaUtil.carregarObjeto(CAMINHO_ESTADO_CONTROLE);
        if (estado != null) listaUsuarios = estado;
    }

    @Override
    public String obterHashEstado() {
        try { return listaUsuarios.gerarHashEstado(); } catch (Exception e) { return "ERRO_HASH"; }
    }

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
