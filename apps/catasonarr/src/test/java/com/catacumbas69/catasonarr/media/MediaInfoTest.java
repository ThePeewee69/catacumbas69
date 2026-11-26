package com.catacumbas69.catasonarr.media;

import static org.assertj.core.api.Assertions.assertThat;

import com.catacumbas69.catasonarr.model.MediaInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class MediaInfoTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode side(String json) throws Exception {
        return JSON.readTree(json);
    }

    private static MediaInfo info(String video, String videoId, String audio, String audioId, String profile,
                                  double channels, String layout) {
        return new MediaInfo(11, "matroska", video, videoId, null, 0, 8, 0, null, null, "", 1080, 1920, 23.976, audio,
                audioId, profile, 0, channels, layout, 1, List.of("eng"), List.of(), 2700, "Progressive");
    }

    @Test
    void hdr_followsSonarrsRules() throws Exception {
        JsonNode dovi = side("{\"side_data_type\":\"DOVI configuration record\",\"dv_bl_signal_compatibility_id\":1}");
        JsonNode plus = side("{\"side_data_type\":\"HDR Dynamic Metadata SMPTE2094-40 (HDR10+)\"}");
        JsonNode mastering = side("{\"side_data_type\":\"Mastering display metadata\"}");

        assertThat(MediaInfoReader.hdr(8, "bt2020", "smpte2084", List.of(mastering))).isEmpty();
        assertThat(MediaInfoReader.hdr(10, "bt2020", "smpte2084", List.of(dovi))).isEqualTo("DV HDR10");
        assertThat(MediaInfoReader.hdr(10, "bt2020", "smpte2084", List.of(dovi, plus))).isEqualTo("DV HDR10Plus");
        assertThat(MediaInfoReader.hdr(10, "bt2020", "smpte2084", List.of(plus))).isEqualTo("HDR10Plus");
        assertThat(MediaInfoReader.hdr(10, "bt2020", "smpte2084", List.of(mastering))).isEqualTo("HDR10");
        assertThat(MediaInfoReader.hdr(10, "bt2020", "smpte2084", List.of())).isEqualTo("PQ");
        assertThat(MediaInfoReader.hdr(10, "bt2020", "arib-std-b67", List.of())).isEqualTo("HLG");
        assertThat(MediaInfoReader.hdr(10, "bt709", "bt709", List.of())).isEmpty();
    }

    @Test
    void videoCodec_takesTheReleasesSpelling() {
        MediaInfo h264 = info("h264", "", "aac", "", "", 2, "stereo");

        assertThat(MediaInfoFormatter.videoCodec(h264, "Show.S01E01.1080p.WEB-DL.x264-GRP")).isEqualTo("x264");
        assertThat(MediaInfoFormatter.videoCodec(h264, "Show.S01E01.1080p.WEB-DL.AVC-GRP")).isEqualTo("AVC");
        assertThat(MediaInfoFormatter.videoCodec(h264, null)).isEqualTo("h264");
        assertThat(MediaInfoFormatter.videoCodec(info("hevc", "", "aac", "", "", 2, null), null)).isEqualTo("h265");
    }

    @Test
    void audio_codecsAndChannels() {
        assertThat(MediaInfoFormatter.audioCodec(info("h264", "", "eac3", "ec+3", "", 6, "5.1(side)"), null))
                .isEqualTo("EAC3 Atmos");
        assertThat(MediaInfoFormatter.audioCodec(info("h264", "", "dts", "", "DTS-HD MA", 8, "7.1"), null))
                .isEqualTo("DTS-HD MA");
        assertThat(MediaInfoFormatter.audioCodec(info("h264", "", "truehd", "thd+", "", 8, "7.1"), null))
                .isEqualTo("TrueHD Atmos");
        assertThat(MediaInfoFormatter.audioChannels(info("h264", "", "eac3", "", "", 6, "5.1(side)")))
                .isEqualByComparingTo(new BigDecimal("5.1"));
        assertThat(MediaInfoFormatter.audioChannels(info("h264", "", "aac", "", "", 2, "stereo")))
                .isEqualByComparingTo(new BigDecimal("2"));
    }
}
