package org.eidasvalidation.sandbox;

import org.eidasvalidation.engine.ValidationException;
import org.eidasvalidation.i18n.Messages;
import org.eidasvalidation.report.Report;
import org.eidasvalidation.report.ReportJson;
import org.eidasvalidation.service.ValidationService;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Entry point of the worker process. Loads trust data once, then validates one document per
 * request until stdin closes. Everything that can go wrong with a document happens here, in a
 * process the parent can kill.
 *
 * <p>Test hooks, enabled only by system properties the parent sets deliberately:
 * {@code eidas.worker.hangOn=<displayName>} sleeps forever on that document;
 * {@code eidas.worker.crashOn=<displayName>} halts the JVM on that document.
 */
public final class Worker {

    private Worker() {
    }

    public static void main(String[] args) throws IOException {
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        // Nothing but protocol may reach stdout; the engine logs through SLF4J to stderr.
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));

        String hello = in.readLine();
        if (hello == null) {
            return;
        }
        ValidationService service;
        try {
            service = ValidationService.create(WorkerProtocol.read(hello, WorkerProtocol.Hello.class).toOptions());
        } catch (IOException | RuntimeException e) {
            out.println(WorkerProtocol.FAILED + e.getMessage());
            System.exit(2);
            return;
        }
        out.println(WorkerProtocol.READY + WorkerProtocol.write(service.trust()));

        String hangOn = System.getProperty("eidas.worker.hangOn");
        String crashOn = System.getProperty("eidas.worker.crashOn");

        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            WorkerProtocol.Request req;
            try {
                req = WorkerProtocol.read(line, WorkerProtocol.Request.class);
            } catch (IllegalArgumentException e) {
                out.println(WorkerProtocol.write(WorkerProtocol.Response.failure("failure", e.getMessage())));
                continue;
            }
            if (hangOn != null && hangOn.equals(req.displayName())) {
                sleepForever();
            }
            if (crashOn != null && crashOn.equals(req.displayName())) {
                Runtime.getRuntime().halt(3);
            }
            out.println(WorkerProtocol.write(handle(service, req)));
        }
    }

    private static WorkerProtocol.Response handle(ValidationService service, WorkerProtocol.Request req) {
        try {
            Report report = service.validate(Path.of(req.document()), req.displayName(), Instant.parse(req.at()),
                    req.online(), Messages.forTag(req.lang()));
            return WorkerProtocol.Response.success(ReportJson.compact(report));
        } catch (ValidationException e) {
            return WorkerProtocol.Response.failure("unreadable", e.getMessage());
        } catch (Throwable t) {
            // StackOverflowError, parser bugs, anything: report it rather than die quietly.
            return WorkerProtocol.Response.failure("failure", t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : ": " + t.getMessage()));
        }
    }

    private static void sleepForever() {
        while (true) {
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException ignored) {
                // keep hanging; this is the test hook's purpose
            }
        }
    }
}
