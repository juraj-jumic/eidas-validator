package org.eidasvalidation.cli;

import org.eidasvalidation.i18n.Messages;
import org.eidasvalidation.sandbox.SandboxedValidator;
import org.eidasvalidation.service.ValidationService;
import org.eidasvalidation.service.ValidatorOptions;
import org.eidasvalidation.web.LocalServer;

import java.time.Duration;
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

    @Option(names = "--no-sandbox", description = "Parse documents in this process instead of a supervised worker "
            + "JVM (faster start, no isolation from hostile files).")
    boolean noSandbox;

    @Option(names = "--sandbox-timeout", paramLabel = "<seconds>", defaultValue = "60",
            description = "Wall-clock limit per document in the worker (default: 60).")
    long sandboxTimeout;

    @Option(names = "--sandbox-memory", paramLabel = "<MB>", defaultValue = "1024",
            description = "Heap cap of the worker JVM in megabytes (default: 1024).")
    int sandboxMemory;

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
        ValidatorOptions options = trust.toOptions();
        LocalServer.ValidatorFactory factory = noSandbox
                ? () -> ValidationService.create(options)
                : () -> SandboxedValidator.start(options, SandboxedValidator.Settings.defaults()
                        .withJobTimeout(Duration.ofSeconds(sandboxTimeout)).withMaxHeapMb(sandboxMemory));
        LocalServer server;
        try {
            server = new LocalServer(options, address, port, Set.copyOf(allowHosts), Messages.forTag(lang), factory);
            server.start();
        } catch (IOException | RuntimeException e) {
            System.err.println("Cannot start the web interface: " + e.getMessage());
            return Main.EXIT_ERROR;
        }
        System.out.println("eidas-validator web interface: " + server.url());
        System.out.println(noSandbox ? "Documents are parsed in this process (--no-sandbox)."
                : "Documents are parsed in a supervised worker JVM (" + sandboxMemory + " MB, " + sandboxTimeout + " s per document).");
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
