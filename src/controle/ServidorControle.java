package controle;

import org.jgroups.JChannel;
import org.jgroups.Message;
import org.jgroups.ReceiverAdapter;
import org.jgroups.View;
import org.jgroups.Address;

public class ServidorControle extends ReceiverAdapter {

    private JChannel canal;

    public void iniciar() throws Exception {
        try {
            canal = new JChannel("jgroups.xml");
            canal.setReceiver(this);
            canal.connect("ClusterArquivos");

            System.out.println("ServidorControle conectado ao cluster 'ClusterArquivos'.");

            // Pausa pequena para garantir que o outro nó tenha tempo de se juntar
            Thread.sleep(2000);

            // Teste de comunicação automática
            enviarMensagem("Olá do ServidorControle!");

        } catch (Exception e) {
            System.err.println("Erro ao iniciar o ServidorControle: " + e.getMessage());
            e.printStackTrace();
            throw e;
        }
    }

    // Recebe mensagens de outros nós
    @Override
    public void receive(Message msg) {
        Address remetente = msg.getSrc();

        // FILTRO DE REDUNDÂNCIA: Se o remetente for este próprio canal, ignora.
        if (remetente != null && remetente.equals(canal.getAddress())) {
            return;
        }

        // Apenas processa mensagens de OUTROS membros.
        System.out.println("[CONTROLE] Mensagem recebida de OUTRO Servidor: " + msg.getObject());
    }

    // Exibe mudança de membros do cluster
    @Override
    public void viewAccepted(View newView) {
        System.out.println("[CONTROLE] Nova view: " + newView);
    }

    // Envia mensagens para o cluster
    public void enviarMensagem(String msg) throws Exception {
        canal.send(null, msg);
        System.out.println("[CONTROLE] Mensagem enviada automaticamente: " + msg);
    }

    public static void main(String[] args) {
        try {
            ServidorControle servidor = new ServidorControle();
            servidor.iniciar();

            // Mantém o servidor rodando para receber mensagens
            new java.util.Scanner(System.in).nextLine();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}