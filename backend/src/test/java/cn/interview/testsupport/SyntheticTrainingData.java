package cn.interview.testsupport;

import java.nio.file.Files;
import java.nio.file.Path;

public final class SyntheticTrainingData {
    public static Path migrated() {
        try {
            Path root = Files.createTempDirectory("interview-v2-synthetic-");
            Path data = root.resolve("data");
            var process = new ProcessBuilder("python3", "../scripts/prepare-training.py", "--data-dir", data.toString(),
                    "--backup", root.resolve("backup").toString(), "--confirm-maintenance")
                    .redirectErrorStream(true).start();
            String result = new String(process.getInputStream().readAllBytes());
            if (process.waitFor() != 0) throw new IllegalStateException(result);
            return data;
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
