package org.jobrunr.tests.e2e;

import org.jobrunr.storage.StorageProvider;
import org.jobrunr.storage.sql.common.SqlJobStorageProviderFactory;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.JdbcDatabaseContainer;

public class PostgresGsonBackgroundJobContainer extends AbstractBackgroundJobSqlContainer {

    public PostgresGsonBackgroundJobContainer(JdbcDatabaseContainer sqlContainer) {
        super("jobrunr-e2e-postgres-gson:1.0", sqlContainer);
    }

    @Override
    protected StorageProvider initStorageProvider(JdbcDatabaseContainer sqlContainer) {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(sqlContainer.getJdbcUrl());
        dataSource.setUser(sqlContainer.getUsername());
        dataSource.setPassword(sqlContainer.getPassword());

        return SqlJobStorageProviderFactory.using(dataSource);
    }

}
