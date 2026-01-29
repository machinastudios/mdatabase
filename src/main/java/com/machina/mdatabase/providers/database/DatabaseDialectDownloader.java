package com.machina.mdatabase.providers.database;

import java.util.HashMap;

public class DatabaseDialectDownloader {
    /**
     * The dialects
     */
    private static final HashMap<DatabaseDialect, DialectInfo> DIALECTS = new HashMap<>();

    /**
     * Load the given dialect from the `lib` directory
     * @param dialect The dialect to load
     * @return The class for the loaded dialect
     */
    public static Class<?> loadDialectDriverClass(DatabaseDialect dialect) {
        // Temporarily load the dialect from the classpath
        try {
            var dialectInfo = DIALECTS.get(dialect);

            return Class.forName(dialectInfo.driverClassName);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Failed to load dialect: " + dialect, e);
        }

        // Download the dialect if it's not already downloaded
        /*Path dialectPath = downloadDialect(dialect);

        // If the dialect path is null, throw an exception
        if (dialectPath == null) {
            throw new RuntimeException("Failed to download dialect: " + dialect);
        }

        // Get the dialect info
        DialectInfo dialectInfo = dialectUrls.get(dialect);

        try {
            // Download and load dependencies first
            for (String depUrl : dialectInfo.dependencyUrls) {
                Path depPath = downloadJar(depUrl);
                String depPathStr = depPath.toAbsolutePath().toString();

                if (!loadedJars.contains(depPathStr)) {
                    URL depJarUrl = depPath.toUri().toURL();
                    addJarToClassLoader(depJarUrl);
                    loadedJars.add(depPathStr);
                    logger.info("Loaded dependency: %s", depPath.getFileName());
                }
            }

            // Convert the path to a URL for adding to classloader
            URL jarUrl = dialectPath.toUri().toURL();
            String jarPath = dialectPath.toAbsolutePath().toString();

            // Only add the JAR if it hasn't been loaded yet
            if (!loadedJars.contains(jarPath)) {
                addJarToClassLoader(jarUrl);
                loadedJars.add(jarPath);
            }

            // Load the driver class using our dialect classloader
            Class<?> dialectClass = Class.forName(dialectInfo.driverClassName, true, getDialectClassLoader());

            // Instantiate the driver to register it with DriverManager
            // This ensures JDBC can find the driver when creating connections
            Object driverInstance = dialectClass.getDeclaredConstructor().newInstance();

            // Register the driver with DriverManager if it implements java.sql.Driver
            if (driverInstance instanceof java.sql.Driver) {
                java.sql.DriverManager.registerDriver((java.sql.Driver) driverInstance);
                logger.info("Registered JDBC driver: %s", dialectClass.getName());
            }

            logger.info("Loaded %s dialect driver class: %s", dialect, dialectClass.getName());

            // Return the class
            return dialectClass;
        } catch (Exception e) {
            throw new RuntimeException("Failed to load dialect driver class for: " + dialect, e);
        }*/
    }

    /**
     * The static initializer
     */
    static {
        DIALECTS.put(DatabaseDialect.SQLITE, new DialectInfo(
            DatabaseDialect.SQLITE,
            "org.sqlite.JDBC"
        ));

        DIALECTS.put(
            DatabaseDialect.MYSQL,
            new DialectInfo(
                DatabaseDialect.MYSQL,
                "com.mysql.cj.jdbc.Driver"
            )
        );

        DIALECTS.put(
            DatabaseDialect.POSTGRES, new DialectInfo(
                DatabaseDialect.POSTGRES,
                "org.postgresql.Driver"
            )
        );
    }
}
