package com.catacumbas69.catasonarr.parser;

import com.catacumbas69.catasonarr.quality.Qualities;
import com.catacumbas69.catasonarr.quality.Quality;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The video files Catasonarr knows and the quality their extension alone suggests, translated from Sonarr 4.0.20's
 * {@code MediaFileExtensions.cs} (GPL-3.0, orch.md decision 122). Extensions are compared without case.
 */
public final class MediaFileExtensions {

    private static final Map<String, Quality> FILE_EXTENSIONS = Map.ofEntries(
            Map.entry(".webm", Qualities.UNKNOWN),
            Map.entry(".m4v", Qualities.SDTV),
            Map.entry(".3gp", Qualities.SDTV),
            Map.entry(".nsv", Qualities.SDTV),
            Map.entry(".ty", Qualities.SDTV),
            Map.entry(".strm", Qualities.SDTV),
            Map.entry(".rm", Qualities.SDTV),
            Map.entry(".rmvb", Qualities.SDTV),
            Map.entry(".m3u", Qualities.SDTV),
            Map.entry(".ifo", Qualities.SDTV),
            Map.entry(".mov", Qualities.SDTV),
            Map.entry(".qt", Qualities.SDTV),
            Map.entry(".divx", Qualities.SDTV),
            Map.entry(".xvid", Qualities.SDTV),
            Map.entry(".bivx", Qualities.SDTV),
            Map.entry(".nrg", Qualities.SDTV),
            Map.entry(".pva", Qualities.SDTV),
            Map.entry(".wmv", Qualities.SDTV),
            Map.entry(".asf", Qualities.SDTV),
            Map.entry(".asx", Qualities.SDTV),
            Map.entry(".ogm", Qualities.SDTV),
            Map.entry(".ogv", Qualities.SDTV),
            Map.entry(".m2v", Qualities.SDTV),
            Map.entry(".avi", Qualities.SDTV),
            Map.entry(".bin", Qualities.SDTV),
            Map.entry(".dat", Qualities.SDTV),
            Map.entry(".dvr-ms", Qualities.SDTV),
            Map.entry(".mpg", Qualities.SDTV),
            Map.entry(".mpeg", Qualities.SDTV),
            Map.entry(".mp4", Qualities.SDTV),
            Map.entry(".avc", Qualities.SDTV),
            Map.entry(".vp3", Qualities.SDTV),
            Map.entry(".svq3", Qualities.SDTV),
            Map.entry(".nuv", Qualities.SDTV),
            Map.entry(".viv", Qualities.SDTV),
            Map.entry(".dv", Qualities.SDTV),
            Map.entry(".fli", Qualities.SDTV),
            Map.entry(".flv", Qualities.SDTV),
            Map.entry(".wpl", Qualities.SDTV),
            Map.entry(".img", Qualities.DVD),
            Map.entry(".iso", Qualities.DVD),
            Map.entry(".vob", Qualities.DVD),
            Map.entry(".mkv", Qualities.HDTV_720P),
            Map.entry(".ts", Qualities.HDTV_720P),
            Map.entry(".wtv", Qualities.HDTV_720P),
            Map.entry(".m2ts", Qualities.BLURAY_720P));

    /** Disc images. */
    public static final Set<String> DISK_EXTENSIONS = Set.of(".img", ".iso", ".vob");

    /** Playlists that stream from elsewhere. */
    public static final Set<String> STREAMING_EXTENSIONS = Set.of(".m3u", ".strm");

    private MediaFileExtensions() {
    }

    /**
     * Whether an extension is a video file's.
     *
     * @param extension e.g. {@code .mkv}
     * @return whether it is one
     */
    public static boolean isVideo(String extension) {
        return extension != null && FILE_EXTENSIONS.containsKey(extension.toLowerCase(Locale.ROOT));
    }

    /**
     * The quality an extension suggests.
     *
     * @param extension e.g. {@code .mkv}
     * @return the quality, Unknown when it says nothing
     */
    public static Quality qualityForExtension(String extension) {
        return extension == null ? Qualities.UNKNOWN
                : FILE_EXTENSIONS.getOrDefault(extension.toLowerCase(Locale.ROOT), Qualities.UNKNOWN);
    }
}
