package com.novoda.downloadmanager.demo;

import android.content.Context;

import com.novoda.downloadmanager.FilePath;
import com.novoda.downloadmanager.FilePathCreator;
import com.novoda.downloadmanager.FilePersistence;
import com.novoda.downloadmanager.FilePersistenceResult;
import com.novoda.downloadmanager.FilePersistenceType;
import com.novoda.downloadmanager.FileSize;
import com.novoda.notils.logger.simple.Log;

// Must be public
public class CustomFilePersistence implements FilePersistence {

    private int currentSize;

    @Override
    public void initialiseWith(Context context) {
        Log.v("initialise");
    }

    @Override
    public FilePath basePath() {
        FilePath filePath = FilePathCreator.unknownFilePath();
        Log.v("basePath " + filePath.toString());
        return filePath;
    }

    @Override
    public FilePersistenceResult create(FilePath absoluteFilePath, FileSize fileSize) {
        Log.v("create " + absoluteFilePath.toString() + ", " + fileSize.toString());
        return FilePersistenceResult.newInstance(FilePersistenceResult.Status.SUCCESS);
    }

    @Override
    public boolean write(byte[] buffer, int offset, int numberOfBytesToWrite) {
        Log.v("write offset: " + offset + ", numberOfBytesToWrite: " + numberOfBytesToWrite);
        currentSize = +numberOfBytesToWrite;
        return true;
    }

    @Override
    public void delete() {
        Log.v("delete");
    }

    @Override
    public long getCurrentSize() {
        Log.v("getCurrentSize: " + currentSize);
        return currentSize;
    }

    @Override
    public long getCurrentSize(FilePath filePath) {
        Log.v("getCurrentSize for " + filePath + ": " + currentSize);
        return 0;
    }

    @Override
    public void close() {
        Log.v("close");
    }

    @Override
    public FilePersistenceType getType() {
        return FilePersistenceType.CUSTOM;
    }
}