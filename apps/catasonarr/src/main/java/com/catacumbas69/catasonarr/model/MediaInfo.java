package com.catacumbas69.catasonarr.model;

import java.util.List;

/**
 * What ffprobe says of an episode's file (orch.md decision 122), as Sonarr's {@code MediaInfoModel} keeps it and
 * its API shows it: the video, the audio streams and the subtitles.
 *
 * @param schemaRevision the version of how it was read; read again when lower than Catasonarr's
 * @param containerFormat e.g. {@code matroska,webm}
 * @param videoFormat codec, e.g. {@code hevc}
 * @param videoCodecId codec tag, e.g. {@code hev1}
 * @param videoProfile e.g. {@code Main 10}
 * @param videoBitrate bits per second
 * @param videoBitDepth e.g. 10
 * @param videoMultiViewCount 3D views, 0 for 2D
 * @param videoColourPrimaries e.g. {@code bt2020}
 * @param videoTransferCharacteristics e.g. {@code smpte2084} (PQ)
 * @param videoHdrFormat Sonarr's HDR name: {@code HDR10}, {@code HDR10Plus}, {@code DV}, {@code DV HDR10},
 *                       {@code HLG}, or {@code ""}
 * @param height pixels
 * @param width pixels
 * @param videoFps frames per second
 * @param audioFormat first audio stream's codec, e.g. {@code eac3}
 * @param audioCodecId its tag
 * @param audioProfile its profile, e.g. {@code DTS-HD MA}
 * @param audioBitrate its bits per second
 * @param audioChannels its channels, e.g. 6
 * @param audioChannelPositions its layout, e.g. {@code 5.1(side)}
 * @param audioStreamCount audio streams
 * @param audioLanguages each audio stream's language (ISO 639-2), e.g. {@code eng}
 * @param subtitles each subtitle stream's language
 * @param runTimeSeconds the duration
 * @param scanType {@code Progressive} or {@code Interlaced}
 */
@SuppressWarnings("checkstyle:linelength")
public record MediaInfo(int schemaRevision, String containerFormat, String videoFormat, String videoCodecId,
                        String videoProfile, long videoBitrate, int videoBitDepth, int videoMultiViewCount,
                        String videoColourPrimaries, String videoTransferCharacteristics, String videoHdrFormat,
                        int height, int width, double videoFps, String audioFormat, String audioCodecId,
                        String audioProfile, long audioBitrate, double audioChannels, String audioChannelPositions,
                        int audioStreamCount, List<String> audioLanguages, List<String> subtitles,
                        double runTimeSeconds, String scanType) {
}
