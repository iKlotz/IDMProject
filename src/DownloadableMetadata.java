import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Describes a file's metadata: URL, file name, size, and which parts already downloaded to disk.
 * The metadata is constantly stored safely on the disk.
 * When constructing a new metadata object, we check if there is existing metadata file first.
 */

class DownloadableMetadata {
    private final String metadataFilename;
    private String filename;
    private String url;
    private File metadataFile;
    private long size;
    private List<Range> rangeList;
    private List<Range> conserverRangeList;
    private int downloadedPercentage;

    DownloadableMetadata(String url) {
        this.url = url;
        this.filename = getName(url);
        this.metadataFilename = getMetadataName(this.filename);
        this.size = contentSize();
        this.metadataFile = getMDF();
        this.rangeList = makeRangeList();
        this.conserverRangeList = new ArrayList(this.rangeList);
        this.downloadedPercentage = 100 - this.rangeList.size();
    }

    private File getMDF() {
        File mdf = new File(this.metadataFilename);

        try {
            if (!mdf.exists()) {
                if (mdf.createNewFile()) {
                    initMDF(mdf);
                } else {
                    System.err.println("Couldn't create metadata file :( . Download failed.");
                    System.exit(-1);
                }
            }

            return mdf;

        } catch (IOException e) {
            System.err.println("Error getting metadata file :( . Download failed.");
            System.exit(-1);
            return null;
        }
    }

    private void initMDF(File mdf) {
        try {
            RandomAccessFile randomAccessMetadataFile = new RandomAccessFile(mdf, "rw");
            StringBuilder stringBuilder = new StringBuilder();
            Long start;
            Long end;
            Long percent;
            percent = this.size / 100;

            // write ranges to metadata
            for (long i = 0; i < 100; i++) {
                start = i * percent;
                end = start + percent - 1;
                //last range can be of different size
                if (i == 99 && end != this.size) {
                    end = this.size;
                }

                String sRange = Long.toString(start) + ',' + Long.toString(end) + "\n";
                stringBuilder.append(sRange);
            }

            randomAccessMetadataFile.writeBytes(stringBuilder.toString());
            randomAccessMetadataFile.close();

        } catch (IOException e) {
            System.err.println("Couldn't initiate metadata file :( Download failed.");
            System.exit(-1);
        }
    }

    private long contentSize() {
        try {
            URL url = new URL(this.url);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            int res = connection.getResponseCode();

            return (res / 100 == 2) ? connection.getContentLengthLong() : 0;
        } catch (IOException e) {
            System.err.println(e.getMessage());

            return 0;
        }
    }

    private List<Range> makeRangeList() {
        List<Range> rangeList = new ArrayList<>();

        try {
            RandomAccessFile ramdf = new RandomAccessFile(this.metadataFile, "rw");
            Range range;
            String line;
            String[] separatedRangeValues;

            while ((line = ramdf.readLine()) != null) {
                separatedRangeValues = line.split(",");
                range = new Range(Long.parseLong(separatedRangeValues[0]), Long.parseLong(separatedRangeValues[1]));
                rangeList.add(range);
            }

            ramdf.close();
        } catch (IOException e) {
            System.err.println("Error occurred while getting ranges. Download failed.");
            System.exit(-1);
        }

        return rangeList;
    }

    public void addDataToDynamicMetadata(Chunk chunk) {
        int i = this.rangeList.indexOf(chunk.getRange());
        Range oldRange = this.conserverRangeList.get(i);
        long newStart = oldRange.getStart() + chunk.getSizeInBytes();
        long newEnd = oldRange.getEnd();
        long dist = newStart - newEnd;
        Range newRange = new Range(newStart, newEnd);

        if (dist == 1 || dist == 0) {
            this.downloadedPercentage++;
            System.err.println("Downloaded " + this.downloadedPercentage + "%");
        }

        // print download complete message, delete mdf
        if (this.isCompleted()) {
            System.err.println("Download succeeded");
            this.delete();
        } else {
            this.conserverRangeList.set(i, newRange);
            writeNewRangeToTemp();
        }
    }

    private void writeNewRangeToTemp() {
        try {
            File tempMDF = new File(this.metadataFilename + ".tmp");

            if (!tempMDF.exists()) {
                if (!tempMDF.createNewFile()) {
                    System.err.println("Error creating temporary metadata file :( Download failed.");
                    System.exit(-1);
                }
            }

            // write current ranges to temp file
            RandomAccessFile ratmp = new RandomAccessFile(tempMDF, "rw"); //creates a random access stream
            StringBuilder stringBuilder = new StringBuilder();
            for (Range range : this.conserverRangeList) {
                long start = range.getStart();
                long end = range.getEnd();
                long dist = start - end;

                if (!(dist == 1 || dist == 0)) {
                    String sRange = Long.toString(start) + ',' + Long.toString(end) + "\n";
                    stringBuilder.append(sRange);
                }
            }

            ratmp.writeBytes(stringBuilder.toString());
            ratmp.close();

            //attempt to rename .tmp file
            try {
                Files.move(tempMDF.toPath(), this.metadataFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); //ATOMIC_MOVE ADDED
            } catch (IOException e) {
                System.err.println("Error renaming .tmp file. Download failed :(.");
                System.exit(-1);
            }
        } catch (IOException e) {
            System.err.println("Error writing to .tmp file. Download failed. Please try again.");
            System.exit(-1);
        }
    }

    String getFilename() {
        return this.filename;
    }

    boolean isCompleted() {
        return (this.downloadedPercentage == 100);
    }

    public List<Range> getRangeList() {
        return this.rangeList;
    }

    private static String getMetadataName(String filename) {
        return filename + ".metadata";
    }

    private static String getName(String path) {
        return path.substring(path.lastIndexOf('/') + 1, path.length());
    }

    private void delete() {
        if (this.metadataFile.exists()) {
            System.err.println("Deleting metadata file");

            try {
                Files.delete(this.metadataFile.toPath());
                System.err.println("Metadata file deleted");
            } catch (IOException e) {
                System.err.println("Metadata deletion failed");
            }
        }
    }
}
