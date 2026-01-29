package com.machina.mdatabase.providers.database;

public class DialectInfo {
    /**
     * The dialect
     */
    public final DatabaseDialect dialect;

    /**
     * The class name of the dialect driver
     */
    public final String driverClassName;

    /**
     * Constructor
     * @param dialect The dialect
     * @param driverClassName The class name of the dialect driver
     */
    public DialectInfo(DatabaseDialect dialect, String driverClassName) {
        this.dialect = dialect;
        this.driverClassName = driverClassName;
    }
}