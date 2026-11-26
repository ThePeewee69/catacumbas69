package com.catacumbas69.catasonarr.media;

import com.catacumbas69.catasonarr.model.MediaInfo;
import com.catacumbas69.catasonarr.parser.MediaFileExtensions;
import com.catacumbas69.catasonarr.parser.ParserText;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reads a video file with ffprobe (in Catasonarr's image, orch.md decision 122), as Sonarr's
 * {@code VideoFileInfoReader} (GPL-3.0): the main video stream (not a cover image), the first audio stream, every
 * audio and subtitle language, and the kind of HDR (from the colours, the transfer and the side data: Dolby Vision,
 * HDR10+, HDR10, HLG, PQ).
 */
@Component
public class MediaInfoReader {

    /** Sonarr's schema revision, so a file read by an older reader is read again. */
    public static final int SCHEMA_REVISION = 11;

    private static final Logger LOG = LoggerFactory.getLogger(MediaInfoReader.class);
    private static final Set<String> PQ = Set.of("smpte2084");
    private static final Set<String> HLG = Set.of("arib-std-b67");
    private static final Set<String> HDR_PRIMARIES = Set.of("bt2020");

    private final ObjectMapper json = new ObjectMapper();
    private final String ffprobe;

    /** Uses the {@code ffprobe} of the image's PATH. */
    public MediaInfoReader() {
        this("ffprobe");
    }

    /**
     * Uses another ffprobe.
     *
     * @param ffprobe its command
     */
    public MediaInfoReader(String ffprobe) {
        this.ffprobe = ffprobe;
    }

    /**
     * What a file holds.
     *
     * @param file the file
     * @return its media info, or {@code null} when ffprobe cannot read it (or it is a disc image or playlist)
     */
    public MediaInfo read(Path file) {
        String ext = ParserText.extension(file.getFileName().toString()).toLowerCase();
        if (MediaFileExtensions.DISK_EXTENSIONS.contains(ext) || MediaFileExtensions.STREAMING_EXTENSIONS.contains(ext)
                || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            JsonNode probe = run(List.of(ffprobe, "-v", "quiet", "-print_format", "json", "-show_format",
                    "-show_streams", "-probesize", "50000000", file.toString()));
            JsonNode video = primaryVideo(probe);
            JsonNode audio = first(probe, "audio");
            if (audio != null && audio.path("channel_layout").asText("").isBlank()) {
                probe = run(List.of(ffprobe, "-v", "quiet", "-print_format", "json", "-show_format", "-show_streams",
                        "-probesize", "150000000", "-analyzeduration", "150000000", file.toString()));
                video = primaryVideo(probe);
                audio = first(probe, "audio");
            }
            List<JsonNode> sideData = new ArrayList<>();
            if (video != null) {
                video.path("side_data_list").forEach(sideData::add);
                if (PQ.contains(video.path("color_transfer").asText())) {
                    JsonNode frames = run(List.of(ffprobe, "-v", "quiet", "-print_format", "json", "-show_frames",
                            "-read_intervals", "%+#1", "-select_streams", "v:" + video.path("index").asInt(0),
                            file.toString()));
                    frames.path("frames").path(0).path("side_data_list").forEach(sideData::add);
                }
            }
            int bitDepth = video == null ? 8 : bitDepth(video);
            List<String> audioLanguages = languages(probe, "audio");
            List<String> subtitles = languages(probe, "subtitle");
            double runtime = video != null && video.path("duration").asDouble() > 0 ? video.path("duration").asDouble()
                    : audio != null && audio.path("duration").asDouble() > 0 ? audio.path("duration").asDouble()
                    : probe.path("format").path("duration").asDouble();
            return new MediaInfo(SCHEMA_REVISION, probe.path("format").path("format_name").asText(null),
                    video == null ? null : video.path("codec_name").asText(null),
                    video == null ? null : video.path("codec_tag_string").asText(null),
                    video == null ? null : video.path("profile").asText(null),
                    video == null ? 0 : video.path("bit_rate").asLong(), bitDepth, 0,
                    video == null ? null : video.path("color_primaries").asText(null),
                    video == null ? null : video.path("color_transfer").asText(null),
                    video == null ? "" : hdr(bitDepth, video.path("color_primaries").asText(""),
                            video.path("color_transfer").asText(""), sideData),
                    video == null ? 0 : video.path("height").asInt(), video == null ? 0 : video.path("width").asInt(),
                    video == null ? 0 : fps(video.path("r_frame_rate").asText("0/1")),
                    audio == null ? null : audio.path("codec_name").asText(null),
                    audio == null ? null : audio.path("codec_tag_string").asText(null),
                    audio == null ? null : audio.path("profile").asText(null),
                    audio == null ? 0 : audio.path("bit_rate").asLong(),
                    audio == null ? 0 : audio.path("channels").asDouble(),
                    audio == null ? null : audio.path("channel_layout").asText(null), count(probe, "audio"),
                    audioLanguages, subtitles, runtime, "Progressive");
        } catch (IOException | RuntimeException e) {
            LOG.warn("Unable to read media info from {}: {}", file, e.getMessage());
            return null;
        }
    }

    /** Sonarr's {@code GetHdrFormat}. */
    static String hdr(int bitDepth, String primaries, String transfer, List<JsonNode> sideData) {
        if (bitDepth < 10) {
            return "";
        }
        JsonNode dovi = side(sideData, "DOVI configuration record");
        boolean hdr10Plus = side(sideData, "HDR Dynamic Metadata SMPTE2094-40 (HDR10+)") != null;
        if (dovi != null) {
            return switch (dovi.path("dv_bl_signal_compatibility_id").asInt(-1)) {
                case 1, 6 -> hdr10Plus ? "DV HDR10Plus" : "DV HDR10";
                case 2 -> "DV SDR";
                case 4 -> "DV HLG";
                default -> "DV";
            };
        }
        if (!HDR_PRIMARIES.contains(primaries) || !(PQ.contains(transfer) || HLG.contains(transfer))) {
            return "";
        }
        if (HLG.contains(transfer)) {
            return "HLG";
        }
        if (hdr10Plus) {
            return "HDR10Plus";
        }
        if (side(sideData, "Mastering display metadata") != null || side(sideData, "Content light level metadata")
                != null) {
            return "HDR10";
        }
        return "PQ";
    }

    private static JsonNode side(List<JsonNode> sideData, String type) {
        return sideData.stream().filter(s -> type.equalsIgnoreCase(s.path("side_data_type").asText())).findFirst()
                .orElse(null);
    }

    private JsonNode run(List<String> command) throws IOException {
        Process p = new ProcessBuilder(command).redirectErrorStream(false).start();
        byte[] out;
        try {
            out = p.getInputStream().readAllBytes();
            if (!p.waitFor(120, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("ffprobe took too long");
            }
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted");
        }
        return json.readTree(new String(out, StandardCharsets.UTF_8));
    }

    private static JsonNode primaryVideo(JsonNode probe) {
        JsonNode firstVideo = null;
        for (JsonNode s : probe.path("streams")) {
            if (!"video".equals(s.path("codec_type").asText())) {
                continue;
            }
            if (firstVideo == null) {
                firstVideo = s;
            }
            String codec = s.path("codec_name").asText();
            if (!codec.equals("mjpeg") && !codec.equals("png")) {
                return s;
            }
        }
        return firstVideo;
    }

    private static JsonNode first(JsonNode probe, String type) {
        for (JsonNode s : probe.path("streams")) {
            if (type.equals(s.path("codec_type").asText())) {
                return s;
            }
        }
        return null;
    }

    private static int count(JsonNode probe, String type) {
        int n = 0;
        for (JsonNode s : probe.path("streams")) {
            if (type.equals(s.path("codec_type").asText())) {
                n++;
            }
        }
        return n;
    }

    private static List<String> languages(JsonNode probe, String type) {
        List<String> out = new ArrayList<>();
        for (JsonNode s : probe.path("streams")) {
            String lang = s.path("tags").path("language").asText("");
            if (type.equals(s.path("codec_type").asText()) && !lang.isBlank()) {
                out.add(lang);
            }
        }
        return out;
    }

    private static int bitDepth(JsonNode video) {
        String raw = video.path("bits_per_raw_sample").asText("");
        if (raw.matches("\\d+")) {
            return Integer.parseInt(raw);
        }
        String pix = video.path("pix_fmt").asText("");
        if (pix.contains("12")) {
            return 12;
        }
        return pix.contains("10") ? 10 : 8;
    }

    private static double fps(String rate) {
        String[] parts = rate.split("/");
        try {
            double num = Double.parseDouble(parts[0]);
            double den = parts.length > 1 ? Double.parseDouble(parts[1]) : 1;
            return den == 0 ? 0 : num / den;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
