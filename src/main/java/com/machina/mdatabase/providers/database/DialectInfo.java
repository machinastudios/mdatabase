package com.machina.mdatabase.providers.database;

public class DialectInfo {
    /**
     * The dialect
     */
    public final DatabaseDialect dialect;

    /**
     * The URL of the dialect
     */
    public final String url;

    /**
     * The class name of the dialect driver
     */
    public final String driverClassName;

    /**
     * URLs of dependencies that must be loaded before the driver
     */
    public final String[] dependencyUrls;

    /**
     * Constructor
     * @param dialect The dialect
     * @param url The URL of the dialect
     * @param driverClassName The class name of the dialect driver
     */
    public DialectInfo(DatabaseDialect dialect, String url, String driverClassName) {
        this(dialect, url, driverClassName, new String[0]);
    }

    /**
     * Constructor with dependencies
     * @param dialect The dialect
     * @param url The URL of the dialect
     * @param driverClassName The class name of the dialect driver
     * @param dependencyUrls URLs of dependencies to load before the driver
     */
    public DialectInfo(DatabaseDialect dialect, String url, String driverClassName, String[] dependencyUrls) {
        this.dialect = dialect;
        this.url = url;
        this.driverClassName = driverClassName;
        this.dependencyUrls = dependencyUrls;
    }
}