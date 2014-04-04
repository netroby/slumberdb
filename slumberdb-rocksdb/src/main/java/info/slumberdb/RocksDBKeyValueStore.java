package info.slumberdb;

import org.boon.Exceptions;
import org.boon.Logger;
import org.iq80.leveldb.DB;
import org.iq80.leveldb.DBIterator;
import org.iq80.leveldb.Options;
import org.iq80.leveldb.WriteBatch;
import org.iq80.leveldb.impl.Iq80DBFactory;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;

import static org.boon.Boon.configurableLogger;
import static org.boon.Exceptions.die;

import org.fusesource.rocksdbjni.JniDBFactory;

/**
 * Created by Richard on 4/4/14.
 */
public class RocksDBKeyValueStore implements KeyValueStore<byte[], byte[]>{


    /**
     * The "filename" of the level db file.
     * This is really a directory.
     */
    private final String fileName;

    /**
     * Flag to denote if we are using JNI or not.
     */
    private final boolean usingJNI;

    /**
     * Setup the options for the database.
     * Also used for flush operation which closes the DB
     * and then reopens it.
     */
    private final Options options;

    /**
     * Logger.
     */
    private Logger logger = configurableLogger(RocksDBKeyValueStore.class);

    /** Actual database implementation. */
    DB database;


    /** Creates a level db database with the default options. */
    public RocksDBKeyValueStore( String fileName ) {
        this (fileName, null, false);
    }

    /** Creates a level db database with the options passed
     * Also allows setting up logging or not.
     *
     * @param fileName fileName
     * @param options options
     * @param log turn on logging
     */
    public RocksDBKeyValueStore(String fileName, Options options, boolean log) {
        this.fileName = fileName;
        File file = new File(fileName);



        if (options==null) {
            logger.info("Using default options");
            options =defaultOptions();
        }

        this.options = options;

        if (log) {
            options.logger(new org.iq80.leveldb.Logger() {
                @Override
                public void log(String message) {
                    logger.info("FROM DATABASE LOG", message);
                }
            });
        }


        usingJNI = openDB(file,  options);
    }

    /**
     * Configures default options.
     * @return
     */
    private Options defaultOptions() {

        Options options = new Options();
        options.createIfMissing(true);
        options.blockSize(32_768); //32K
        options.cacheSize(67_108_864);//64MB
        return options;
    }

    /**
     * Opens the database
     * @param file filename to open
     * @param options options
     * @return
     */
    private boolean openDB(File file, Options options) {

        try {
            database = JniDBFactory.factory.open(file, options);
            logger.info("Using JNI Level DB");
            return  true;
        } catch (IOException ex1) {
            try {
                database = Iq80DBFactory.factory.open(file, options);
                logger.info("Using Java Level DB");
                return false;
            } catch (IOException ex2) {
                return Exceptions.handle(Boolean.class, ex2);
            }
        }

    }

    /**
     * Puts an item in the key value store.
     * @param key  key
     * @param value value
     */
    @Override
    public void put(byte[] key, byte[] value) {
        database.put(key, value);
    }

    /**
     * Puts values into the key value store in batch mode
     * @param values values
     */
    @Override
    public void putAll(Map<byte[], byte[]> values) {

        WriteBatch batch = database.createWriteBatch();

        try {

            for (Map.Entry<byte[], byte[]> entry : values.entrySet()) {
                batch.put(entry.getKey(), entry.getValue());
            }

            database.write(batch);

        } finally {
            closeBatch(batch);
        }
    }

    private void closeBatch(WriteBatch batch) {
        try {
            batch.close();
        } catch (IOException e) {
            Exceptions.handle(e);
        }
    }

    /** Remove all of the keys passed.
     *
     * @param keys keys
     */
    @Override
    public void removeAll(Iterable<byte[]> keys) {

        WriteBatch batch = database.createWriteBatch();

        try {

            for (byte[] key : keys) {
                batch.delete(key);
            }

            database.write(batch);

        } finally {
            closeBatch(batch);
        }

    }

    /**
     * Performs batch updates
     * @param updates
     */
    @Override
    public void updateAll(Iterable<CrudOperation> updates) {
        die("Not implemented for binary array");

    }

    /**
     * Remove items from list
     * @param key
     */
    @Override
    public void remove(byte[] key) {
        database.delete(key);
    }


    /**
     * Search to a certain location.
     * @param startKey startKey
     * @return
     */
    @Override
    public KeyValueIterable<byte[], byte[]> search(byte[] startKey) {

        final DBIterator iterator = database.iterator();
        iterator.seek( startKey );


        return new KeyValueIterable<byte[], byte[]>() {
            @Override
            public void close() {
                closeIterator(iterator);
            }

            @Override
            public Iterator<Entry<byte[], byte[]>> iterator() {
                return new Iterator<Entry<byte[], byte[]>>() {
                    @Override
                    public boolean hasNext() {
                        return iterator.hasNext();
                    }

                    @Override
                    public Entry<byte[], byte[]> next() {

                        Map.Entry<byte[], byte[]> next = iterator.next();
                        return new Entry<>(next.getKey(), next.getValue());
                    }

                    @Override
                    public void remove() {
                        iterator.remove();
                    }
                };
            }
        };

    }

    private void closeIterator(DBIterator iterator) {
        try {
            iterator.close();
        } catch (IOException e) {
            Exceptions.handle(e);
        }
    }

    /**
     * Load all of the key/values from the store.
     * @return
     */
    @Override
    public KeyValueIterable<byte[], byte[]> loadAll() {

        final DBIterator iterator = database.iterator();
        iterator.seekToFirst();


        return new KeyValueIterable<byte[], byte[]>() {
            @Override
            public void close() {
                closeIterator(iterator);
            }

            @Override
            public Iterator<Entry<byte[], byte[]>> iterator() {
                return new Iterator<Entry<byte[], byte[]>>() {
                    @Override
                    public boolean hasNext() {
                        return iterator.hasNext();
                    }

                    @Override
                    public Entry<byte[], byte[]> next() {

                        Map.Entry<byte[], byte[]> next = iterator.next();
                        return new Entry<>(next.getKey(), next.getValue());
                    }

                    @Override
                    public void remove() {
                        iterator.remove();
                    }
                };
            }
        };
    }

    /**
     * Get the key from the store
     * @param key key
     * @return value from store at location key
     */
    @Override
    public byte[] get(byte[] key) {
        return database.get(key);
    }


    /**
     * Close the database connection.
     */
    @Override
    public void close()  {
        try {
            database.close();
        } catch (IOException e) {
            Exceptions.handle(e);
        }
    }

    /**
     * Close the database and reopen it.
     * We should add a lock here or just drop this feature.
     *
     */
    @Override
    public void flush()  {
        this.close();
        openDB(new File(fileName), this.options);
    }
}

