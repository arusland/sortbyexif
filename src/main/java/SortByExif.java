import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sort media files in a folder by the year taken from the EXIF data.
 */
public class SortByExif {
    private final Path targetFolder;
    // Parse string like "Date/Time Original              : 2022:02:12 11:25:42"
    private static final Pattern PAT_DATE_TIME_ORIG = Pattern.compile("Date/Time Original\\s*:\\s*(\\d{4}):(\\d{2}):(\\d{2}) (\\d{2}):(\\d{2}):(\\d{2})");
    // parse string line "Offset Time                     : +03:00"
    private static final Pattern PAT_OFFSET_TIME = Pattern.compile("Offset Time\\s*:\\s*([+-]\\d{2}):(\\d{2})");

    public SortByExif(Path targetFolder) {
        this.targetFolder = targetFolder;
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("sortbyexif: move all media files in the target folder to subfolders by year taken");
            System.out.println("Usage: sortbyexif <target folder>");
            System.exit(1);
        }
        final Path targetFolder = Paths.get(args[0]).normalize().toAbsolutePath();
        System.out.println("Starting to sort files in folder: " + targetFolder + "...");
        try {
            final int exitCode = new SortByExif(targetFolder).sort();
            System.exit(exitCode);
        } catch (final IOException | InterruptedException e) {
            e.printStackTrace();
            System.exit(10);
        }
    }

    private int sort() throws IOException, InterruptedException {
        if (!Files.exists(targetFolder)) {
            System.out.println("Target folder does not exist: " + targetFolder);
            return 2;
        }
        if (!Files.isDirectory(targetFolder)) {
            System.out.println("Target folder is not a directory: " + targetFolder);
            return 3;
        }
        final List<Path> allFiles = Files.list(targetFolder).filter(Files::isRegularFile).toList();
        for (int i = 0; i < allFiles.size(); i++) {
            final Path file = allFiles.get(i);
            final int exitCode = sortFile(file);
            if (exitCode != 0) {
                System.out.println("Failed to sort file: " + file);
                return exitCode;
            }
            final int percentage = (i + 1) * 100 / allFiles.size();
            System.out.println(String.format("Processed %d of %d files: %s %%", i + 1, allFiles.size(), percentage));
        }

        return 0;
    }

    private int sortFile(final Path filePath) throws IOException, InterruptedException {
        final StringWriter output = new StringWriter();
        final int exitCode = runProgram(Arrays.asList("exiftool", filePath.toString()), output);
        if (exitCode != 0) {
            System.err.println("Failed to read EXIF data for file: " + filePath);
            System.err.println(output);
            return exitCode;
        }
        final String outputContent = output.toString();
        final Matcher matcher = PAT_DATE_TIME_ORIG.matcher(outputContent);
        if (matcher.find()) {
            final String year = matcher.group(1);
            final Path targetFolder = this.targetFolder.resolve(year);
            Files.createDirectories(targetFolder);
            final Path targetPath = targetFolder.resolve(filePath.getFileName());
            Files.move(filePath, targetPath);
            System.out.println("Moved file to: " + targetPath);
            final LocalDateTime dateTime = LocalDateTime.of(
                    Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3)),
                    Integer.parseInt(matcher.group(4)),
                    Integer.parseInt(matcher.group(5)),
                    Integer.parseInt(matcher.group(6))
            );
            final FileTime creationDate = FileTime.fromMillis(dateTime.toEpochSecond(getOffsetByMeta(outputContent)) * 1000);
            final BasicFileAttributeView attributes = Files.getFileAttributeView(targetPath, BasicFileAttributeView.class);
            attributes.setTimes(creationDate, creationDate, creationDate);
        } else {
            System.out.println("Skip file: " + filePath);
        }
        return 0;
    }

    private int runProgram(final List<String> command, final Writer output) throws IOException, InterruptedException {
        final ProcessBuilder processBuilder = new ProcessBuilder(command);
        final Process process = processBuilder.start();
        final BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        String line;
        while ((line = reader.readLine()) != null) {
            output.write(line);
            output.write("\n");
        }

        return process.waitFor();
    }

    private ZoneOffset getOffsetByMeta(final String exifOutput) {
        final Matcher matcher = PAT_OFFSET_TIME.matcher(exifOutput);
        if (matcher.find()) {
            final int hours = Integer.parseInt(matcher.group(1));
            final int minutes = Integer.parseInt(matcher.group(2));
            return ZoneOffset.ofHoursMinutes(hours, minutes);
        }
        return ZoneOffset.ofHoursMinutes(2, 0);
    }
}