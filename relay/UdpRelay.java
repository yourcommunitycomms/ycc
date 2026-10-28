import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Tiny Your Community Communications repeater: forwards every packet it receives to every other client heard in the
 * last minute. It never decrypts anything (it has no channel key). Useful to join devices that
 * cannot broadcast to each other: emulators, separate subnets, a mesh gateway.
 *
 * Run: java UdpRelay.java [port [bindAddress [dropPercent]]]   (default 47400, all interfaces, 0)
 * Clients: put "<relay-ip>:47400" in Extra peers.
 */
public class UdpRelay {
    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 47400;
        // Optional bind address, e.g. 127.0.0.1 when only local emulators need it.
        DatagramSocket s = args.length > 1
                ? new DatagramSocket(new InetSocketAddress(args[1], port))
                : new DatagramSocket(new InetSocketAddress(port));
        // Optional third arg: percent of forwarded packets to drop, to test loss handling.
        double drop = args.length > 2 ? Double.parseDouble(args[2]) / 100.0 : 0;
        java.util.Random rnd = new java.util.Random();
        Map<SocketAddress, Long> clients = new HashMap<>();
        byte[] buf = new byte[2048];
        DatagramPacket in = new DatagramPacket(buf, buf.length);
        // Optional fourth arg: fixed clients "host:port,host:port" (e.g. a phone on the LAN). The relay
        // pokes them every 10 s, so a host firewall sees their replies as answers and lets them in.
        final java.util.List<java.net.InetSocketAddress> fixed = new java.util.ArrayList<>();
        if (args.length > 3) for (String hp : args[3].split(",")) {
            int c = hp.lastIndexOf(':');
            fixed.add(new java.net.InetSocketAddress(hp.substring(0, c), Integer.parseInt(hp.substring(c + 1))));
        }
        if (!fixed.isEmpty()) {
            final DatagramSocket sock = s;
            Thread poke = new Thread(() -> {
                byte[] hi = {'U', 'B'}; // too short to be a packet: receivers ignore it
                while (true) {
                    for (java.net.InetSocketAddress a : fixed) {
                        try {
                            sock.send(new DatagramPacket(hi, hi.length, a));
                        } catch (Exception ignored) {
                        }
                    }
                    try {
                        Thread.sleep(10000);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            });
            poke.setDaemon(true);
            poke.start();
        }
        System.out.println("Your Community Communications relay on UDP " + port + (fixed.isEmpty() ? "" : ", fixed clients " + fixed));
        long packets = 0;
        while (true) {
            in.setLength(buf.length);
            s.receive(in);
            long now = System.currentTimeMillis();
            SocketAddress from = in.getSocketAddress();
            if (clients.put(from, now) == null) System.out.println("client " + from);
            Iterator<Map.Entry<SocketAddress, Long>> it = clients.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<SocketAddress, Long> c = it.next();
                if (now - c.getValue() > 60000) {
                    it.remove();
                    continue;
                }
                if (!c.getKey().equals(from) && (drop == 0 || rnd.nextDouble() >= drop)) s.send(new DatagramPacket(buf, in.getLength(), c.getKey()));
            }
            if (++packets % 500 == 0) System.out.println(packets + " packets, " + clients.size() + " clients");
        }
    }
}
