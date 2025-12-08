package gateway;

import controle.ServidorControle;

import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.Scanner;

/**
 * MainGateway
 *
 * Este é o PONTO DE ENTRADA do sistema do lado do servidor.
 * Ele inicia:
 *
 *  1. O ServidorControle (cluster JGroups: autenticação, locks, operações)
 *  2. O RMI Registry (porta 1099), se ainda não existir
 *  3. O Gateway, que expõe as operações via RMI para os clientes
 *
 * Depois disso, a aplicação permanece ativa até ENTER ser pressionado.
 *
 * Em resumo: este arquivo integra o mundo RMI com o mundo JGroups.
 */
public class MainGateway {

    public static void main(String[] args) {
        try {
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
                // Tenta criar o registry — funciona apenas se nenhum registry estiver rodando.
                registry = LocateRegistry.createRegistry(1099);
                System.out.println("[MainGateway] Registry criado na porta 1099.");
            } catch (Exception ex) {
                // Se já existe, ele simplesmente obtém sua referência.
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
            System.out.println("   Porta: 1099                                   ");
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
