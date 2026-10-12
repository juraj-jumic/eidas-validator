package org.eidasvalidation.sandbox;

import org.eidasvalidation.engine.TrustSummary;
import org.eidasvalidation.engine.ValidationException;
import org.eidasvalidation.i18n.Messages;
import org.eidasvalidation.report.Report;
import org.eidasvalidation.report.ReportJson;
import org.eidasvalidation.service.Validator;
import org.eidasvalidation.service.ValidatorOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Validates documents in a supervised worker JVM ({@link Worker}). The worker loads trust data
 * once and handles jobs one at a time. A job that overruns its time limit gets the worker
 * killed; a worker that dies mid-job is reported as crashed; either way the next job starts a
 * fresh worker. The parent process, and with it the web interface, survives whatever a hostile
 * PDF does to the parser.
 *
 * <p>Limits on the worker: heap cap ({@code -Xmx}), exit on OutOfMemoryError, its own temp
 * directory, and the wall-clock timeout per job. Network access is governed by the guarded
 * fetcher inside the worker exactly as in-process.
 */
public final class SandboxedValidator implements Validator {

    private static final Logger LOG = LoggerFactory.getLogger(SandboxedValidator.class);

    /**
     * @param jobTimeout   wall-clock limit per document
     * @param startTimeout limit for the worker to load trust data and report ready
     * @param maxHeapMb    worker heap cap in megabytes
     * @param extraJvmArgs additional JVM arguments for the worker (tests use this for the hooks)
     */
    public record Settings(Duration jobTimeout, Duration startTimeout, int maxHeapMb, List<String> extraJvmArgs) {
        public static Settings defaults() {
            return new Settings(Duration.ofSeconds(60), Duration.ofMinutes(5), 1024, List.of());
        }

        public Settings withJobTimeout(Duration d) {
            return new Settings(d, startTimeout, maxHeapMb, extraJvmArgs);
        }

        public Settings withMaxHeapMb(int mb) {
            return new Settings(jobTimeout, startTimeout, mb, extraJvmArgs);
        }

        public Settings withExtraJvmArgs(List<String> args) {
            return new Settings(jobTimeout, startTimeout, maxHeapMb, List.copyOf(args));
        }
    }

    private final ValidatorOptions options;
    private final Settings settings;
    private final ExecutorService reader = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sandbox-reader");
        t.setDaemon(true);
        return t;
    });

    private Process process;
    private BufferedWriter toWorker;
    private BufferedReader fromWorker;
    private Path tempDir;
    private TrustSummary trust;
    private Instant started;
    private boolean closed;

    private SandboxedValidator(ValidatorOptions options, Settings settings) {
        this.options = Objects.requireNonNull(options);
        this.settings = Objects.requireNonNull(settings);
    }

    /** Starts the worker and waits until it has loaded trust data. */
    public static SandboxedValidator start(ValidatorOptions options, Settings settings) throws IOException {
        SandboxedValidator v = new SandboxedValidator(options, settings);
        v.spawn();
        return v;
    }

    @Override
    public TrustSummary trust() {
        return trust;
    }

    @Override
    public boolean isStale() {
        return options.trustedLists() && !options.offline() && started != null
                && Instant.now().isAfter(started.plus(options.tlMaxAge()));
    }

    /** For diagnostics and tests. */
    public synchronized boolean isWorkerAlive() {
        return process != null && process.isAlive();
    }

    @Override
    public synchronized Report validate(Path document, String displayName, Instant at, boolean online, Messages messages) {
        if (closed) {
            throw new IllegalStateException("validator is closed");
        }
        try {
            if (!isWorkerAlive()) {
                spawn();
            }
        } catch (IOException e) {
            throw new SandboxException(SandboxException.Kind.START_FAILED, "worker could not be started: " + e.getMessage(), e);
        }

        WorkerProtocol.Request req = new WorkerProtocol.Request(document.toAbsolutePath().toString(), displayName,
                at.toString(), online, messages.tag());
        String line;
        try {
            toWorker.write(WorkerProtocol.write(req));
            toWorker.newLine();
            toWorker.flush();
            line = readLine(settings.jobTimeout());
        } catch (IOException e) {
            kill();
            throw new SandboxException(SandboxException.Kind.CRASHED, "worker connection lost: " + e.getMessage(), e);
        } catch (TimeoutException e) {
            kill();
            LOG.warn("Worker exceeded {} on {}; killed", settings.jobTimeout(), displayName);
            throw new SandboxException(SandboxException.Kind.TIMEOUT,
                    "the parser exceeded the time limit of " + settings.jobTimeout().toSeconds() + " s", e);
        }
        if (line == null) {
            int code = exitCodeOrMinusOne();
            kill();
            LOG.warn("Worker exited with code {} while processing {}", code, displayName);
            throw new SandboxException(SandboxException.Kind.CRASHED,
                    "the parser process ended unexpectedly (exit code " + code + ")", null);
        }

        WorkerProtocol.Response response;
        try {
            response = WorkerProtocol.read(line, WorkerProtocol.Response.class);
        } catch (IllegalArgumentException e) {
            kill();
            throw new SandboxException(SandboxException.Kind.PROTOCOL, e.getMessage(), e);
        }
        if (!response.ok()) {
            if ("unreadable".equals(response.kind())) {
                throw new ValidationException(response.message(), null);
            }
            throw new SandboxException(SandboxException.Kind.CRASHED, "the parser failed: " + response.message(), null);
        }
        return ReportJson.read(response.report());
    }

    @Override
    public synchronized void close() {
        closed = true;
        kill();
        reader.shutdownNow();
    }

    // ---- process management -------------------------------------------------------------------

    private void spawn() throws IOException {
        kill();
        tempDir = Files.createTempDirectory("eidas-worker-");
        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        cmd.add("-Xmx" + settings.maxHeapMb() + "m");
        cmd.add("-XX:+ExitOnOutOfMemoryError");
        cmd.add("-Dfile.encoding=UTF-8");
        cmd.add("-Djava.io.tmpdir=" + tempDir);
        cmd.addAll(settings.extraJvmArgs());
        cmd.add("-cp");
        cmd.add(absoluteClasspath());
        cmd.add(Worker.class.getName());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        pb.directory(tempDir.toFile());
        process = pb.start();
        toWorker = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        fromWorker = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));

        String line;
        try {
            toWorker.write(WorkerProtocol.write(WorkerProtocol.Hello.from(options)));
            toWorker.newLine();
            toWorker.flush();
            line = readLine(settings.startTimeout());
        } catch (IOException e) {
            // The worker died before or during the handshake; its stderr is passed through above.
            int code = exitCodeOrMinusOne();
            kill();
            throw new SandboxException(SandboxException.Kind.START_FAILED,
                    "worker exited during start (exit code " + code + "): " + e.getMessage(), e);
        } catch (TimeoutException e) {
            kill();
            throw new SandboxException(SandboxException.Kind.START_FAILED,
                    "worker did not become ready within " + settings.startTimeout().toSeconds() + " s", e);
        }
        if (line == null || !line.startsWith(WorkerProtocol.READY)) {
            String reason = line == null ? "worker exited with code " + exitCodeOrMinusOne()
                    : line.startsWith(WorkerProtocol.FAILED) ? line.substring(WorkerProtocol.FAILED.length()) : line;
            kill();
            throw new SandboxException(SandboxException.Kind.START_FAILED, reason, null);
        }
        trust = WorkerProtocol.readTrust(line.substring(WorkerProtocol.READY.length()));
        started = Instant.now();
        LOG.info("Sandbox worker pid {} ready ({} MB heap, {} per job)", process.pid(), settings.maxHeapMb(), settings.jobTimeout());
    }

    /**
     * The parent's classpath with every entry made absolute: with {@code java -jar target/x.jar}
     * the property holds a relative path, and the worker runs in its own directory. A jar's
     * manifest Class-Path entries resolve relative to the jar, so they need no treatment.
     */
    static String absoluteClasspath() {
        return java.util.Arrays.stream(System.getProperty("java.class.path", "").split(java.io.File.pathSeparator))
                .filter(p -> !p.isBlank())
                .map(p -> Path.of(p).toAbsolutePath().normalize().toString())
                .collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
    }

    private String readLine(Duration timeout) throws IOException, TimeoutException {
        Future<String> f = reader.submit(() -> fromWorker.readLine());
        try {
            return f.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            throw new IOException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        } catch (TimeoutException e) {
            f.cancel(true);
            throw e;
        }
    }

    private int exitCodeOrMinusOne() {
        try {
            return process != null && process.waitFor(2, TimeUnit.SECONDS) ? process.exitValue() : -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    private void kill() {
        if (process != null) {
            process.destroyForcibly();
            try {
                process.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            process = null;
        }
        if (tempDir != null) {
            try (var files = Files.walk(tempDir)) {
                files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            } catch (IOException ignored) {
                // best effort; the directory is under the system temp dir
            }
            tempDir = null;
        }
    }
}
