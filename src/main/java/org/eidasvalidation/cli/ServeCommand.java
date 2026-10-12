package org.eidasvalidation.cli;

import org.eidasvalidation.i18n.Messages;
import org.eidasvalidation.web.LocalServer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;

/** Starts the local web interface. */
@Command(name = "serve", mixinStandardHelpOptions = true,
        description = "Start the local web interface on 127.0.0.1 and keep running until interrupted.")
public final class ServeCommand implements Callable<Integer> {

    @Option(names = "--port", paramLabel = "<port>", defaultValue = "8765",
            description = "TCP port on the loopback interface (default: 8765).")
    int port;

    @Option(names = "--lang", paramLabel = "<tag>", description = "Default language of the pages, e.g. en or de; "
            + "a browser's Accept-Language header takes precedence when it names a supported language.")
    String lang;

    @Mixin
    TrustOptions trust;

    @Override
    public Integer call() throws IOException, InterruptedException {
        LocalServer server;
        try {
            server = new LocalServer(trust.toOptions(), port, Messages.forTag(lang));
            server.start();
        } catch (IOException | RuntimeException e) {
            System.err.println("Cannot start the web interface: " + e.getMessage());
            return Main.EXIT_ERROR;
        }
        System.out.println("eidas-validator web interface: " + server.url());
        System.out.println("Bound to the loopback interface only. Press Ctrl+C to stop.");

        CountDownLatch stop = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            stop.countDown();
        }));
        stop.await();
        return 0;
    }
}
