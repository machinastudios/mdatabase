package com.machina.mdatabase.providers.database;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Logger;

public class DatabaseDialectDownloader {
    /**
     * The URLs of the dialects
     */
    private static final HashMap<DatabaseDialect, DialectInfo> dialectUrls = new HashMap<>();

    /**
     * Set of already loaded JARs to avoid duplicate loading
     */
    private static final Set<String> loadedJars = new HashSet<>();

    /**
     * The logger for the DatabaseDialectDownloader
     */
    private static Logger logger = Logger.getLogger(DatabaseDialectDownloader.class.getName());

    /**
     * The static initializer
     */
    static {
        // SQLite requires SLF4J as a dependency
        dialectUrls.put(DatabaseDialect.SQLITE, new DialectInfo(
            DatabaseDialect.SQLITE,
            "https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/3.44.1.0/sqlite-jdbc-3.44.1.0.jar",
            "org.sqlite.JDBC",
            new String[] {
                "https://repo1.maven.org/maven2/org/slf4j/slf4j-api/2.0.9/slf4j-api-2.0.9.jar",
                "https://repo1.maven.org/maven2/org/slf4j/slf4j-simple/2.0.9/slf4j-simple-2.0.9.jar"
            }
        ));

        dialectUrls.put(
            DatabaseDialect.MYSQL,
            new DialectInfo(
                DatabaseDialect.MYSQL,
                "https://repo1.maven.org/maven2/com/mysql/cj/mysql-connector-j/8.0.33/mysql-connector-j-8.0.33.jar",
                "com.mysql.cj.jdbc.Driver",
                new String[] {}
            )
        );

        dialectUrls.put(
            DatabaseDialect.POSTGRES, new DialectInfo(
                DatabaseDialect.POSTGRES,
                "https://repo1.maven.org/maven2/org/postgresql/postgresql/42.7.0/postgresql-42.7.0.jar",
                "org.postgresql.Driver",
                new String[] {}
            )
        );
    }

    /**
     * Download the given dialect to the `lib` directory
     * @param dialect The dialect to download
     * @return The path to the downloaded dialect
     */
    public static Path downloadDialect(DatabaseDialect dialect) {
        // Get the URL of the dialect
        DialectInfo dialectInfo = dialectUrls.get(dialect);

        // If the dialect is not supported, throw an exception
        if (dialectInfo == null) {
            throw new IllegalArgumentException("Unsupported dialect: " + dialect);
        }

        // Get the path to the downloaded dialect
        Path dialectPath = new File("lib/mdatabase/database/dialects/" + dialectInfo.dialect.name().toLowerCase() + ".jar").toPath();

        // Get the parent directory of the dialect path
        Path parentDirectory = dialectPath.getParent();

        // If the parent directory does not exist, create it
        if (!Files.exists(parentDirectory)) {
            try {
                Files.createDirectories(parentDirectory);
            } catch (IOException e) {
                logger.severe("Failed to create parent directory: " + parentDirectory);
                logger.severe(e.getMessage());
                throw new RuntimeException("Failed to create parent directory: " + parentDirectory, e);
            }
        }

        // If the dialect is already downloaded, return the path
        if (Files.exists(dialectPath)) {
            logger.info("Dialect " + dialect + " already downloaded");
            return dialectPath;
        }

        // Try to download the dialect
        try {
            logger.info("Downloading dialect " + dialect + " from " + dialectInfo.url);

            URLConnection connection = java.net.URI.create(dialectInfo.url).toURL().openConnection();

            InputStream inputStream = connection.getInputStream();
            Files.copy(inputStream, dialectPath, StandardCopyOption.REPLACE_EXISTING);
            inputStream.close();

            logger.info("Dialect " + dialect + " downloaded");

            return dialectPath;
        } catch (IOException e) {
            logger.severe("Failed to download dialect: " + dialect);
            logger.severe(e.getMessage());
            throw new RuntimeException("Failed to download dialect: " + dialect, e);
        }
    }

    /**
     * Download a JAR from a URL to the lib directory
     * @param jarUrl The URL of the JAR to download
     * @return The path to the downloaded JAR
     */
    private static Path downloadJar(String jarUrl) {
        // Extract filename from URL
        String filename = jarUrl.substring(jarUrl.lastIndexOf('/') + 1);
        Path jarPath = new File("lib/mdatabase/database/deps/" + filename).toPath();

        // Get the parent directory
        Path parentDirectory = jarPath.getParent();

        // Create parent directory if needed
        if (!Files.exists(parentDirectory)) {
            try {
                Files.createDirectories(parentDirectory);
            } catch (IOException e) {
                throw new RuntimeException("Failed to create parent directory: " + parentDirectory, e);
            }
        }

        // If already downloaded, return the path
        if (Files.exists(jarPath)) {
            logger.info("Dependency " + filename + " already downloaded");
            return jarPath;
        }

        // Download the JAR
        try {
            logger.info("Downloading dependency " + filename + " from " + jarUrl);

            URLConnection connection = java.net.URI.create(jarUrl).toURL().openConnection();
            InputStream inputStream = connection.getInputStream();
            Files.copy(inputStream, jarPath, StandardCopyOption.REPLACE_EXISTING);
            inputStream.close();

            logger.info("Dependency " + filename + " downloaded");
            return jarPath;
        } catch (IOException e) {
            throw new RuntimeException("Failed to download dependency: " + jarUrl, e);
        }
    }

    /**
     * Custom classloader for loading dialect JARs
     */
    private static java.net.URLClassLoader dialectClassLoader = null;

    /**
     * Add a JAR file to our custom classloader and set it as the thread context classloader
     * @param jarUrl The URL of the JAR file to add
     */
    private static void addJarToClassLoader(URL jarUrl) {
        try {
            if (dialectClassLoader == null) {
                // Create a new URLClassLoader with the system classloader as parent
                dialectClassLoader = new java.net.URLClassLoader(
                    new URL[] { jarUrl },
                    ClassLoader.getSystemClassLoader()
                );
            } else {
                // Create a new classloader that includes the previous URLs plus the new one
                URL[] existingUrls = dialectClassLoader.getURLs();
                URL[] newUrls = new URL[existingUrls.length + 1];
                System.arraycopy(existingUrls, 0, newUrls, 0, existingUrls.length);
                newUrls[existingUrls.length] = jarUrl;

                dialectClassLoader = new java.net.URLClassLoader(
                    newUrls,
                    ClassLoader.getSystemClassLoader()
                );
            }

            // Set as thread context classloader so JDBC DriverManager can find drivers
            Thread.currentThread().setContextClassLoader(dialectClassLoader);

            logger.info("Added JAR to dialect classloader: " + jarUrl);
        } catch (Exception e) {
            throw new RuntimeException("Failed to add JAR to classloader: " + jarUrl, e);
        }
    }

    /**
     * Get the dialect classloader (or system classloader if not initialized)
     * @return The classloader to use for loading dialect classes
     */
    public static ClassLoader getDialectClassLoader() {
        return dialectClassLoader != null ? dialectClassLoader : ClassLoader.getSystemClassLoader();
    }

    /**
     * Download and load Jakarta Persistence and Hibernate dependencies
     * Should be called before initializing EntityManagerFactory
     */
    public static void loadJakartaDependencies() {
        // URLs for Jakarta and Hibernate dependencies
        String[] jakartaDeps = {
            // Jakarta Persistence API
            "https://repo1.maven.org/maven2/jakarta/persistence/jakarta.persistence-api/3.1.0/jakarta.persistence-api-3.1.0.jar",
            // Hibernate Core
            "https://repo1.maven.org/maven2/org/hibernate/orm/hibernate-core/6.4.4.Final/hibernate-core-6.4.4.Final.jar",
            // Hibernate Community Dialects
            "https://repo1.maven.org/maven2/org/hibernate/orm/hibernate-community-dialects/6.4.4.Final/hibernate-community-dialects-6.4.4.Final.jar"
        };

        for (String depUrl : jakartaDeps) {
            try {
                Path depPath = downloadJar(depUrl);
                String depPathStr = depPath.toAbsolutePath().toString();

                if (!loadedJars.contains(depPathStr)) {
                    URL depJarUrl = depPath.toUri().toURL();
                    addJarToClassLoader(depJarUrl);
                    loadedJars.add(depPathStr);
                    logger.info("Loaded Jakarta/Hibernate dependency: " + depPath.getFileName());
                }
            } catch (Exception e) {
                logger.warning("Failed to load Jakarta/Hibernate dependency: " + depUrl + " - " + e.getMessage());
                // Continue loading other dependencies even if one fails
            }
        }
    }

    /**
     * Load the given dialect from the `lib` directory
     * @param dialect The dialect to load
     * @return The class for the loaded dialect
     */
    public static Class<?> loadDialectDriverClass(DatabaseDialect dialect) {
        // Download the dialect if it's not already downloaded
        Path dialectPath = downloadDialect(dialect);

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
                    logger.info("Loaded dependency: " + depPath.getFileName());
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
                logger.info("Registered JDBC driver: " + dialectClass.getName());
            }

            logger.info("Loaded " + dialect + " dialect driver class: " + dialectClass.getName());

            // Return the class
            return dialectClass;
        } catch (Exception e) {
            throw new RuntimeException("Failed to load dialect driver class for: " + dialect, e);
        }
    }
}
