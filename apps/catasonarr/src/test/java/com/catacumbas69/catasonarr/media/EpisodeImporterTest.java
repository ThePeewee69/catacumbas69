package com.catacumbas69.catasonarr.media;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EpisodeImporterTest {

    @TempDir
    Path dir;

    @Test
    void videoFiles_skipsSonarrsExcludedFoldersAndFiles() throws Exception {
        Path release = Files.createDirectories(dir.resolve("Show.S01.1080p.WEB-DL-GRP"));
        Files.writeString(release.resolve("Show.S01E01.mkv"), "x");
        Files.writeString(release.resolve("Show.S01E02.mp4"), "x");
        Files.writeString(release.resolve("Show.S01E01.srt"), "x");
        Files.writeString(release.resolve("._Show.S01E03.mkv"), "x");
        Files.writeString(release.resolve("Show-Trailer.mkv"), "x");
        Files.writeString(Files.createDirectories(release.resolve("extras")).resolve("Bonus.mkv"), "x");
        Files.writeString(Files.createDirectories(release.resolve(".hidden")).resolve("Show.S01E04.mkv"), "x");
        Files.writeString(Files.createDirectories(release.resolve("Sub")).resolve("Show.S01E05.mkv"), "x");

        assertThat(EpisodeImporter.videoFiles(release)).extracting(p -> release.relativize(p).toString())
                .containsExactly("Show.S01E01.mkv", "Show.S01E02.mp4", "Sub/Show.S01E05.mkv");
    }

    @Test
    void videoFiles_ofOneFile_isThatFile_whenItIsAVideo() throws Exception {
        Path video = Files.writeString(dir.resolve("Show.S01E01.mkv"), "x");
        Path text = Files.writeString(dir.resolve("Show.S01E01.nfo"), "x");

        assertThat(EpisodeImporter.videoFiles(video)).containsExactly(video);
        assertThat(EpisodeImporter.videoFiles(text)).isEmpty();
        assertThat(EpisodeImporter.videoFiles(dir.resolve("missing"))).isEmpty();
    }
}
