package gateway;

import java.rmi.Naming;
import java.rmi.RemoteException;
import java.util.Scanner;
import java.util.List;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.Path;

/**
 * Cliente
 *
 * Este cliente consome o serviço RMI exposto pelo Gateway, que por sua vez
 * encaminha operações ao ServidorControle. Ele implementa todas as funcionalidades
 * exigidas no PDF: cadastro, login, upload real de arquivos, download, listagem,
 * busca global, exclusão e exibição do hash global do sistema.
 */
public class Cliente {

    private static IGateway gateway;
    private static String usuarioLogado = null;

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        try {
            gateway = (IGateway) Naming.lookup("rmi://localhost/Gateway");
            System.out.println("Cliente conectado ao Gateway via RMI.");
        } catch (Exception e) {
            System.err.println("Erro ao conectar ao RMI: " + e.getMessage());
            return;
        }

        while (true) {

            exibirMenu();
            int opcao;

            try {
                opcao = Integer.parseInt(scanner.nextLine());
            } catch (Exception e) {
                System.out.println("Entrada inválida.");
                continue;
            }

            switch (opcao) {

                case 1:
                    cadastrarUsuario(scanner);
                    break;

                case 2:
                    fazerLogin(scanner);
                    break;

                case 3:
                    enviarArquivo(scanner);
                    break;

                case 4:
                    listarArquivos();
                    break;

                case 5:
                    realizarDownload(scanner);
                    break;

                case 6:
                    excluirArquivo(scanner);
                    break;

                case 7:
                    buscarArquivos(scanner);
                    break;

                case 8: // ★★★ NOVO ★★★
                    atualizarArquivo(scanner);
                    break;

                case 0:
                    System.out.println("Encerrando cliente...");
                    scanner.close();
                    return;

                default:
                    System.out.println("Opção inválida.");
            }

            try {
                String hash = gateway.obterHashEstado();
                System.out.println("\n[HASH GLOBAL DO SISTEMA] " + hash);
            } catch (Exception e) {
                System.out.println("\n[HASH] Não foi possível obter hash global: " + e.getMessage());
            }

        }
    }

    /**
     * Exibe menu principal.
     */
    private static void exibirMenu() {
        System.out.println("\n===== MENU =====");
        System.out.println("1 - Cadastrar Usuário");
        System.out.println("2 - Login");
        System.out.println("3 - Enviar Arquivo (arquivo real)");
        System.out.println("4 - Listar Meus Arquivos");
        System.out.println("5 - Download de Arquivo");
        System.out.println("6 - Excluir Arquivo");
        System.out.println("7 - Buscar Arquivos (Global)");
        System.out.println("8 - Atualizar Arquivo (UPDATE)"); // ★ NOVO ★
        System.out.println("0 - Sair");
        System.out.print("Escolha: ");
    }

    private static void cadastrarUsuario(Scanner scanner) {
        System.out.print("Usuário: ");
        String nome = scanner.nextLine();
        System.out.print("Senha: ");
        String senha = scanner.nextLine();

        try {
            boolean resultado = gateway.cadastrarUsuario(nome, senha);
            System.out.println(resultado ? "Usuário cadastrado com sucesso." : "Nome de usuário já existe.");
        } catch (RemoteException e) {
            System.err.println("Erro no cadastro: " + e.getMessage());
        }
    }

    private static void fazerLogin(Scanner scanner) {
        System.out.print("Usuário: ");
        String nome = scanner.nextLine();
        System.out.print("Senha: ");
        String senha = scanner.nextLine();

        try {
            if (gateway.autenticarUsuario(nome, senha)) {
                usuarioLogado = nome;
                System.out.println("Login realizado com sucesso.");
            } else {
                System.out.println("Usuário ou senha inválidos.");
            }
        } catch (RemoteException e) {
            System.err.println("Erro ao realizar login: " + e.getMessage());
        }
    }

    private static void enviarArquivo(Scanner scanner) {
        if (!usuarioLogadoOK()) return;

        System.out.print("Informe o caminho do arquivo: ");
        String caminho = scanner.nextLine();

        try {
            Path path = Paths.get(caminho);

            if (!Files.exists(path)) {
                System.out.println("Arquivo não encontrado.");
                return;
            }

            byte[] dados = Files.readAllBytes(path);
            String nomeArquivo = path.getFileName().toString();

            String resposta = gateway.enviarArquivos(nomeArquivo, dados, usuarioLogado);
            System.out.println(resposta);

        } catch (Exception e) {
            System.err.println("Erro ao enviar o arquivo: " + e.getMessage());
        }
    }

    private static void listarArquivos() {
        if (!usuarioLogadoOK()) return;

        try {
            List<String> arquivos = gateway.solicitarListagem(usuarioLogado);

            if (arquivos == null || arquivos.isEmpty()) {
                System.out.println("Nenhum arquivo encontrado.");
            } else {
                System.out.println("\nSeus arquivos:");
                arquivos.forEach(System.out::println);
            }

        } catch (RemoteException e) {
            System.err.println("Erro ao listar arquivos: " + e.getMessage());
        }
    }

    private static void realizarDownload(Scanner scanner) {
        if (!usuarioLogadoOK()) return;

        System.out.print("Informe o UID do arquivo para baixar: ");
        String uid = scanner.nextLine();

        try {
            byte[] conteudo = gateway.downloadArquivo(uid);

            if (conteudo != null && conteudo.length > 0) {
                String nomeFinal = "download_" + uid;
                Files.write(Paths.get(nomeFinal), conteudo);

                System.out.println("Arquivo salvo como: " + nomeFinal);
            } else {
                System.out.println("Arquivo não encontrado.");
            }

        } catch (Exception e) {
            System.out.println("Erro ao tentar baixar arquivo: " + e.getMessage());
        }
    }

    private static void excluirArquivo(Scanner scanner) {
        if (!usuarioLogadoOK()) return;

        System.out.print("Digite o UID do arquivo a ser excluído: ");
        String uidExcluir = scanner.nextLine();

        try {
            boolean sucesso = gateway.excluirArquivo(uidExcluir);

            if (sucesso) {
                System.out.println("Arquivo excluído com sucesso.");
            } else {
                System.out.println("Falha ao excluir arquivo.");
            }

        } catch (Exception e) {
            System.err.println("Falha ao excluir arquivo: " + e.getMessage());
        }
    }

    private static void buscarArquivos(Scanner scanner) {
        if (!usuarioLogadoOK()) return;

        System.out.print("Digite parte do nome do arquivo para buscar: ");
        String nomeBusca = scanner.nextLine();

        try {
            List<String> resultados = gateway.buscarArquivos(nomeBusca);

            System.out.println("\n=== RESULTADOS DA BUSCA ===");
            if (resultados == null || resultados.isEmpty()) {
                System.out.println("Nenhum arquivo encontrado.");
            } else {
                resultados.forEach(System.out::println);
            }

        } catch (Exception e) {
            System.out.println("Erro ao realizar busca: " + e.getMessage());
        }
    }

    // ★★★★★ NOVO MÉTODO: UPDATE DE ARQUIVO ★★★★★
    private static void atualizarArquivo(Scanner scanner) {
        if (!usuarioLogadoOK()) return;

        System.out.print("Informe o UID do arquivo a atualizar: ");
        String uid = scanner.nextLine();

        System.out.print("Informe o caminho do novo arquivo: ");
        String caminho = scanner.nextLine();

        try {
            Path path = Paths.get(caminho);

            if (!Files.exists(path)) {
                System.out.println("Arquivo não existe.");
                return;
            }

            byte[] novoConteudo = Files.readAllBytes(path);

            boolean sucesso = gateway.atualizarArquivo(uid, novoConteudo);

            if (sucesso) {
                System.out.println("Arquivo atualizado com sucesso!");
            } else {
                System.out.println("Falha ao atualizar arquivo.");
            }

        } catch (Exception e) {
            System.err.println("Erro ao atualizar arquivo: " + e.getMessage());
        }
    }

    private static boolean usuarioLogadoOK() {
        if (usuarioLogado == null) {
            System.out.println("Você precisa estar logado.");
            return false;
        }
        return true;
    }
}
