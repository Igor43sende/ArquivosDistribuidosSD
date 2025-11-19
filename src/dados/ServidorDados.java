package dados;
import org.jgroups.JChannel;
import org.jgroups.Message;
import org.jgroups.ReceiverAdapter;
import org.jgroups.View;
import org.jgroups.Address;

public class ServidorDados extends ReceiverAdapter {

    private JChannel canal;

    public void iniciar() throws Exception {
        try {
            canal = new JChannel("jgroups.xml");
            canal.setReceiver(this);
            canal.connect("ClusterArquivos");

            System.out.println("ServidorDados conectado ao cluster 'ClusterArquivos'.");

            // Pausa pequena para garantir que o outro nó tenha tempo de se juntar
            Thread.sleep(2000);

            // Teste de comunicação automática
            enviarMensagem("Olá do ServidorDados!");

        } catch (Exception e) {
            System.err.println("Erro ao iniciar o ServidorDados: " + e.getMessage());
            e.printStackTrace();
            throw e;
        }
    }

    // Recebe mensagens
    @Override
    public void receive(Message msg) {
        Address remetente = msg.getSrc();

        // FILTRO DE REDUNDÂNCIA: Se o remetente for este próprio canal, ignora.
        if (remetente != null && remetente.equals(canal.getAddress())) {
            return;
        }

        // Apenas processa mensagens de OUTROS membros.
        System.out.println("[DADOS] Mensagem recebida de OUTRO Servidor: " + msg.getObject());
    }

    // Notificação de alteração no cluster
    @Override
    public void viewAccepted(View newView) {
        System.out.println("[DADOS] Nova view: " + newView);
    }

    // Envia mensagens
    public void enviarMensagem(String msg) throws Exception {
        canal.send(null, msg);
        System.out.println("[DADOS] Mensagem enviada automaticamente: " + msg);
    }

    public static void main(String[] args) {
        try {
            ServidorDados servidor = new ServidorDados();
            servidor.iniciar();

            // Mantém o servidor rodando para receber mensagens
            new java.util.Scanner(System.in).nextLine();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}