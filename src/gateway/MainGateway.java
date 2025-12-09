package gateway;

import controle.ServidorControle;

import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.Scanner;

public class MainGateway {

    public static void main(String[] args) {
        try {
            // ============================================================
            // DEFINIÇÃO DO HOSTNAME EXTERNO PARA RMI
            // ============================================================
            System.setProperty("java.rmi.server.hostname", "192.168.15.4");
            // TROQUE PELO SEU IP REAL DA REDE

            // ------------------------------------------------------------
            // 1) Subida do ServidorControle
            // ------------------------------------------------------------
            System.out.println("[MainGateway] Iniciando ServidorControle...");
            ServidorControle controle = new ServidorControle();
            controle.iniciar();

            // ------------------------------------------------------------
            // 2) Criação do Gateway (objeto remoto RMI)
            // ------------------------------------------------------------
            System.out.println("[MainGateway] Criando Gateway...");
            IGateway gateway = new Gateway(controle);

            // ------------------------------------------------------------
            // 3) Inicialização do Registry RMI (porta 1099)
            // ------------------------------------------------------------
            System.out.println("[MainGateway] Criando/obtendo Registry RMI na porta 1099...");
            Registry registry;
            try {
                registry = LocateRegistry.createRegistry(1099);
                System.out.println("[MainGateway] Registry criado na porta 1099.");
            } catch (Exception ex) {
                System.out.println("[MainGateway] Registry já existente. Obtendo referência...");
                registry = LocateRegistry.getRegistry(1099);
            }

            // ------------------------------------------------------------
            // 4) Registro do serviço remoto
            // ------------------------------------------------------------
            System.out.println("[MainGateway] Registrando objeto Gateway como 'Gateway'...");
            registry.rebind("Gateway", gateway);

            // ------------------------------------------------------------
            // 5) Mensagem de status
            // ------------------------------------------------------------
            System.out.println("\n=================================================");
            System.out.println("   Gateway ONLINE e registrado no RMI Registry   ");
            System.out.println("   Nome do serviço: Gateway                      ");
            System.out.println("   Endereço: rmi://192.168.15.4/Gateway     ");
            System.out.println("=================================================\n");

            // ------------------------------------------------------------
            // 6) Mantém o servidor ativo
            // ------------------------------------------------------------
            System.out.println("Pressione ENTER para encerrar o Gateway...");
            new Scanner(System.in).nextLine();

        } catch (Exception e) {
            System.err.println("[MainGateway] Erro fatal:");
            e.printStackTrace();
        }
    }
}
