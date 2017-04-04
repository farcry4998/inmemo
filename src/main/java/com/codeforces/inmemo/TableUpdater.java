package com.codeforces.inmemo;

import org.apache.log4j.Logger;
import org.jacuzzi.core.Jacuzzi;
import org.jacuzzi.core.Row;
import org.jacuzzi.core.TypeOracle;

import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * @author MikeMirzayanov (mirzayanovmr@gmail.com)
 */
class TableUpdater<T extends HasId> {
    private static final Logger logger = Logger.getLogger(TableUpdater.class);
    private static final int MAX_ROWS_IN_SINGLE_SQL_STATEMENT = 200_000;
    private static final int MAX_UPDATE_SAME_INDICATOR_TIMES = 5;

    private final Lock updateLock = new ReentrantLock();

    private static DataSource dataSource;
    private static final Collection<TableUpdater<? extends HasId>> instances = new ArrayList<>();

    private final Table table;
    private final boolean advancedLogging; // TODO remove
    private final Thread thread;
    private final String threadName;
    private volatile boolean running;

    private final Jacuzzi jacuzzi;
    private final TypeOracle<T> typeOracle;

    private Object lastIndicatorValue;
    private final long startTimeMillis;

    /**
     * Delay after meaningless update try.
     */
    private static final long rescanTimeMillis = 500;

    private final Map<Long, Integer> lastEntityIdsUpdateCount = new HashMap<>();

    TableUpdater(Table<T> table, Object initialIndicatorValue) {
        if (dataSource == null) {
            logger.error("It should be called static Inmemo#setDataSource() before any instance of TableUpdater.");
            throw new InmemoException("It should be called static Inmemo#setDataSource() before any instance of TableUpdater.");
        }

        this.table = table;
        this.advancedLogging = false; // "ContestParticipant".equals(table.getClazz().getSimpleName());
        this.lastIndicatorValue = initialIndicatorValue;

        jacuzzi = Jacuzzi.getJacuzzi(dataSource);
        typeOracle = TypeOracle.getTypeOracle(table.getClazz());

        threadName = "InmemoUpdater#" + table.getClazz();
        thread = new Thread(new TableUpdaterRunnable(), threadName);
        thread.setDaemon(true);

        logger.info("Started Inmemo table updater thread '" + threadName + "'.");
        startTimeMillis = System.currentTimeMillis();

        //noinspection ThisEscapedInObjectConstruction
        instances.add(this);
    }

    @SuppressWarnings("UnusedDeclaration")
    static void setDataSource(DataSource dataSource) {
        TableUpdater.dataSource = dataSource;
    }

    @SuppressWarnings("UnusedDeclaration")
    static void stop() {
        for (TableUpdater<? extends HasId> instance : instances) {
            instance.running = false;
        }
    }

    void start() {
        running = true;
        thread.start();
    }

    void insertOrUpdateById(Long id) {
        List<Row> rows = jacuzzi.findRows(String.format("SELECT * FROM %s WHERE %s = %s",
                typeOracle.getTableName(), typeOracle.getIdColumn(), id.toString()
        ));

        if (rows == null || rows.isEmpty()) {
            return;
        }

        if (rows.size() == 1) {
            Row row = rows.get(0);
            T entity = typeOracle.convertFromRow(row);

            table.insertOrUpdate(entity);
            table.insertOrUpdate(row);
        } else {
            throw new InmemoException("Expected at most one item of " + table.getClazz() + " with id = " + id + '.');
        }
    }

    List<T> findAndUpdateByEmergencyQueryFields(Object[] fields) {
        validateFieldsArray(fields);

        String[] fieldNames = new String[fields.length / 2];
        Object[] fieldValues = new Object[fields.length / 2];

        for (int index = 0; index < fields.length; index += 2) {
            fieldNames[index / 2] = (String) fields[index];
            fieldValues[index / 2] = fields[index + 1];
        }

        String formattedFields = typeOracle.getQueryFindSql(fieldNames);

        List<Row> rows = jacuzzi.findRows(String.format("SELECT * FROM %s WHERE %s ORDER BY %s",
                typeOracle.getTableName(), formattedFields, typeOracle.getIdColumn()), fieldValues);

        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }

        logger.warn("Emergency case: found " + rows.size() + " items of class " + table.getClazz().getName() + " [fields=" + formattedFields + "].");

        List<T> result = new ArrayList<>(rows.size());
        for (Row row : rows) {
            T entity = typeOracle.convertFromRow(row);
            logger.warn("Emergency found: " + table.getClazz().getName() + " id=" + entity.getId() + " [fields=" + formattedFields + "].");

            result.add(entity);

            table.insertOrUpdate(entity);
            table.insertOrUpdate(row);
        }

        return result;
    }

    private static void validateFieldsArray(Object[] fields) {
        if (fields.length % 2 != 0) {
            throw new IllegalArgumentException("EmergencyQueryFields array should have even length. Found: "
                    + Arrays.toString(fields) + '.');
        }

        for (int index = 0; index < fields.length; index += 2) {
            Object field = fields[index];
            if (field == null || !(field instanceof String) || ((String) field).isEmpty()) {
                throw new IllegalArgumentException("EmergencyQueryFields array must contain non-empty strings on even positions. Found: "
                        + Arrays.toString(fields) + '.');
            }
        }
    }

    void update() {
        if (running) {
            internalUpdate();
        }
    }

    private void update(Random timeSleepRandom) {
        List<Long> updatedIds = internalUpdate();
        sleepBetweenRescans(timeSleepRandom, updatedIds.size());
    }

    private List<Long> internalUpdate() {
        updateLock.lock();

        try {
            long startTimeMillis = System.currentTimeMillis();

            List<Row> rows = getRecentlyChangedRows(lastIndicatorValue);
            long afterGetRecentlyChangedRowsMillis = System.currentTimeMillis();

            Object previousIndicatorLastValue = lastIndicatorValue;
            boolean hasInsertOrUpdateByRow = table.hasInsertOrUpdateByRow();
            List<Long> updatedIds = new ArrayList<>();

            if (advancedLogging) {
                logger.warn(String.format(
                        "UPDATE ContestParticipant (1): lastIndicatorValue=%s, rows.size=%d, hasInsertOrUpdateByRow=%b.",
                        lastIndicatorValue, rows.size(), hasInsertOrUpdateByRow
                ));
            }

            for (Row row : rows) {
                long id = getRowId(row);
                if (Objects.equals(row.get(table.getIndicatorField()), previousIndicatorLastValue)
                        && lastEntityIdsUpdateCount.containsKey(id) && lastEntityIdsUpdateCount.get(id) >= MAX_UPDATE_SAME_INDICATOR_TIMES) {
                    if (advancedLogging) {
                        logger.warn(String.format("UPDATE ContestParticipant (2): row=%s.", row.entrySet()));
                    }

                    continue;
                }

                // Insert or update entity.
                T entity = typeOracle.convertFromRow(row);
                table.insertOrUpdate(entity);

                updatedIds.add(id);

                // Insert or update row.
                if (hasInsertOrUpdateByRow) {
                    table.insertOrUpdate(row);
                }

                lastIndicatorValue = row.get(table.getIndicatorField());
            }

            if (advancedLogging) {
                logger.warn(String.format(
                        "UPDATE ContestParticipant (3): lastIndicatorValue=%s, updatedIds.size=%d.",
                        lastIndicatorValue, updatedIds.size()
                ));
            }

            if (updatedIds.size() >= 10) {
                logger.info(String.format("Thread '%s' has found %s rows to update in %d ms [lastIndicatorValue=" + lastIndicatorValue + "].", threadName,
                        rows.size(), afterGetRecentlyChangedRowsMillis - startTimeMillis));

                if (updatedIds.size() <= 100) {
                    StringBuilder ids = new StringBuilder();
                    for (long id : updatedIds) {
                        if (ids.length() > 0) {
                            ids.append(',');
                        }
                        ids.append(id);
                    }
                    logger.info("Updated entries have id=" + ids + '.');
                }

                logger.info(String.format("Thread '%s' has updated %d items in %d ms [lastIndicatorValue=" + lastIndicatorValue + "].",
                        threadName, updatedIds.size(), System.currentTimeMillis() - startTimeMillis));
            }

            if (updatedIds.isEmpty() && !table.isPreloaded()) {
                logger.info("Inmemo preloaded " + ReflectionUtil.getTableClassName(table.getClazz())
                        + " [items=" + table.size() + "] in " + (System.currentTimeMillis() - this.startTimeMillis) + " ms.");
                table.setPreloaded(true);
            }

            if (!Objects.equals(previousIndicatorLastValue, lastIndicatorValue)) {
                lastEntityIdsUpdateCount.clear();
            }
            List<Long> trulyUpdatedIds = new ArrayList<>(updatedIds.size());
            for (Row row : rows) {
                if (Objects.equals(row.get(table.getIndicatorField()), lastIndicatorValue)) {
                    Integer updateCount = lastEntityIdsUpdateCount.get(getRowId(row));
                    if (updateCount == null) {
                        trulyUpdatedIds.add(getRowId(row));
                        updateCount = 1;
                    } else {
                        updateCount += 1;
                    }
                    lastEntityIdsUpdateCount.put(getRowId(row), updateCount);
                }
            }
            return trulyUpdatedIds;
        } finally {
            updateLock.unlock();
        }
    }

    private static long getRowId(Row row) {
        Long id = (Long) row.get("id");
        if (id == null) {
            id = (Long) row.get("ID");
        }
        return id;
    }

    private void sleepBetweenRescans(Random timeSleepRandom, int updatedCount) {
        if (updatedCount == 0) {
            sleep((4 * rescanTimeMillis / 5) + timeSleepRandom.nextInt((int) (rescanTimeMillis / 5)));
        } else if ((updatedCount << 1) > MAX_ROWS_IN_SINGLE_SQL_STATEMENT) {
            logger.info(String.format(
                    "Thread '%s' will not sleep because it updated near maximum row count.", threadName
            ));
        } else {
            sleep(timeSleepRandom.nextInt((int) (rescanTimeMillis / 5)));
        }
    }

    private void sleep(long timeMillis) {
        try {
            Thread.sleep(timeMillis);
        } catch (InterruptedException e) {
            logger.error("Thread '" + threadName + "' has been stopped because of InterruptedException.", e);
            running = false;
        }
    }

    private List<Row> getRecentlyChangedRows(Object indicatorLastValue) {
        List<Row> rows;
        long startTimeMillis = System.currentTimeMillis();
        String forceIndexClause = table.getDatabaseIndex() == null ? "" : ("FORCE INDEX (" + table.getDatabaseIndex() + ')');

        if (indicatorLastValue == null) {
            rows = jacuzzi.findRows(
                    String.format(
                            "SELECT * FROM %s %s ORDER BY %s, %s LIMIT %d",
                            typeOracle.getTableName(),
                            forceIndexClause,
                            table.getIndicatorField(),
                            typeOracle.getIdColumn(),
                            MAX_ROWS_IN_SINGLE_SQL_STATEMENT
                    )
            );
        } else {
            rows = jacuzzi.findRows(
                    String.format(
                            "SELECT * FROM %s %s WHERE %s >= ? ORDER BY %s, %s LIMIT %d",
                            typeOracle.getTableName(),
                            forceIndexClause,
                            table.getIndicatorField(),
                            table.getIndicatorField(),
                            typeOracle.getIdColumn(),
                            MAX_ROWS_IN_SINGLE_SQL_STATEMENT
                    ), indicatorLastValue
            );
        }

        long queryTimeMillis = System.currentTimeMillis() - startTimeMillis;
        if (queryTimeMillis * 10 > rescanTimeMillis) {
            logger.warn(String.format(
                    "Rescanning query for entity `%s` took too long time %d ms.",
                    table.getClazz().getName(), queryTimeMillis
            ));
        }

//        int rowCount = rows.size();
//        if (rowCount == MAX_ROWS_IN_SINGLE_SQL_STATEMENT) {
//            logger.warn(String.format(
//                    "Suspicious row count while rescanning `%s` [rowCount=%d, queryTime=%d ms].",
//                    table.getClazz().getName(),
//                    MAX_ROWS_IN_SINGLE_SQL_STATEMENT,
//                    queryTimeMillis
//            ));
//        }

        return rows;
    }

    private class TableUpdaterRunnable implements Runnable {
        @Override
        public void run() {
            AttachConnectionHelper attachConnectionHelper = new AttachConnectionHelper();
            @SuppressWarnings("UnsecureRandomNumberGeneration") Random sleepRandom = new Random();

            while (running) {
                attachConnectionHelper.reattach();
                try {
                    update(sleepRandom);
                } catch (Exception e) {
                    logger.error("Unexpected " + e.getClass().getName() + " exception in TableUpdaterRunnable of "
                            + threadName
                            + ": " + e, e);
                }
            }

            attachConnectionHelper.stop();
            logger.warn("Inmemo update thread for " + table.getClazz().getName() + " finished");
        }
    }

    private final class AttachConnectionHelper {
        private static final long ATTACH_TIME_MILLIS = 90_000; /* 1.5 minutes. */
        private long attachTimeMillis;

        private AttachConnectionHelper() {
            this.attachTimeMillis = System.currentTimeMillis();
            logger.info("Initially attached connection for " + thread.getName() + '.');
            jacuzzi.attachConnection();
        }

        void reattach() {
            if (attachTimeMillis + ATTACH_TIME_MILLIS < System.currentTimeMillis()) {
                attachTimeMillis = System.currentTimeMillis();
                logger.info("Reattaching connection for " + thread.getName() + '.');
                jacuzzi.detachConnection();
                jacuzzi.attachConnection();
            }
        }

        public void stop() {
            jacuzzi.detachConnection();
        }
    }
}
