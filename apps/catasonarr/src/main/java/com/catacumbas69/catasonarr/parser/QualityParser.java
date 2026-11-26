package com.catacumbas69.catasonarr.parser;

import static com.catacumbas69.catasonarr.parser.ParserText.containsIgnoreCase;

import com.catacumbas69.catasonarr.quality.Qualities;
import com.catacumbas69.catasonarr.quality.Quality;
import com.catacumbas69.catasonarr.quality.QualityDetectionSource;
import com.catacumbas69.catasonarr.quality.QualityModel;
import com.catacumbas69.catasonarr.quality.QualitySource;
import java.util.List;

/**
 * A release's or a file's quality from its name, translated from Sonarr 4.0.20's {@code QualityParser.cs}
 * (GPL-3.0, orch.md decision 122): the source (Blu-ray, WEB-DL, HDTV, …), the resolution, a remux, and PROPER,
 * REPACK, vN and REAL for the revision.
 */
@SuppressWarnings({"checkstyle:linelength", "checkstyle:nestedifdepth"})
public final class QualityParser {

    private static final NetRegex SOURCE_REGEX = NetRegex.compile("\\b(?:(?<bluray>BluRay|Blu-Ray|HD-?DVD|BDMux|BD(?!$))|(?<webdl>WEB[-_. ]DL(?:mux)?|WEBDL|AmazonHD|AmazonSD|iTunesHD|MaxdomeHD|NetflixU?HD|WebHD|HBOMaxHD|DisneyHD|[. ]WEB[. ](?:[xh][ .]?26[45]|AVC|HEVC|DDP?5[. ]1)|[. ](?-i:WEB)$|(?:720|1080|2160)p[-. ]WEB[-. ]|[-. ]WEB[-. ](?:720|1080|2160)p|\\b\\s\\/\\sWEB\\s\\/\\s\\b|(?:AMZN|NF|DP)[. -]WEB[. -](?!Rip))|(?<webrip>WebRip|Web-Rip|WEBMux)|(?<hdtv>HDTV)|(?<bdrip>BDRip|BDLight)|(?<brrip>BRRip)|(?<dvd>DVD|DVDRip|NTSC|PAL|xvidvd)|(?<dsr>WS[-_. ]DSR|DSR)|(?<pdtv>PDTV)|(?<sdtv>SDTV)|(?<tvrip>TVRip))(?:\\b|$|[ .])", NetRegex.IGNORE_CASE | NetRegex.IGNORE_PATTERN_WHITESPACE);
    private static final NetRegex RAW_HD_REGEX = NetRegex.compile("\\b(?<rawhd>RawHD|Raw[-_. ]HD)\\b", NetRegex.IGNORE_CASE);
    private static final NetRegex MPEG2_REGEX = NetRegex.compile("\\b(?<mpeg2>MPEG[-_. ]?2)\\b");
    private static final NetRegex PROPER_REGEX = NetRegex.compile("\\b(?<proper>proper)\\b", NetRegex.IGNORE_CASE);
    private static final NetRegex REPACK_REGEX = NetRegex.compile("\\b(?<repack>repack\\d?|rerip\\d?)\\b", NetRegex.IGNORE_CASE);
    private static final NetRegex VERSION_REGEX = NetRegex.compile("\\d[-._ ]?v(?<version>\\d)[-._ ]|\\[v(?<version>\\d)\\]|repack(?<version>\\d)|rerip(?<version>\\d)|(?:480|576|720|1080|2160)p[._ ]v(?<version>\\d)", NetRegex.IGNORE_CASE);
    private static final NetRegex REAL_REGEX = NetRegex.compile("\\b(?<real>REAL)\\b", 0);
    private static final NetRegex RESOLUTION_REGEX = NetRegex.compile("\\b(?:(?<R360p>360p)|(?<R480p>480p|480i|640x480|848x480)|(?<R540p>540p)|(?<R576p>576p)|(?<R720p>720p|1280x720|960p)|(?<R1080p>1080p|1920x1080|1440p|FHD|1080i|4kto1080p)|(?<R2160p>2160p|3840x2160|4k[-_. ](?:UHD|HEVC|BD|H265)|(?:UHD|HEVC|BD|H265)[-_. ]4k))\\b", NetRegex.IGNORE_CASE);
    // Handle cases where no resolution is in the release name (assume if UHD then 4k) or resolution is non-standard
    private static final NetRegex ALTERNATIVE_RESOLUTION_REGEX = NetRegex.compile("\\b(?<R2160p>UHD)\\b|(?<R2160p>\\[4K\\])", NetRegex.IGNORE_CASE);
    private static final NetRegex CODEC_REGEX = NetRegex.compile("\\b(?:(?<x264>x264)|(?<h264>h264)|(?<xvidhd>XvidHD)|(?<xvid>Xvid)|(?<divx>divx))\\b", NetRegex.IGNORE_CASE);
    private static final NetRegex OTHER_SOURCE_REGEX = NetRegex.compile("(?<hdtv>HD[-_. ]TV)|(?<sdtv>SD[-_. ]TV)", NetRegex.IGNORE_CASE);
    private static final NetRegex ANIME_BLURAY_REGEX = NetRegex.compile("bd(?:720|1080|2160)|(?<=[-_. (\\[])bd(?=[-_. )\\]])", NetRegex.IGNORE_CASE);
    private static final NetRegex ANIME_WEB_DL_REGEX = NetRegex.compile("\\[WEB\\]|[\\[\\(]WEB[ .]", NetRegex.IGNORE_CASE);
    private static final NetRegex HIGH_DEF_PDTV_REGEX = NetRegex.compile("hr[-_. ]ws", NetRegex.IGNORE_CASE);
    private static final NetRegex REMUX_REGEX = NetRegex.compile("(?:[_. ]|\\d{4}p-|\\bHybrid-)(?<remux>(?:(BD|UHD)[-_. ]?)?Remux)\\b|(?<remux>(?:(BD|UHD)[-_. ]?)?Remux[_. ]\\d{4}p)", NetRegex.IGNORE_CASE);

    /** The resolutions Sonarr reads. */
    private enum Resolution {
        R360P, R480P, R540P, R576P, R720P, R1080P, R2160P, UNKNOWN
    }

    private QualityParser() {
    }

    /**
     * A name's quality; when the name says nothing, the extension's.
     *
     * @param name a release title or a file name
     * @return the quality
     */
    public static QualityModel parseQuality(String name) {
        if (name == null || name.isBlank()) {
            return new QualityModel();
        }
        name = name.strip();
        QualityModel result = parseQualityName(name);
        if (result.quality.equals(Qualities.UNKNOWN) && !containsInvalidPathChars(name)) {
            result.quality = MediaFileExtensions.qualityForExtension(ParserText.extension(name));
            result.sourceDetectionSource = QualityDetectionSource.EXTENSION;
            result.resolutionDetectionSource = QualityDetectionSource.EXTENSION;
        }
        return result;
    }

    /**
     * A name's quality from the name alone.
     *
     * @param name a release title or a file name
     * @return the quality, Unknown when the name says nothing
     */
    public static QualityModel parseQualityName(String name) {
        String normalizedName = name.replace('_', ' ').strip();
        QualityModel result = parseQualityModifiers(name, normalizedName);

        if (RAW_HD_REGEX.isMatch(normalizedName)) {
            result.sourceDetectionSource = QualityDetectionSource.NAME;
            result.resolutionDetectionSource = QualityDetectionSource.NAME;
            result.quality = Qualities.RAWHD;
            return result;
        }

        List<NetMatch> sourceMatches = SOURCE_REGEX.matches(normalizedName);
        NetMatch sourceMatch = sourceMatches.isEmpty() ? null : sourceMatches.get(sourceMatches.size() - 1);
        Resolution resolution = parseResolution(normalizedName);
        NetMatch codecRegex = CODEC_REGEX.match(normalizedName);
        boolean remuxMatch = REMUX_REGEX.isMatch(normalizedName);

        if (resolution != Resolution.UNKNOWN) {
            result.resolutionDetectionSource = QualityDetectionSource.NAME;
        }

        if (sourceMatch != null && sourceMatch.success()) {
            result.sourceDetectionSource = QualityDetectionSource.NAME;
            Quality bySource = bySource(name, normalizedName, sourceMatch, resolution, codecRegex, remuxMatch, result);
            if (bySource != null) {
                result.quality = bySource;
                return result;
            }
        }

        if (sourceMatch == null && remuxMatch && resolution != Resolution.UNKNOWN) {
            result.sourceDetectionSource = QualityDetectionSource.UNKNOWN;
            switch (resolution) {
                case R480P:
                    result.quality = Qualities.BLURAY_480P;
                    return result;
                case R720P:
                    result.quality = Qualities.BLURAY_720P;
                    return result;
                case R2160P:
                    result.quality = Qualities.BLURAY_2160P_REMUX;
                    return result;
                case R1080P:
                    result.quality = Qualities.BLURAY_1080P_REMUX;
                    return result;
                default:
                    break;
            }
        }

        if (ANIME_BLURAY_REGEX.match(normalizedName).success()) {
            result.sourceDetectionSource = QualityDetectionSource.NAME;
            if (isSd(resolution) || containsIgnoreCase(normalizedName, "480p")) {
                result.resolutionDetectionSource = QualityDetectionSource.NAME;
                result.quality = Qualities.DVD;
                return result;
            }
            if (resolution == Resolution.R1080P || containsIgnoreCase(normalizedName, "1080p")) {
                result.resolutionDetectionSource = QualityDetectionSource.NAME;
                result.quality = remuxMatch ? Qualities.BLURAY_1080P_REMUX : Qualities.BLURAY_1080P;
                return result;
            }
            if (resolution == Resolution.R2160P || containsIgnoreCase(normalizedName, "2160p")) {
                result.resolutionDetectionSource = QualityDetectionSource.NAME;
                result.quality = remuxMatch ? Qualities.BLURAY_2160P_REMUX : Qualities.BLURAY_2160P;
                return result;
            }
            result.quality = remuxMatch && resolution != Resolution.R720P ? Qualities.BLURAY_1080P_REMUX
                    : Qualities.BLURAY_720P;
            return result;
        }

        if (ANIME_WEB_DL_REGEX.match(normalizedName).success()) {
            result.sourceDetectionSource = QualityDetectionSource.NAME;
            if (isSd(resolution) || containsIgnoreCase(normalizedName, "480p")) {
                result.resolutionDetectionSource = QualityDetectionSource.NAME;
                result.quality = Qualities.WEBDL_480P;
                return result;
            }
            if (resolution == Resolution.R1080P || containsIgnoreCase(normalizedName, "1080p")) {
                result.resolutionDetectionSource = QualityDetectionSource.NAME;
                result.quality = Qualities.WEBDL_1080P;
                return result;
            }
            if (resolution == Resolution.R2160P || containsIgnoreCase(normalizedName, "2160p")) {
                result.resolutionDetectionSource = QualityDetectionSource.NAME;
                result.quality = Qualities.WEBDL_2160P;
                return result;
            }
            result.quality = Qualities.WEBDL_720P;
            return result;
        }

        if (resolution != Resolution.UNKNOWN) {
            QualitySource source = QualitySource.UNKNOWN;
            if (remuxMatch) {
                result.sourceDetectionSource = QualityDetectionSource.NAME;
                source = QualitySource.BLURAY_RAW;
            } else {
                Quality quality = MediaFileExtensions.qualityForExtension(ParserText.extension(name));
                if (!quality.equals(Qualities.UNKNOWN)) {
                    result.sourceDetectionSource = QualityDetectionSource.EXTENSION;
                    source = QualitySource.of(quality);
                }
            }
            result.resolutionDetectionSource = QualityDetectionSource.NAME;
            boolean unknown = source == QualitySource.UNKNOWN;
            switch (resolution) {
                case R2160P:
                    result.quality = unknown ? Qualities.HDTV_2160P : Qualities.findBySourceAndResolution(source, 2160);
                    return result;
                case R1080P:
                    result.quality = unknown ? Qualities.HDTV_1080P : Qualities.findBySourceAndResolution(source, 1080);
                    return result;
                case R720P:
                    result.quality = unknown ? Qualities.HDTV_720P : Qualities.findBySourceAndResolution(source, 720);
                    return result;
                default:
                    result.quality = unknown ? Qualities.SDTV : Qualities.findBySourceAndResolution(source, 480);
                    return result;
            }
        }

        if (codecRegex.group("x264").success()) {
            result.quality = Qualities.SDTV;
            return result;
        }

        if (normalizedName.contains("848x480")) {
            result.resolutionDetectionSource = QualityDetectionSource.NAME;
            if (normalizedName.contains("dvd")) {
                result.sourceDetectionSource = QualityDetectionSource.NAME;
                result.quality = Qualities.DVD;
            } else if (containsIgnoreCase(normalizedName, "bluray")) {
                result.sourceDetectionSource = QualityDetectionSource.NAME;
                result.quality = Qualities.BLURAY_480P;
            } else {
                result.quality = Qualities.SDTV;
            }
            return result;
        }

        if (containsIgnoreCase(normalizedName, "1280x720")) {
            result.resolutionDetectionSource = QualityDetectionSource.NAME;
            boolean bluray = containsIgnoreCase(normalizedName, "bluray");
            if (bluray) {
                result.sourceDetectionSource = QualityDetectionSource.NAME;
            }
            result.quality = bluray ? Qualities.BLURAY_720P : Qualities.HDTV_720P;
            return result;
        }

        if (containsIgnoreCase(normalizedName, "1920x1080")) {
            result.resolutionDetectionSource = QualityDetectionSource.NAME;
            boolean bluray = containsIgnoreCase(normalizedName, "bluray");
            if (bluray) {
                result.sourceDetectionSource = QualityDetectionSource.NAME;
            }
            result.quality = bluray ? Qualities.BLURAY_1080P : Qualities.HDTV_1080P;
            return result;
        }

        for (Object[] named : new Object[][] {{"bluray720p", Qualities.BLURAY_720P},
                {"bluray1080p", Qualities.BLURAY_1080P}, {"bluray2160p", Qualities.BLURAY_2160P}}) {
            if (containsIgnoreCase(normalizedName, (String) named[0])) {
                result.sourceDetectionSource = QualityDetectionSource.NAME;
                result.resolutionDetectionSource = QualityDetectionSource.NAME;
                result.quality = (Quality) named[1];
                return result;
            }
        }

        Quality otherSourceMatch = otherSourceMatch(normalizedName);
        if (!otherSourceMatch.equals(Qualities.UNKNOWN)) {
            result.sourceDetectionSource = QualityDetectionSource.NAME;
            result.quality = otherSourceMatch;
        }
        return result;
    }

    /** The quality when the source regex matched; {@code null} to go on with the other rules. */
    private static Quality bySource(String name, String normalizedName, NetMatch sourceMatch, Resolution resolution,
                                    NetMatch codecRegex, boolean remuxMatch, QualityModel result) {
        if (sourceMatch.group("bluray").success()) {
            if (codecRegex.group("xvid").success() || codecRegex.group("divx").success()) {
                return Qualities.BLURAY_480P;
            }
            if (resolution == Resolution.R2160P) {
                return remuxMatch ? Qualities.BLURAY_2160P_REMUX : Qualities.BLURAY_2160P;
            }
            if (resolution == Resolution.R1080P) {
                return remuxMatch ? Qualities.BLURAY_1080P_REMUX : Qualities.BLURAY_1080P;
            }
            if (resolution == Resolution.R576P) {
                return Qualities.BLURAY_576P;
            }
            if (resolution == Resolution.R360P || resolution == Resolution.R480P || resolution == Resolution.R540P) {
                return Qualities.BLURAY_480P;
            }
            if (remuxMatch && resolution != Resolution.R720P) {
                return Qualities.BLURAY_1080P_REMUX;
            }
            return Qualities.BLURAY_720P;
        }
        if (sourceMatch.group("webdl").success()) {
            if (resolution == Resolution.R2160P) {
                return Qualities.WEBDL_2160P;
            }
            if (resolution == Resolution.R1080P) {
                return Qualities.WEBDL_1080P;
            }
            if (resolution == Resolution.R720P || name.contains("[WEBDL]")) {
                return Qualities.WEBDL_720P;
            }
            return Qualities.WEBDL_480P;
        }
        if (sourceMatch.group("webrip").success()) {
            if (resolution == Resolution.R2160P) {
                return Qualities.WEBRIP_2160P;
            }
            if (resolution == Resolution.R1080P) {
                return Qualities.WEBRIP_1080P;
            }
            if (resolution == Resolution.R720P) {
                return Qualities.WEBRIP_720P;
            }
            return Qualities.WEBRIP_480P;
        }
        if (sourceMatch.group("hdtv").success()) {
            if (MPEG2_REGEX.isMatch(normalizedName)) {
                return Qualities.RAWHD;
            }
            if (resolution == Resolution.R2160P) {
                return Qualities.HDTV_2160P;
            }
            if (resolution == Resolution.R1080P) {
                return Qualities.HDTV_1080P;
            }
            if (resolution == Resolution.R720P || name.contains("[HDTV]")) {
                return Qualities.HDTV_720P;
            }
            return Qualities.SDTV;
        }
        if (sourceMatch.group("bdrip").success() || sourceMatch.group("brrip").success()) {
            switch (resolution) {
                case R720P:
                    return Qualities.BLURAY_720P;
                case R1080P:
                    return Qualities.BLURAY_1080P;
                case R2160P:
                    return Qualities.BLURAY_2160P;
                default:
                    return Qualities.BLURAY_480P;
            }
        }
        if (sourceMatch.group("dvd").success()) {
            return Qualities.DVD;
        }
        if (sourceMatch.group("pdtv").success() || sourceMatch.group("sdtv").success()
                || sourceMatch.group("dsr").success() || sourceMatch.group("tvrip").success()) {
            if (resolution == Resolution.R1080P || containsIgnoreCase(normalizedName, "1080p")) {
                return Qualities.HDTV_1080P;
            }
            if (resolution == Resolution.R720P || containsIgnoreCase(normalizedName, "720p")) {
                return Qualities.HDTV_720P;
            }
            if (HIGH_DEF_PDTV_REGEX.isMatch(normalizedName)) {
                result.resolutionDetectionSource = QualityDetectionSource.NAME;
                return Qualities.HDTV_720P;
            }
            return Qualities.SDTV;
        }
        return null;
    }

    private static boolean isSd(Resolution r) {
        return r == Resolution.R360P || r == Resolution.R480P || r == Resolution.R540P || r == Resolution.R576P;
    }

    private static Resolution parseResolution(String name) {
        NetMatch match = RESOLUTION_REGEX.match(name);
        NetMatch alternativeMatch = ALTERNATIVE_RESOLUTION_REGEX.match(name);
        if (!match.success() && !alternativeMatch.success()) {
            return Resolution.UNKNOWN;
        }
        String[] groups = {"R360p", "R480p", "R540p", "R576p", "R720p", "R1080p", "R2160p"};
        for (int i = 0; i < groups.length; i++) {
            if (match.group(groups[i]).success()) {
                return Resolution.values()[i];
            }
        }
        return alternativeMatch.group("R2160p").success() ? Resolution.R2160P : Resolution.UNKNOWN;
    }

    private static Quality otherSourceMatch(String name) {
        NetMatch match = OTHER_SOURCE_REGEX.match(name);
        if (!match.success()) {
            return Qualities.UNKNOWN;
        }
        if (match.group("sdtv").success()) {
            return Qualities.SDTV;
        }
        return match.group("hdtv").success() ? Qualities.HDTV_720P : Qualities.UNKNOWN;
    }

    private static QualityModel parseQualityModifiers(String name, String normalizedName) {
        QualityModel result = new QualityModel();
        NetMatch versionRegexResult = VERSION_REGEX.match(normalizedName);
        if (versionRegexResult.success()) {
            result.revision.version = ParserText.toInt(versionRegexResult.group("version").value());
            result.revisionDetectionSource = QualityDetectionSource.NAME;
        }
        if (PROPER_REGEX.isMatch(normalizedName)) {
            result.revision.version = versionRegexResult.success()
                    ? ParserText.toInt(versionRegexResult.group("version").value()) + 1 : 2;
            result.revisionDetectionSource = QualityDetectionSource.NAME;
        }
        if (REPACK_REGEX.isMatch(normalizedName)) {
            result.revision.version = versionRegexResult.success()
                    ? ParserText.toInt(versionRegexResult.group("version").value()) + 1 : 2;
            result.revision.isRepack = true;
            result.revisionDetectionSource = QualityDetectionSource.NAME;
        }
        int real = REAL_REGEX.matches(name).size();
        if (real > 0) {
            result.revision.real = real;
            result.revisionDetectionSource = QualityDetectionSource.NAME;
        }
        return result;
    }

    /** Characters a path cannot hold, as .NET's {@code Path.GetInvalidPathChars} on Linux: only NUL. */
    private static boolean containsInvalidPathChars(String name) {
        return name.indexOf('\0') >= 0;
    }
}
