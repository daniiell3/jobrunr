package org.jobrunr.tests.e2e;

import org.jobrunr.storage.StorageProvider;
import org.jobrunr.storage.sql.common.SqlJobStorageProviderFactory;
import org.mariadb.jdbc.MariaDbPoolDataSource;
import org.testcontainers.containers.JdbcDatabaseContainer;

public class MariaDbGsonBackgroundJobContainer extends AbstractBackgroundJobSqlContainer {

    public MariaDbGsonBackgroundJobContainer(JdbcDatabaseContainer sqlContainer) {
        super("jobrunr-e2e-mariadb-gson:1.0", sqlContainer);
    }

    @Override
    protected StorageProvider initStorageProvider(JdbcDatabaseContainer sqlContainer) throws Exception {
        MariaDbPoolDataSource dataSource = new MariaDbPoolDataSource();
        dataSource.setUrl(sqlContainer.getJdbcUrl() + "?rewriteBatchedStatements=true&pool=true");
        dataSource.setUser(sqlContainer.getUsername());
        dataSource.setPassword(sqlContainer.getPassword());
        return SqlJobStorageProviderFactory.using(dataSource);
    }

}
