package org.eidasvalidation.cli;

import org.eidasvalidation.i18n.Messages;
import org.eidasvalidation.web.LocalServer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;

/** Starts the web interface. */
@Command(name = "serve", mixinStandardHelpOptions = true,
        description = "Start the web interface and keep running until interrupted. Listens on 127.0.0.1 by default.")
public final class ServeCommand implements Callable<Integer> {

    @Option(names = "--port", paramLabel = "<port>", defaultValue = "8765",
            description = "TCP port (default: 8765).")
    int port;

    @Option(names = "--bind", paramLabel = "<address>", defaultValue = "127.0.0.1",
            description = "Address to listen on (default: 127.0.0.1). Use 0.0.0.0 inside a container; the "
                    + "interface has no authentication, so expose it only through a port mapping or a reverse proxy.")
    String bind;

    @Option(names = "--allow-host", paramLabel = "<name>",
            description = "Additional host name (with optional :port) accepted in the Host and Origin headers, "
                    + "e.g. the name a reverse proxy serves. Repeatable. Loopback names are always accepted.")
    List<String> allowHosts = new ArrayList<>();

    @Option(names = "--lang", paramLabel = "<tag>", description = "Default language of the pages, e.g. en, de or hr; "
            + "a browser's Accept-Language header takes precedence when it names a supported language.")
    String lang;

    @Mixin
    TrustOptions trust;

    @Override
    public Integer call() throws IOException, InterruptedException {
        InetAddress address;
        try {
            address = InetAddress.getByName(bind);
        } catch (UnknownHostException e) {
            System.err.println("Cannot resolve --bind address: " + bind);
            return Main.EXIT_ERROR;
        }
        LocalServer server;
        try {
            server = new LocalServer(trust.toOptions(), address, port, Set.copyOf(allowHosts), Messages.forTag(lang));
            server.start();
        } catch (IOException | RuntimeException e) {
            System.err.println("Cannot start the web interface: " + e.getMessage());
            return Main.EXIT_ERROR;
        }
        System.out.println("eidas-validator web interface: " + server.url());
        if (address.isLoopbackAddress()) {
            System.out.println("Bound to the loopback interface only. Press Ctrl+C to stop.");
        } else {
            System.out.println("Bound to " + address.getHostAddress() + ". The interface has no authentication: "
                    + "publish the port on 127.0.0.1 or behind an authenticating reverse proxy. Press Ctrl+C to stop.");
        }

        CountDownLatch stop = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            stop.countDown();
        }));
        stop.await();
        return 0;
    }
}
