package com.novoda.downloadmanager;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

import java.io.File;

final class MigrationFactory {

    private MigrationFactory() {
        // Uses static methods.
    }

    static Migrator createVersionOneToVersionTwoMigrator(Context context, File databasePath) {
        if (!databasePath.exists()) {
            return Migrator.NO_OP;
        }

        SQLiteDatabase sqLiteDatabase = SQLiteDatabase.openDatabase(databasePath.getAbsolutePath(), null, 0);
        SqlDatabaseWrapper database = new SqlDatabaseWrapper(sqLiteDatabase);

        MigrationExtractor migrationExtractor = new MigrationExtractor(database);
        RoomDownloadsPersistence downloadsPersistence = RoomDownloadsPersistence.newInstance(context);
        InternalFilePersistence internalFilePersistence = new InternalFilePersistence();
        internalFilePersistence.initialiseWith(context);
        return new VersionOneToVersionTwoMigrator(migrationExtractor, downloadsPersistence, internalFilePersistence, database);
    }

}
