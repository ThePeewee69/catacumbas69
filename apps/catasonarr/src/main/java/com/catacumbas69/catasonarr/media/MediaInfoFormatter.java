package com.catacumbas69.catasonarr.media;

import com.catacumbas69.catasonarr.model.MediaInfo;
import com.catacumbas69.catasonarr.parser.Parser;
import com.catacumbas69.catasonarr.parser.ParserText;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Codec and channel names as Sonarr writes them in file names, translated from Sonarr 4.0.20's
 * {@code MediaInfoFormatter} (GPL-3.0, orch.md decision 122).
 */
public final class MediaInfoFormatter {

    private static final Pattern POSITION = Pattern.compile("^\\d\\.\\d");

    private MediaInfoFormatter() {
    }

    /**
     * The audio channels, e.g. 5.1, from the layout or the count.
     *
     * @param m the media info
     * @return the channels
     */
    public static BigDecimal audioChannels(MediaInfo m) {
        if (m.audioChannelPositions() != null) {
            Matcher match = POSITION.matcher(m.audioChannelPositions());
            if (match.find()) {
                BigDecimal fromLayout = new BigDecimal(match.group());
                if (fromLayout.signum() != 0) {
                    return fromLayout;
                }
            }
        }
        return BigDecimal.valueOf(m.audioChannels());
    }

    /**
     * The audio codec, e.g. {@code EAC3 Atmos}, {@code DTS-HD MA}.
     *
     * @param m the media info
     * @param sceneName the release's name, or {@code null}
     * @return the codec, or {@code null}
     */
    public static String audioCodec(MediaInfo m, String sceneName) {
        String format = m.audioFormat();
        if (format == null) {
            return null;
        }
        String id = m.audioCodecId() == null ? "" : m.audioCodecId();
        String profile = m.audioProfile() == null ? "" : m.audioProfile();
        if (format.isEmpty()) {
            return "";
        }
        if (id.equals("thd+")) {
            return "TrueHD Atmos";
        }
        switch (format) {
            case "truehd":
                return "TrueHD";
            case "flac":
                return "FLAC";
            case "dts":
                return switch (profile) {
                    case "DTS:X" -> "DTS-X";
                    case "DTS-HD MA" -> "DTS-HD MA";
                    case "DTS-ES" -> "DTS-ES";
                    case "DTS-HD HRA" -> "DTS-HD HRA";
                    case "DTS Express" -> "DTS Express";
                    case "DTS 96/24" -> "DTS 96/24";
                    default -> "DTS";
                };
            default:
                break;
        }
        if (id.equals("ec+3")) {
            return "EAC3 Atmos";
        }
        return switch (format) {
            case "eac3" -> "EAC3";
            case "ac3" -> "AC3";
            case "aac" -> id.equals("A_AAC/MPEG4/LC/SBR") ? "HE-AAC" : "AAC";
            case "mp3" -> "MP3";
            case "mp2" -> "MP2";
            case "opus" -> "Opus";
            case "vorbis" -> "Vorbis";
            case "wmav1", "wmav2", "wmapro" -> "WMA";
            default -> format.startsWith("pcm_") || format.startsWith("adpcm_") ? "PCM" : format;
        };
    }

    /**
     * The video codec, e.g. {@code x265} or {@code h265} as the release's name says.
     *
     * @param m the media info
     * @param sceneName the release's name, or {@code null}
     * @return the codec, or {@code null}
     */
    public static String videoCodec(MediaInfo m, String sceneName) {
        String format = m.videoFormat();
        if (format == null) {
            return null;
        }
        String id = m.videoCodecId() == null ? "" : m.videoCodecId();
        if (format.isEmpty()) {
            return format.strip();
        }
        if (id.equals("x264")) {
            return "x264";
        }
        if (format.equals("h264")) {
            return sceneMatch(sceneName, "AVC", "x264", "h264");
        }
        if (id.equals("x265")) {
            return "x265";
        }
        if (format.equals("hevc")) {
            return sceneMatch(sceneName, "HEVC", "x265", "h265");
        }
        if (format.equals("mpeg4") || format.contains("msmpeg4")) {
            if (id.equals("XVID")) {
                return "XviD";
            }
            return id.equals("DIV3") || id.equals("DX50") || id.toUpperCase(Locale.ROOT).equals("DIVX") ? "DivX" : "";
        }
        return switch (format) {
            case "mpeg2video" -> "MPEG2";
            case "mpeg1video" -> "MPEG";
            case "vc1" -> "VC1";
            case "av1" -> "AV1";
            case "vp7", "vp8", "vp9" -> format.toUpperCase(Locale.ROOT);
            case "wmv1", "wmv2", "wmv3" -> "WMV";
            case "qtrle", "rpza", "rv10", "rv20", "rv30", "rv40", "cinepak", "rawvideo", "msvideo1" -> "";
            default -> format.contains("vp6") ? "VP6" : format.strip();
        };
    }

    /**
     * {@code HDR} when the video is HDR.
     *
     * @param m the media info
     * @return {@code HDR} or {@code ""}
     */
    public static String videoDynamicRange(MediaInfo m) {
        return m.videoHdrFormat() == null || m.videoHdrFormat().isEmpty() ? "" : "HDR";
    }

    /**
     * The kind of HDR, e.g. {@code DV HDR10}.
     *
     * @param m the media info
     * @return the kind, or {@code ""}
     */
    public static String videoDynamicRangeType(MediaInfo m) {
        return m.videoHdrFormat() == null ? "" : m.videoHdrFormat();
    }

    private static String sceneMatch(String sceneName, String... tokens) {
        String name = sceneName == null || sceneName.isBlank() ? "" : Parser.removeFileExtension(sceneName);
        for (String token : tokens) {
            if (ParserText.containsIgnoreCase(name, token)) {
                return token;
            }
        }
        return tokens[tokens.length - 1];
    }
}
