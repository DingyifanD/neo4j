/*
 * Copyright (c) 2002-2019 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl.storemigration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

import org.neo4j.common.ProgressReporter;
import org.neo4j.configuration.Config;
import org.neo4j.dbms.api.DatabaseManagementService;
import org.neo4j.graphdb.Transaction;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.io.layout.DatabaseLayout;
import org.neo4j.io.layout.Neo4jLayout;
import org.neo4j.io.pagecache.PageCache;
import org.neo4j.kernel.impl.store.MetaDataStore;
import org.neo4j.kernel.impl.store.format.standard.MetaDataRecordFormat;
import org.neo4j.kernel.impl.store.format.standard.StandardV4_0;
import org.neo4j.kernel.impl.transaction.log.LogPosition;
import org.neo4j.kernel.impl.transaction.log.files.LogFiles;
import org.neo4j.kernel.impl.transaction.log.files.LogFilesBuilder;
import org.neo4j.kernel.internal.GraphDatabaseAPI;
import org.neo4j.logging.NullLogProvider;
import org.neo4j.logging.internal.LogService;
import org.neo4j.logging.internal.NullLogService;
import org.neo4j.logging.internal.SimpleLogService;
import org.neo4j.scheduler.JobScheduler;
import org.neo4j.storageengine.api.TransactionId;
import org.neo4j.storageengine.api.TransactionIdStore;
import org.neo4j.test.TestDatabaseManagementServiceBuilder;
import org.neo4j.test.extension.Inject;
import org.neo4j.test.extension.Neo4jLayoutExtension;
import org.neo4j.test.extension.RandomExtension;
import org.neo4j.test.extension.pagecache.PageCacheExtension;
import org.neo4j.test.rule.RandomRule;
import org.neo4j.test.rule.TestDirectory;
import org.neo4j.test.scheduler.ThreadPoolJobScheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.neo4j.configuration.GraphDatabaseSettings.DEFAULT_DATABASE_NAME;
import static org.neo4j.configuration.GraphDatabaseSettings.transaction_logs_root_path;
import static org.neo4j.kernel.impl.store.MetaDataStore.Position.LAST_TRANSACTION_CHECKSUM;
import static org.neo4j.kernel.impl.store.MetaDataStore.Position.LAST_TRANSACTION_COMMIT_TIMESTAMP;
import static org.neo4j.kernel.impl.store.MetaDataStore.Position.LAST_TRANSACTION_ID;
import static org.neo4j.kernel.impl.store.MetaDataStore.getRecord;
import static org.neo4j.kernel.impl.store.MetaDataStore.setRecord;
import static org.neo4j.kernel.impl.store.format.standard.MetaDataRecordFormat.FIELD_NOT_PRESENT;

@PageCacheExtension
@Neo4jLayoutExtension
@ExtendWith( RandomExtension.class )
class StoreMigratorTest
{
    @Inject
    private TestDirectory testDirectory;
    @Inject
    private FileSystemAbstraction fileSystem;
    @Inject
    private PageCache pageCache;
    @Inject
    private Neo4jLayout neo4jLayout;
    @Inject
    private DatabaseLayout databaseLayout;
    @Inject
    private RandomRule random;
    private JobScheduler jobScheduler;

    @BeforeEach
    void setUp()
    {
        jobScheduler = new ThreadPoolJobScheduler();
    }

    @AfterEach
    void tearDown() throws Exception
    {
        jobScheduler.close();
    }

    @Test
    void shouldExtractTransactionInformationFromMetaDataStore() throws Exception
    {
        // given
        // ... variables
        long txId = 42;
        int checksum = 123456789;
        long timestamp = 919191919191919191L;
        TransactionId expected = new TransactionId( txId, checksum, timestamp );

        // ... and files

        File neoStore = databaseLayout.metadataStore();
        neoStore.createNewFile();

        // ... and mocks
        Config config = mock( Config.class );
        LogService logService = mock( LogService.class );

        // when
        // ... data in record
        setRecord( pageCache, neoStore, LAST_TRANSACTION_ID, txId );
        setRecord( pageCache, neoStore, LAST_TRANSACTION_CHECKSUM, checksum );
        setRecord( pageCache, neoStore, LAST_TRANSACTION_COMMIT_TIMESTAMP, timestamp );

        // ... and with migrator
        RecordStorageMigrator migrator = new RecordStorageMigrator( fileSystem, pageCache, config, logService, jobScheduler );
        TransactionId actual = migrator.extractTransactionIdInformation( neoStore, txId );

        // then
        assertEquals( expected, actual );
    }

    @Test
    void shouldGenerateTransactionInformationWhenLogsNotPresent() throws Exception
    {
        // given
        long txId = 42;

        File neoStore = databaseLayout.metadataStore();
        neoStore.createNewFile();
        Config config = mock( Config.class );
        LogService logService = new SimpleLogService( NullLogProvider.getInstance(), NullLogProvider.getInstance() );

        // when
        // ... transaction info not in neo store
        assertEquals( FIELD_NOT_PRESENT, getRecord( pageCache, neoStore, LAST_TRANSACTION_ID ) );
        assertEquals( FIELD_NOT_PRESENT, getRecord( pageCache, neoStore, LAST_TRANSACTION_CHECKSUM ) );
        assertEquals( FIELD_NOT_PRESENT, getRecord( pageCache, neoStore, LAST_TRANSACTION_COMMIT_TIMESTAMP ) );
        // ... and with migrator
        RecordStorageMigrator migrator = new RecordStorageMigrator( fileSystem, pageCache, config, logService, jobScheduler );
        TransactionId actual = migrator.extractTransactionIdInformation( neoStore, txId );

        // then
        assertEquals( txId, actual.transactionId() );
        assertEquals( TransactionIdStore.UNKNOWN_TX_CHECKSUM, actual.checksum() );
        assertEquals( TransactionIdStore.UNKNOWN_TX_COMMIT_TIMESTAMP, actual.commitTimestamp() );
    }

    @Test
    void extractTransactionInformationFromLogsInCustomAbsoluteLocation() throws Exception
    {
        File customLogLocation = testDirectory.directory( "customLogLocation" );
        extractTransactionalInformationFromLogs( customLogLocation.toPath().toAbsolutePath() );
    }

    @Test
    void shouldGenerateTransactionInformationWhenLogsAreEmpty() throws Exception
    {
        // given
        long txId = 1;

        File neoStore = databaseLayout.metadataStore();
        neoStore.createNewFile();
        Config config = mock( Config.class );
        LogService logService = new SimpleLogService( NullLogProvider.getInstance(), NullLogProvider.getInstance() );

        // when
        // ... transaction info not in neo store
        assertEquals( FIELD_NOT_PRESENT, getRecord( pageCache, neoStore, LAST_TRANSACTION_ID ) );
        assertEquals( FIELD_NOT_PRESENT, getRecord( pageCache, neoStore, LAST_TRANSACTION_CHECKSUM ) );
        assertEquals( FIELD_NOT_PRESENT, getRecord( pageCache, neoStore, LAST_TRANSACTION_COMMIT_TIMESTAMP ) );
        // ... and with migrator
        RecordStorageMigrator migrator = new RecordStorageMigrator( fileSystem, pageCache, config, logService, jobScheduler );
        TransactionId actual = migrator.extractTransactionIdInformation( neoStore, txId );

        // then
        assertEquals( txId, actual.transactionId() );
        assertEquals( TransactionIdStore.BASE_TX_CHECKSUM, actual.checksum() );
        assertEquals( TransactionIdStore.BASE_TX_COMMIT_TIMESTAMP, actual.commitTimestamp() );
    }

    @Test
    void writeAndReadLastTxInformation() throws IOException
    {
        RecordStorageMigrator migrator = newStoreMigrator();
        TransactionId writtenTxId = new TransactionId( random.nextLong(), random.nextInt(), random.nextLong() );

        migrator.writeLastTxInformation( databaseLayout, writtenTxId );

        TransactionId readTxId = migrator.readLastTxInformation( databaseLayout );

        assertEquals( writtenTxId, readTxId );
    }

    @Test
    void writeAndReadLastTxLogPosition() throws IOException
    {
        RecordStorageMigrator migrator = newStoreMigrator();
        LogPosition writtenLogPosition = new LogPosition( random.nextLong(), random.nextLong() );

        migrator.writeLastTxLogPosition( databaseLayout, writtenLogPosition );

        LogPosition readLogPosition = migrator.readLastTxLogPosition( databaseLayout );

        assertEquals( writtenLogPosition, readLogPosition );
    }

    @Test
    void shouldNotMigrateFilesForVersionsWithSameCapability() throws Exception
    {
        // Prepare migrator and file
        RecordStorageMigrator migrator = newStoreMigrator();
        DatabaseLayout dbLayout = databaseLayout;
        File neoStore = dbLayout.metadataStore();
        neoStore.createNewFile();

        // Monitor what happens
        MyProgressReporter progressReporter = new MyProgressReporter();
        // Migrate with two storeversions that have the same FORMAT capabilities
        DatabaseLayout migrationLayout = neo4jLayout.databaseLayout( "migrationDir" );
        fileSystem.mkdirs( migrationLayout.databaseDirectory() );
        migrator.migrate( dbLayout, migrationLayout, progressReporter,
                StandardV4_0.STORE_VERSION, StandardV4_0.STORE_VERSION );

        // Should not have started any migration
        assertFalse( progressReporter.started );
    }

    private void extractTransactionalInformationFromLogs( Path customLogsLocation ) throws IOException
    {
        Config config = Config.defaults( transaction_logs_root_path, customLogsLocation );
        LogService logService = new SimpleLogService( NullLogProvider.getInstance(), NullLogProvider.getInstance() );

        DatabaseManagementService managementService = new TestDatabaseManagementServiceBuilder( databaseLayout )
                        .setConfig( transaction_logs_root_path, customLogsLocation )
                        .build();
        GraphDatabaseAPI database = (GraphDatabaseAPI) managementService.database( DEFAULT_DATABASE_NAME );
        for ( int i = 0; i < 10; i++ )
        {
            try ( Transaction transaction = database.beginTx() )
            {
                transaction.createNode();
                transaction.commit();
            }
        }
        DatabaseLayout databaseLayout = database.databaseLayout();
        File neoStore = databaseLayout.metadataStore();
        managementService.shutdown();

        MetaDataStore.setRecord( pageCache, neoStore, MetaDataStore.Position.LAST_CLOSED_TRANSACTION_LOG_VERSION,
                MetaDataRecordFormat.FIELD_NOT_PRESENT );
        RecordStorageMigrator migrator = new RecordStorageMigrator( fileSystem, pageCache, config, logService, jobScheduler );
        LogPosition logPosition = migrator.extractTransactionLogPosition( neoStore, databaseLayout, 100 );

        LogFiles logFiles = LogFilesBuilder.activeFilesBuilder( databaseLayout, fileSystem, pageCache ).withConfig( config ).build();
        assertEquals( 0, logPosition.getLogVersion() );
        assertEquals( logFiles.getHighestLogFile().length(), logPosition.getByteOffset() );
    }

    private RecordStorageMigrator newStoreMigrator()
    {
        return new RecordStorageMigrator( fileSystem, pageCache, Config.defaults(), NullLogService.getInstance(), jobScheduler );
    }

    private static class MyProgressReporter implements ProgressReporter
    {
        public boolean started;

        @Override
        public void start( long max )
        {
            started = true;
        }

        @Override
        public void progress( long add )
        {

        }

        @Override
        public void completed()
        {

        }
    }
}
