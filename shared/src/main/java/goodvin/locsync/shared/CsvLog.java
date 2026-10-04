/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package goodvin.locsync.shared;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

/**
 * Append-only CSV file for high-rate recordings (the drive tracks): buffered, header written once,
 * rotated to {@code <name>.old} above a size cap, and re-created if something (e.g. "Clear logs")
 * deleted it while open. Plus allocation-free number formatting — {@code String.format} costs
 * microseconds and garbage per call, and the client track writes ~11 rows of ~20 numbers a second.
 *
 * <p>Not thread-safe: use from one thread. Android-free (failures are swallowed so recording never
 * disturbs the caller), so it is JVM unit-testable.
 */
public final class CsvLog {
    private final File file;
    private final String header;
    private final long maxBytes;
    private BufferedWriter writer;
    private long bytes;

    public CsvLog(File file, String header, long maxBytes) {
        this.file = file;
        this.header = header;
        this.maxBytes = maxBytes;
    }

    public File getFile() {
        return file;
    }

    /** Appends one row (without the trailing newline). */
    public void writeLine(StringBuilder line) {
        try {
            if (writer == null) open();
            if (bytes > maxBytes) {
                closeQuietly();
                File old = new File(file.getParentFile(), file.getName() + ".old");
                //noinspection ResultOfMethodCallIgnored
                old.delete();
                //noinspection ResultOfMethodCallIgnored
                file.renameTo(old);
                open();
            }
            line.append('\n');
            writer.append(line);
            bytes += line.length();
        } catch (IOException e) {
            closeQuietly();
        }
    }

    /** Writes buffered rows out; if the file was deleted meanwhile, the next row starts a new one. */
    public void flush() {
        if (writer == null) return;
        if (!file.exists()) {
            closeQuietly();
            return;
        }
        try {
            writer.flush();
        } catch (IOException e) {
            closeQuietly();
        }
    }

    public void close() {
        flush();
        closeQuietly();
    }

    private void open() throws IOException {
        File parent = file.getParentFile();
        if (parent != null) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        boolean needHeader = !file.exists() || file.length() == 0;
        writer = new BufferedWriter(new FileWriter(file, true), 16 * 1024);
        bytes = file.length();
        if (needHeader) {
            writer.append(header).append('\n');
            bytes += header.length() + 1;
        }
    }

    private void closeQuietly() {
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException ignored) {
                // nothing to do
            }
            writer = null;
        }
    }

    private static final long[] POW10 = {1, 10, 100, 1_000, 10_000, 100_000, 1_000_000, 10_000_000, 100_000_000};

    /**
     * Appends {@code v} with exactly {@code decimals} (0–8) digits after the point, rounded half up
     * like {@code %.Nf}; appends nothing for NaN/infinite. Values must fit in a long once scaled.
     */
    public static StringBuilder appendFixed(StringBuilder sb, double v, int decimals) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return sb;
        }
        long scale = POW10[decimals];
        long m = Math.round(Math.abs(v) * scale);
        if (v < 0 && m != 0) {
            sb.append('-');
        }
        sb.append(m / scale);
        if (decimals > 0) {
            sb.append('.');
            long frac = m % scale;
            for (long p = scale / 10; p > 1 && frac < p; p /= 10) {
                sb.append('0');
            }
            sb.append(frac);
        }
        return sb;
    }
}
