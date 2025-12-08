package org.cttelsamicsterrassa.data.importer.shared.service;

import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvValidationException;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.NoSuchElementException;
import java.util.Queue;
import java.util.UUID;

public abstract class MatchResultDetailsByLineIterator<FileRowInfo, FileInfo> implements Iterator<FileRowInfo>, AutoCloseable {

    protected CSVReader currentReader;
    private Queue<FileInfo> fileQueue = new LinkedList<>();
    private String[] nextLine;
    private FileInfo currentInfo;

    @Override
    public void close() {
        try {
            if (currentReader != null) currentReader.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public boolean hasNext() {
        return nextLine != null;
    }

    @Override
    public FileRowInfo next() {
        if (!hasNext()) throw new NoSuchElementException();
        String[] lineToReturn = nextLine;
        FileRowInfo rowInfoToReturn = createFileRowInfo(currentInfo, lineToReturn, UUID.randomUUID());
        advanceReader();
        return rowInfoToReturn;
    }

    protected abstract FileRowInfo createFileRowInfo(FileInfo currentInfo, String[] lineToReturn, UUID uuid);

    public void resetAndLoadTextFilesForSeason(File dir, String seasonRange) throws IOException {
        clearQueue();
        if (currentReader != null) currentReader.close();
        loadTextFilesForSeason(dir, seasonRange);
        advanceReader();
    }

    public void resetAndLoadTextFilesForAllSeasons(File dir) throws IOException {
        clearQueue();
        if (currentReader != null) currentReader.close();
        loadTextFilesForAllSeasons(dir);
        advanceReader();
    }

    private void loadTextFilesForSeason(File baseNatchesDetailsCsvFilesFolder, String seasonRange) throws IOException {
        processMatchesDetailsForSeason(baseNatchesDetailsCsvFilesFolder.toString(), seasonRange);
    }

    private void loadTextFilesForAllSeasons(File baseNatchesDetailsCsvFilesFolder) throws IOException {
        processMatchesDetailsForAllSeasons(baseNatchesDetailsCsvFilesFolder.toString());
    }

    protected abstract void processMatchesDetailsForAllSeasons(String baseNatchesDetailsCsvFilesFolder) throws IOException;

    protected abstract void processMatchesDetailsForSeason(String baseNatchesDetailsCsvFilesFolder, String seasonRange) throws IOException;

    protected void advanceReader() {
        try {
            while (currentReader == null || (nextLine = currentReader.readNext()) == null) {
                if (currentReader != null) currentReader.close();
                if (isQueueEmpty()) {
                    nextLine = null;
                    return;
                }
                currentInfo = pollFromQueue();
                currentReader = getReaderFromBufferedReader(currentInfo);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (CsvValidationException e) {
            throw new RuntimeException(e);
        }
    }

    protected abstract CSVReader getReaderFromBufferedReader(FileInfo fileInfo) throws FileNotFoundException;

    protected void clearQueue() {
        fileQueue.clear();
    }

    protected boolean isQueueEmpty() {
        return fileQueue.isEmpty();
    }

    protected void addToQueue(FileInfo fileInfo) {
        fileQueue.add(fileInfo);
    }

    protected FileInfo pollFromQueue() {
        return fileQueue.poll();
    }
}
