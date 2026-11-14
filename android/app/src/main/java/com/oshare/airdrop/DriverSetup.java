package com.oshare.airdrop;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Getting the kernel modules onto the phone and running the bring-up script.
 *
 * <p># What it does, in order
 *
 * <p>1. Unpacks {@code assets/kernel/} into the app's own {@code files/kernel/}, skipping any
 * file whose SHA-256 already matches the manifest. 2. Hands the directory to {@code su} with
 * one command that copies it to {@code /data/local/tmp/opairdrop} — only when the copy there
 * does not already match — and runs {@code awdl-stack.sh boot}. 3. Reads the receipt the script
 * publishes and returns it, already parsed.
 *
 * <p># Why two directories and not one
 *
 * <p>{@code insmod} from an app-private directory is denied by SELinux, so the modules have to
 * live somewhere the kernel will accept; {@code /data/local/tmp} is where the driver has been
 * loaded from all along. The app cannot write there (it is {@code shell}-owned), so the copy is
 * done by the same {@code su} that loads the modules. That is also why the two steps are not
 * separate calls: two {@code su} invocations would double the prompt and could leave the
 * payload half-copied with no receipt saying so.
 *
 * <p># Why the manifest is checked twice
 *
 * <p>Once here, against what the APK shipped, so a partially extracted file is re-extracted; and
 * once on the device by {@code sha256sum -c}, so a stale payload from an older build is replaced
 * rather than silently loaded. A kernel module that is one build behind does not fail — it
 * loads, and then behaves like the older code, which is the worst kind of bug to chase.
 *
 * <p># Why the receipt is the return value
 *
 * <p>Because it is the only thing that knows what happened. The exit status of {@code boot} is
 * the script's, and the script exits 0 in cases a person should be told about (a partial
 * bring-up: link up, daemon dead). {@link StackState} turns the line into a sentence; nothing
 * here invents one.
 */
public final class DriverSetup {
    /** Where the modules are unpacked for the app to see. */
    private static final String ASSETS = "kernel";
    /** The one place the kernel will let us load modules from. */
    private static final String PAYLOAD = "/data/local/tmp/opairdrop";
    /** The manifest staged next to the modules; see {@code stageKernelAssets} in build.gradle. */
    private static final String MANIFEST = "SHA256SUMS";
    /** Every `insmod` and the daemon start together take about 25 s on a cold phone. */
    private static final int TIMEOUT_SECONDS = 180;

    /**
     * Whether a bring-up is in flight, for two callers that would otherwise race.
     *
     * <p>The swap is 25 seconds long, and it is not idempotent while it runs: the second
     * {@code boot} would find no marker for this boot, would not see the modules loaded yet,
     * and would start its own {@code svc wifi disable} and {@code insmod} on top of the
     * first — which is exactly the interleaving that leaves a phone with no Wi-Fi driver.
     * A second press, or a page that was rebuilt while the first one was still running,
     * both land here rather than on the script.
     */
    private static final AtomicBoolean RUNNING = new AtomicBoolean();

    private DriverSetup() {}

    /** The app-private copy of the payload. */
    public static File dir(Context context) {
        return new File(context.getFilesDir(), ASSETS);
    }

    /** What a run produced: the script's own lines, its status, and the receipt as a sentence. */
    public static final class Result {
        private final int code;
        private final List<String> lines;
        private final StackState.State state;
        private final String problem;

        private Result(int code, List<String> lines, StackState.State state, String problem) {
            this.code = code;
            this.lines = Collections.unmodifiableList(lines);
            this.state = state;
            this.problem = problem;
        }

        /** The script's exit status, or -1 when it never ran (no root, no script). */
        public int code() {
            return code;
        }

        /** Everything the script said, in order, for the log. */
        public List<String> lines() {
            return lines;
        }

        /** The receipt, parsed. Never null; {@link StackState.State#present()} says if anything. */
        public StackState.State state() {
            return state;
        }

        /** Why the run could not happen at all, or empty. Not the same as a failed receipt. */
        public String problem() {
            return problem;
        }

        /** The one sentence the page shows. */
        public String subtitle() {
            return problem.isEmpty() ? state.subtitle() : problem;
        }
    }

    /**
     * Brings the stack up: unpack, copy, run {@code boot}, read the receipt.
     *
     * <p>Blocking, and it must not be called on the main thread — it waits up to three minutes
     * for the phone. Only one runs at a time; see {@link #RUNNING}.
     */
    public static Result boot(Context context, String name) {
        if (!RUNNING.compareAndSet(false, true)) {
            return new Result(-1, new ArrayList<>(), StackState.read(context.getFilesDir()),
                    "正在装驱动，这一次要先跑完");
        }
        try {
            File local = dir(context);
            try {
                unpack(context, local);
            } catch (IOException e) {
                return new Result(-1, new ArrayList<>(), StackState.read(context.getFilesDir()),
                        "解包失败：" + e.getMessage());
            }
            return run("boot " + quote(name), context);
        } finally {
            RUNNING.set(false);
        }
    }

    /** Whether a bring-up is in flight. The page uses it to draw the run it did not start. */
    public static boolean running() {
        return RUNNING.get();
    }

    /**
     * Runs {@code awdl-stack.sh <arguments>} through {@code su}.
     *
     * <p>Separate from {@link #boot} so the other subcommands ({@code stop}, {@code link},
     * {@code status}) can be driven by the page without a second copy of this plumbing.
     */
    public static Result run(String arguments, Context context) {
        String command = command(PAYLOAD, dir(context).getAbsolutePath(), arguments);
        List<String> lines = new ArrayList<>();
        int code;
        Process process;
        try {
            process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
        } catch (IOException noSu) {
            return new Result(-1, lines, StackState.read(context.getFilesDir()),
                    "没有 root：找不到 su（" + noSu.getMessage() + "）");
        }
        try {
            StringBuilder output = new StringBuilder();
            try (InputStream in = process.getInputStream()) {
                byte[] buffer = new byte[4096];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    output.append(new String(buffer, 0, n, StandardCharsets.UTF_8));
                }
            }
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroy();
                return new Result(-1, lines, StackState.read(context.getFilesDir()),
                        "脚本 " + TIMEOUT_SECONDS + " 秒没有结束");
            }
            code = process.exitValue();
            for (String line : output.toString().split("\n")) {
                if (!line.trim().isEmpty()) {
                    lines.add(line.trim());
                }
            }
        } catch (IOException | InterruptedException e) {
            process.destroy();
            return new Result(-1, lines, StackState.read(context.getFilesDir()),
                    "调用 su 失败：" + e.getMessage());
        }
        return new Result(code, lines, StackState.read(context.getFilesDir()), "");
    }

    /**
     * The command handed to {@code su -c}.
     *
     * <p>Three parts: make the payload directory, refresh it only if the hashes say it is stale,
     * run the script. The refresh is conditional because the payload is 38 MB and {@code start}
     * may be pressed several times a session; it is a {@code sha256sum -c} of the manifest that
     * was copied along with the modules, so the answer is about the bytes on the device and not
     * about what this build believes it shipped.
     *
     * <p>{@code chmod 755} is repeated after every copy rather than once: {@code cp} from an
     * app directory produces 0600 files (the app's umask follows it), and a helper without the
     * execute bit fails with EACCES deep inside the script, where the message is about the
     * tap rather than about the file.
     */
    static String command(String payload, String source, String arguments) {
        return "D=" + payload + "\n"
                + "S=" + source + "\n"
                + "mkdir -p \"$D\" || exit 90\n"
                + "if ! (cd \"$D\" && sha256sum -c \"$S/" + MANIFEST + "\" >/dev/null 2>&1); then\n"
                + "  cp -f \"$S\"/* \"$D\"/ || exit 91\n"
                + "  chmod 755 \"$D/awdl-stack.sh\" \"$D/wonder-ven\" \"$D/awdld\" || exit 92\n"
                + "fi\n"
                + "exec sh \"$D/awdl-stack.sh\" " + arguments + "\n";
    }

    /**
     * Writes every asset whose local copy does not already match the manifest.
     *
     * <p>Returns how many files were written, which is 0 on every launch after the first —
     * unpacking 38 MB on each start would be a visible delay for no benefit.
     */
    static int unpack(Context context, File local) throws IOException {
        if (!local.isDirectory() && !local.mkdirs()) {
            throw new IOException("建不了目录 " + local.getAbsolutePath());
        }
        String manifest = read(context.getAssets().open(ASSETS + "/" + MANIFEST));
        int written = 0;
        for (String line : manifest.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int space = trimmed.indexOf(' ');
            if (space < 0) {
                continue;
            }
            String hash = trimmed.substring(0, space);
            String name = trimmed.substring(space + 1).trim();
            if (name.isEmpty() || name.contains("/")) {
                // The manifest is ours, so a path here means it was tampered with or written
                // by a build that changed shape. Refusing is cheaper than explaining a traversal.
                continue;
            }
            File target = new File(local, name);
            if (target.isFile() && hash.equals(sha256(target))) {
                continue;
            }
            try (InputStream in = context.getAssets().open(ASSETS + "/" + name);
                 FileOutputStream out = new FileOutputStream(target)) {
                copy(in, out);
            }
            written++;
            if (name.equals("awdl-stack.sh") || name.equals("wonder-ven") || name.equals("awdld")) {
                target.setExecutable(true, false);
            }
        }
        return written;
    }

    /** SHA-256 of a file, lowercase hex, or empty when it cannot be read. */
    static String sha256(File file) {
        try (FileInputStream in = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1 << 16];
            int n;
            while ((n = in.read(buffer)) > 0) {
                digest.update(buffer, 0, n);
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) {
                hex.append(String.format("%02x", b & 0xff));
            }
            return hex.toString();
        } catch (IOException | NoSuchAlgorithmException unreadable) {
            return "";
        }
    }

    /**
     * A name as one shell word.
     *
     * <p>Everything outside {@code [A-Za-z0-9._-]} is dropped rather than escaped: the name
     * becomes the daemon's mDNS host, and an apostrophe in it would have to survive an
     * {@code su -c} string, a {@code sh -c} string and whatever `--name` does with it. Dropping
     * is the option with one failure mode.
     */
    static String quote(String name) {
        if (name == null) {
            return "''";
        }
        String safe = name.replaceAll("[^A-Za-z0-9._-]", "");
        return "'" + safe + "'";
    }

    private static String read(InputStream in) throws IOException {
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            copy(stream, out);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static void copy(InputStream in, java.io.OutputStream out) throws IOException {
        byte[] buffer = new byte[1 << 16];
        int n;
        while ((n = in.read(buffer)) > 0) {
            out.write(buffer, 0, n);
        }
    }
}
