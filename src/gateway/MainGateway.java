package gateway;

import controle.ServidorControle;

import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.Scanner;

/**
 * MainGateway
 *
 * Sobe:
 *  - o ServidorControle (cluster de controle)
 *  - o Registry RMI (porta 1099)
 *  - o Gateway, exposto como serviço remoto RMI
 *
 * Mantém a aplicação ativa até que o usuário pressione ENTER.
 */
public class MainGateway {

    public static void main(String[] args) {
        try {
            System.out.println("[MainGateway] Iniciando ServidorControle...");
            ServidorControle controle = new ServidorControle();
            controle.iniciar();

            System.out.println("[MainGateway] Criando Gateway...");
            IGateway gateway = new Gateway(controle);

            System.out.println("[MainGateway] Criando/obtendo Registry RMI na porta 1099...");
            Registry registry;
            try {
                registry = LocateRegistry.createRegistry(1099);
                System.out.println("[MainGateway] Registry criado na porta 1099.");
            } catch (Exception ex) {
                System.out.println("[MainGateway] Registry já existente. Obtendo referência...");
                registry = LocateRegistry.getRegistry(1099);
            }

            System.out.println("[MainGateway] Registrando objeto Gateway como 'Gateway'...");
            registry.rebind("Gateway", gateway);

            System.out.println("\n=================================================");
            System.out.println("   Gateway ONLINE e registrado no RMI Registry   ");
            System.out.println("   Nome do serviço: Gateway                      ");
            System.out.println("   Porta: 1099                                   ");
            System.out.println("=================================================\n");

            // Mantém o processo ativo enquanto o servidor estiver rodando
            System.out.println("Pressione ENTER para encerrar o Gateway...");
            new Scanner(System.in).nextLine();

        } catch (Exception e) {
            System.err.println("[MainGateway] Erro fatal:");
            e.printStackTrace();
        }
    }
}
