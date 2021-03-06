package net.squarelabs.sqorm.schema;

import net.squarelabs.sqorm.annotation.Column;
import net.squarelabs.sqorm.annotation.Table;
import net.squarelabs.sqorm.driver.DbDriver;
import net.squarelabs.sqorm.sql.MockStatement;
import org.apache.commons.lang.StringUtils;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.map.SerializationConfig;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.*;
import java.util.stream.Collectors;

public class TableSchema {

    private final String tableName;
    private final Class<?> clazz;
    private final DbDriver driver;

    // Reflection cache
    private final List<IndexSchema> indices = new ArrayList<>();
    private final SortedMap<String, ColumnSchema> columns;
    private final SortedMap<String, ColumnSchema> insertColumns;
    private final SortedMap<String, ColumnSchema> updateColumns;
    private final ColumnSchema versionCol;
    private final List<ColumnSchema> idColumns;
    private final List<ColumnSchema> autoIncrementColumns;
    private final IndexSchema primaryKey;

    // Cached query syntax
    private final String insertQuery;
    private final String updateQuery;

    private final List<RelationSchema> parentRelations = new ArrayList<>();
    private final List<RelationSchema> childRelations = new ArrayList<>();

    public TableSchema(Class<?> clazz, DbDriver driver) {
        this.clazz = clazz;
        this.driver = driver;

        Table tableAno = clazz.getAnnotation(Table.class);
        if (tableAno == null) {
            throw new IllegalArgumentException("Class is not marked with Table annotation!");
        }
        tableName = tableAno.name();

        // Collect accessors
        columns = parseAnnotations(clazz);
        versionCol = findVersionCol(columns);

        autoIncrementColumns = findAutoIncrementColumns(columns);
        insertColumns = findInsertCols(columns, autoIncrementColumns);

        idColumns = findIdCols(columns);
        updateColumns = findUpdateCols(columns, idColumns);

        primaryKey = ensureIndex(findPk(columns));

        // Write queries
        insertQuery = driver.writeInsertQuery(insertColumns, tableName);
        updateQuery = driver.writeUpdateQuery(updateColumns, tableName, idColumns);
    }

    public String getName() {
        return tableName;
    }

    public IndexSchema getPrimaryKey() {
        return primaryKey;
    }

    public Class<?> getType() {
        return clazz;
    }

    public ColumnSchema getColumn(String name) {
        return columns.get(name.toLowerCase());
    }

    public List<ColumnSchema> getColumns(String[] colNames) {
        List<ColumnSchema> cols = new ArrayList<>();
        for (String colName : colNames) {
            ColumnSchema col = getColumn(colName);
            if (col == null) {
                throw new RuntimeException("Column not found [" + colName + "] on table [" + getName() + "]");
            }
            cols.add(col);
        }
        return cols;
    }

    public List<IndexSchema> getIndices() {
        return Collections.unmodifiableList(indices);
    }

    public IndexSchema ensureIndex(List<ColumnSchema> cols) {
        IndexSchema idx = getIndex(cols);
        if (idx != null) {
            return idx;
        }
        idx = new IndexSchema(cols);
        indices.add(idx);
        return idx;
    }

    public IndexSchema getIndex(List<ColumnSchema> cols) {
        for (IndexSchema idx : indices) {
            if (idx.matches(cols)) {
                return idx;
            }
        }
        return null;
    }

    public int getVersion(Object record) {
        int version = (int) versionCol.get(record);
        return version;
    }

    public void setVersion(Object record, int val) {
        versionCol.set(record, val);
    }

    public void persist(Connection con, Object record) {
        int version = getVersion(record);
        if (version == 0) {
            setVersion(record, 1);
            insert(con, record);
        } else {
            setVersion(record, version + 1);
            update(con, record);
        }
    }

    public void update(Connection con, Object record) {
        try (PreparedStatement stmt = con.prepareStatement(updateQuery)) {
            prepareUpdateParms(stmt, record);
            int rowCount = stmt.executeUpdate();
            if (rowCount != 1) {
                throw new Exception("Update failed!");
            }
        } catch (Exception ex) {
            String msg = getFriendlyError(record, false);
            throw new RuntimeException(msg, ex);
        }
    }

    public void insert(Connection con, Object record) {
        try (PreparedStatement stmt = con.prepareStatement(insertQuery, Statement.RETURN_GENERATED_KEYS)) {
            prepareInsertParms(stmt, record);
            int rowCount = stmt.executeUpdate();
            if (rowCount != 1) {
                throw new Exception("Insert failed!");
            }

            // Grab auto incremented keys
            if (autoIncrementColumns.size() > 0) {
                int resIndex = 0;
                try (ResultSet rs = stmt.getGeneratedKeys()) {
                    while (rs.next()) {
                        if (resIndex >= autoIncrementColumns.size()) {
                            throw new Exception("More generated keys than columns!");
                        }
                        int val = rs.getInt(1);
                        autoIncrementColumns.get(resIndex++).set(record, val);
                    }
                }
            }
        } catch (Exception ex) {
            String msg = getFriendlyError(record, true);
            throw new RuntimeException(msg, ex);
        }
    }

    private static SortedMap<String, ColumnSchema> findUpdateCols(Map<String, ColumnSchema> columns,
                                                                  List<ColumnSchema> idColumns) {
        SortedMap<String, ColumnSchema> updateCols = new TreeMap<>(columns);
        idColumns.forEach(col -> updateCols.remove(col.getName()));
        return updateCols;
    }

    private static SortedMap<String, ColumnSchema> findInsertCols(Map<String, ColumnSchema> columns,
                                                                  List<ColumnSchema> autoIncrementColumns) {
        SortedMap<String, ColumnSchema> insertCols = new TreeMap<>(columns);
        autoIncrementColumns.forEach(col -> insertCols.remove(col.getName()));
        return insertCols;
    }

    private static List<ColumnSchema> findAutoIncrementColumns(Map<String, ColumnSchema> columns) {
        List<ColumnSchema> autoIncrementColumns = columns.values().stream()
                .filter(col -> col.isAutoIncrement()).collect(Collectors.toList());
        autoIncrementColumns.sort((ColumnSchema a, ColumnSchema b) -> a.getPkOrdinal() - b.getPkOrdinal());
        return autoIncrementColumns;
    }

    private static List<ColumnSchema> findIdCols(Map<String, ColumnSchema> columns) {
        List<ColumnSchema> idColumns = columns.values().stream()
                .filter(col -> col.getPkOrdinal() >= 0).collect(Collectors.toList());
        idColumns.sort((ColumnSchema a, ColumnSchema b) -> a.getPkOrdinal() - b.getPkOrdinal());
        return idColumns;
    }

    private static List<ColumnSchema> findPk(Map<String, ColumnSchema> columns) {
        List<ColumnSchema> pk = new ArrayList<>();
        for (ColumnSchema col : columns.values()) {
            if (col.getPkOrdinal() >= 0) {
                pk.add(col);
            }
        }
        pk.sort(new Comparator<ColumnSchema>() {
            @Override
            public int compare(ColumnSchema a, ColumnSchema b) {
                return a.getPkOrdinal() - b.getPkOrdinal();
            }
        });
        return pk;
    }

    private static ColumnSchema findVersionCol(Map<String, ColumnSchema> columns) {
        for (ColumnSchema col : columns.values()) {
            if (col.isVersion()) {
                return col;
            }
        }
        return null;
    }

    private static SortedMap<String, ColumnSchema> parseAnnotations(Class<?> clazz) {
        // Collect accessors
        Map<String, AnnotationCache> annos = new HashMap<>();
        String versionCol = null;
        for (Method method : clazz.getMethods()) {
            Column ano = method.getAnnotation(Column.class);
            if (ano == null) {
                continue;
            }
            AnnotationCache ac = getAc(annos, ano.name());
            if (method.getReturnType() == void.class) {
                ac.setter = method;
            } else {
                ac.getter = method;
            }
            ac.autoIncrement = ano.autoIncrement();
            ac.pkOrdinal = ano.pkOrdinal();
            if (ano.isVersion()) {
                versionCol = ac.name;
            }
        }

        // Translate to columns
        SortedMap<String, ColumnSchema> columns = new TreeMap<>();
        for (AnnotationCache ac : annos.values()) {
            boolean isVersion = StringUtils.equals(ac.name, versionCol);
            ColumnSchema col = new ColumnSchema(ac.name, ac.getter, ac.setter, ac.pkOrdinal, isVersion, ac.autoIncrement);
            columns.put(ac.name.toLowerCase(), col);
        }
        return columns;
    }

    public List<RelationSchema> getChildRelations() {
        return Collections.unmodifiableList(childRelations);
    }

    protected void addChildRelation(RelationSchema rel) {
        indices.add(rel.getPrimaryIndex());
        childRelations.add(rel);
    }

    public List<RelationSchema> getParentRelations() {
        return Collections.unmodifiableList(parentRelations);
    }

    protected void addParentRelation(RelationSchema rel) {
        parentRelations.add(rel);
    }

    private void prepareInsertParms(PreparedStatement stmt, Object record) {
        int idx = 1;
        for (ColumnSchema col : insertColumns.values()) {
            idx = setParm(stmt, record, col, idx);
        }
    }

    private void prepareUpdateParms(PreparedStatement stmt, Object record) {
        int idx = 1;
        for (ColumnSchema col : updateColumns.values()) {
            idx = setParm(stmt, record, col, idx);
        }
        for (ColumnSchema col : idColumns) {
            idx = setParm(stmt, record, col, idx);
        }
    }

    private int setParm(PreparedStatement stmt, Object record, ColumnSchema col, int idx) {
        Object javaVal = col.get(record);
        try {
            Object sqlVal = driver.javaToSql(javaVal);
            stmt.setObject(idx++, sqlVal);
            return idx;
        } catch (Exception ex) {
            String msg = String.format("Error setting column [%s] with value [%s]", col.getName(), javaVal);
            throw new RuntimeException(msg, ex);
        }
    }

    private String getFriendlyError(Object record, boolean insert) {
        try {
            MockStatement stmt = new MockStatement();
            if(insert) {
                prepareInsertParms(stmt, record);
            } else {
                prepareUpdateParms(stmt, record);
            }
            String msg = String.format("Error upserting record into [%s] using [%s]\n", tableName, driver.name());
            msg += (insert ? insertQuery : updateQuery) + "\n";
            msg += new ObjectMapper().enable(SerializationConfig.Feature.INDENT_OUTPUT).writeValueAsString(stmt.getParms());
            return msg;
        } catch (Exception ex) {
            throw new RuntimeException("Error getting error!", ex);
        }
    }

    private static AnnotationCache getAc(Map<String, AnnotationCache> annos, String name) {
        if (annos.containsKey(name)) {
            return annos.get(name);
        }
        AnnotationCache ac = new AnnotationCache(name);
        annos.put(name, ac);
        return ac;
    }

    private static class AnnotationCache {
        public String name;
        public Method getter;
        public Method setter;
        public int pkOrdinal;
        public boolean autoIncrement;

        public AnnotationCache(String name) {
            this.name = name;
        }
    }

}
