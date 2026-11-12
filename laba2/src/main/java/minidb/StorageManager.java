package minidb;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Слой доступа к данным на диске (паттерн DAO). Структура каталога данных:
 * <ul>
 * <li>{@code <table>.tbl} - сериализованная таблица (схема, записи, определения индексов);</li>
 * <li>{@code <table>.<index>.idx} - сериализованный индекс;</li>
 * <li>{@code wal.log} - журнал изменений после последней контрольной точки.</li>
 * </ul>
 * Файлы записываются во временный файл, сбрасываются на диск и атомарно переименовываются,
 * поэтому после сбоя на диске всегда лежит целая (старая или новая) версия файла.
 */
public class StorageManager implements Closeable {
    private final Path dir;
    private final WriteAheadLog wal;

    public StorageManager(Path dir, boolean syncWal) {
        this.dir = dir;
        try {
            Files.createDirectories(dir);
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.tmp")) {
                for (Path p : ds) Files.deleteIfExists(p); // остатки прерванной записи
            }
        } catch (IOException e) {
            throw new DbException("Cannot prepare data directory: " + e.getMessage(), e);
        }
        this.wal = new WriteAheadLog(dir.resolve("wal.log"), syncWal);
    }

    /** Загружает все таблицы и их индексы. Несогласованные индексы перестраиваются. */
    public Map<String, Table> loadTables() {
        Map<String, Table> result = new LinkedHashMap<>();
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.tbl")) {
            for (Path p : ds) files.add(p);
        } catch (IOException e) {
            throw new DbException("Cannot list data directory: " + e.getMessage(), e);
        }
        Collections.sort(files);
        for (Path p : files) {
            Table t = (Table) readObject(p);
            for (String idxName : t.getIndexDefs().keySet()) {
                Path ip = dir.resolve(t.getName() + "." + idxName + ".idx");
                Index loaded = null;
                if (Files.exists(ip)) {
                    try {
                        loaded = (Index) readObject(ip);
                    } catch (DbException e) {
                        loaded = null; // повреждён - перестроим
                    }
                }
                t.attachIndex(idxName, loaded);
            }
            result.put(t.getName(), t);
        }
        return result;
    }

    /** Корректные записи журнала, не попавшие в последнюю контрольную точку. */
    public List<String> readWal() { return WriteAheadLog.readAll(dir.resolve("wal.log")); }

    public long walSize() { return wal.size(); }
    public void openWal() { wal.open(); }
    public void logStatement(String sql) { wal.append(sql); }

    /**
     * Контрольная точка: записывает все таблицы и индексы, удаляет файлы несуществующих
     * таблиц и индексов, очищает журнал.
     */
    public void saveAll(Map<String, Table> tables) {
        Set<String> keep = new HashSet<>();
        for (Table t : tables.values()) {
            String tf = t.getName() + ".tbl";
            writeObject(dir.resolve(tf), t);
            keep.add(tf);
            for (Index idx : t.indexes()) {
                String f = t.getName() + "." + idx.getName() + ".idx";
                writeObject(dir.resolve(f), idx);
                keep.add(f);
            }
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.{tbl,idx}")) {
            for (Path p : ds) {
                if (!keep.contains(p.getFileName().toString())) Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            throw new DbException("Cannot clean data directory: " + e.getMessage(), e);
        }
        wal.truncate();
    }

    private void writeObject(Path target, Object obj) {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp.toFile());
             ObjectOutputStream oos = new ObjectOutputStream(new BufferedOutputStream(fos))) {
            oos.writeObject(obj);
            oos.flush();
            fos.getFD().sync();
        } catch (IOException e) {
            throw new DbException("Cannot write " + target.getFileName() + ": " + e.getMessage(), e);
        }
        try {
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new DbException("Cannot replace " + target.getFileName() + ": " + e.getMessage(), e);
        }
    }

    private Object readObject(Path p) {
        try (ObjectInputStream in = new ObjectInputStream(new BufferedInputStream(Files.newInputStream(p)))) {
            return in.readObject();
        } catch (IOException | ClassNotFoundException e) {
            throw new DbException("Cannot read " + p.getFileName() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void close() throws IOException { wal.close(); }
}
