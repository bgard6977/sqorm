package net.squarelabs.sqorm.schema;

import java.lang.reflect.Method;

public class ColumnSchema {

    private final String name;
    private final Method getter;
    private final Method setter;
    private final Class<?> type;

    private final int pkOrdinal;
    private final boolean isVersion;

    private final boolean autoIncrement;

    public ColumnSchema(String name, Method getter, Method setter, int pkOrdinal, boolean isVersion, boolean autoIncrement) {
        if(getter == null) {
            throw new RuntimeException("No getter for column: " + name);
        }

        this.name = name;
        this.getter = getter;
        this.setter = setter;
        this.pkOrdinal = pkOrdinal;
        this.isVersion = isVersion;
        this.autoIncrement = autoIncrement;
        this.type = getter.getReturnType();
    }

    public boolean isAutoIncrement() {
        return autoIncrement;
    }

    public Class<?> getType() {
        return this.type;
    }

    public int getPkOrdinal() {
        return pkOrdinal;
    }

    public boolean isVersion() {
        return isVersion;
    }

    public String getName() {
        return name;
    }

    public Object get(Object record) {
        try {
            return getter.invoke(record);
        } catch (Exception ex) {
            throw new RuntimeException("Error getting record value!", ex);
        }
    }
    public void set(Object record, Object val) {
        try {
            setter.invoke(record, new Object[] {val});
        } catch (Exception ex) {
            throw new RuntimeException("Error setting value [" + val + "] on column [" + name + "]", ex);
        }
    }
}
