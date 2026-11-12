package minidb;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Журнал предзаписи (write-ahead log). Каждый успешно выполненный изменяющий запрос дописывается
 * в конец файла и сбрасывается на диск ({@code fsync}) до того, как пользователю вернётся результат.
 * Формат записи: {@code [int длина][байты UTF-8][long CRC32]}. При чтении запись с неверной
 * контрольной суммой или оборванная (авария во время записи) считается концом журнала.
 */
public class WriteAheadLog implements Closeable {
    private static final int MAX_RECORD = 64 * 1024 * 1024;

    private final Path file;
    private final boolean sync;
    private FileOutputStream fos;
    private DataOutputStream out;

    public WriteAheadLog(Path file, boolean sync) {
        this.file = file;
        this.sync = sync;
    }

    /** Открывает журнал для дозаписи. */
    public void open() {
        try {
            fos = new FileOutputStream(file.toFile(), true);
            out = new DataOutputStream(new BufferedOutputStream(fos));
        } catch (IOException e) {
            throw new DbException("Cannot open WAL: " + e.getMessage(), e);
        }
    }

    /** Размер файла журнала в байтах. */
    public long size() {
        try {
            return Files.exists(file) ? Files.size(file) : 0;
        } catch (IOException e) {
            return 0;
        }
    }

    /** Дописывает запрос в журнал и сбрасывает его на диск. */
    public void append(String sql) {
        try {
            byte[] b = sql.getBytes(StandardCharsets.UTF_8);
            CRC32 crc = new CRC32();
            crc.update(b, 0, b.length);
            out.writeInt(b.length);
            out.write(b);
            out.writeLong(crc.getValue());
            out.flush();
            if (sync) fos.getFD().sync();
        } catch (IOException e) {
            throw new DbException("WAL write failed: " + e.getMessage(), e);
        }
    }

    /** Очищает журнал (вызывается после успешной контрольной точки). */
    public void truncate() {
        try {
            close();
            new FileOutputStream(file.toFile(), false).close();
            open();
        } catch (IOException e) {
            throw new DbException("WAL truncate failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() throws IOException {
        if (out != null) {
            out.close();
            out = null;
            fos = null;
        }
    }

    /** Читает все корректные записи журнала (до первой повреждённой). */
    public static List<String> readAll(Path file) {
        List<String> result = new ArrayList<>();
        if (!Files.exists(file)) return result;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            while (true) {
                int len = in.readInt();
                if (len < 0 || len > MAX_RECORD) break;
                byte[] b = new byte[len];
                in.readFully(b);
                long stored = in.readLong();
                CRC32 crc = new CRC32();
                crc.update(b, 0, b.length);
                if (crc.getValue() != stored) break;
                result.add(new String(b, StandardCharsets.UTF_8));
            }
        } catch (EOFException e) {
            // хвост журнала оборван - нормальная ситуация после аварии
        } catch (IOException e) {
            throw new DbException("Cannot read WAL: " + e.getMessage(), e);
        }
        return result;
    }
}
