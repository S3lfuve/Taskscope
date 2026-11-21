package local.taskscope.manager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

final class Root {
    private static final ScheduledExecutorService TIMEOUTS =
        Executors.newSingleThreadScheduledExecutor();

    static final class Result {
        final int code;
        final String output;

        Result(int code, String output) {
            this.code = code;
            this.output = output;
        }
    }

    static Result run(String command, int seconds) throws IOException, InterruptedException {
        Process process = start(command);
        ScheduledFuture<?> watchdog = timeout(process, seconds);
        try {
            process.getOutputStream().close();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = process.getInputStream()) {
                byte[] buffer = new byte[8192];
                int amount;
                while ((amount = in.read(buffer)) != -1) {
                    if (out.size() + amount > 4 * 1024 * 1024) throw new IOException("Слишком большой ответ root.");
                    out.write(buffer, 0, amount);
                }
            }
            return new Result(process.waitFor(), out.toString(StandardCharsets.UTF_8.name()));
        } finally {
            watchdog.cancel(false);
            process.destroyForcibly();
        }
    }

    static Process start(String command) throws IOException {
        String su = new java.io.File("/system/bin/su").canExecute() ? "/system/bin/su" : "su";
        return new ProcessBuilder(su, "-c", command).redirectErrorStream(true).start();
    }

    static ScheduledFuture<?> timeout(Process process, int seconds) {
        return TIMEOUTS.schedule(process::destroyForcibly, seconds, TimeUnit.SECONDS);
    }

    static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
