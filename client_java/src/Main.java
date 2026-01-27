import java.net.*;

public class Main {
    // args: --server 127.0.0.1 --port 9000 --sem atmost|atleast --timeout 500 --retry 5
    public static void main(String[] args) throws Exception {
        String server = "127.0.0.1";
        int port = 9000;
        String sem = "atmost";
        int timeout = 500;
        int retry = 5;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--server": server = args[++i]; break;
                case "--port": port = Integer.parseInt(args[++i]); break;
                case "--sem": sem = args[++i]; break;
                case "--timeout": timeout = Integer.parseInt(args[++i]); break;
                case "--retry": retry = Integer.parseInt(args[++i]); break;
                default: break;
            }
        }

        boolean atMostOnce = sem.equalsIgnoreCase("atmost");

        // try-with-resources to silence "resource leak: socket not closed"
        try (DatagramSocket sock = new DatagramSocket()) {
            InetAddress ip = InetAddress.getByName(server);
            System.out.println("[client] server=" + server + ":" + port + " sem=" + sem + " timeout=" + timeout + " retry=" + retry);
            Cli cli = new Cli(sock, ip, port, atMostOnce, timeout, retry);
            cli.run();
        }
    }
}