package com.scamera.app.gallery.video;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/** P499: the video player's pure parts - resume, subtitles, decoder order, picture size, labels. */
public class VideoPlayerLogicTest {
    @Test
    public void resumeSkipsBothEnds() {
        assertEquals(0, VideoResume.resumeAtMs(2000, 60_000));
        assertEquals(15_000, VideoResume.resumeAtMs(15_000, 60_000));
        assertEquals(0, VideoResume.resumeAtMs(58_000, 60_000)); // watched to the end: start over
        assertEquals(15_000, VideoResume.resumeAtMs(15_000, 0)); // the length unknown
        assertArrayEquals(new long[]{1, 2, 3}, VideoResume.decode("1/2/3"));
        assertNull(VideoResume.decode("x/2/3"));
        assertNull(VideoResume.decode("1/2"));
    }

    @Test
    public void srtAndVtt() {
        String srt = "﻿1\r\n00:00:01,500 --> 00:00:03,000\r\n<i>Привет</i>\r\nмир\r\n\r\n2\r\n00:00:04,000 --> 00:00:05,250\r\n{\\an8}Второй\r\n";
        List<Subtitles.Cue> cues = Subtitles.parse(srt);
        assertEquals(2, cues.size());
        assertEquals(1500, cues.get(0).startMs);
        assertEquals("Привет\nмир", cues.get(0).text);
        assertEquals("Второй", cues.get(1).text);
        assertEquals("Привет\nмир", Subtitles.at(cues, 2000));
        assertNull(Subtitles.at(cues, 3500));
        assertEquals("Второй", Subtitles.at(cues, 5000));
        assertNull(Subtitles.at(cues, 5250));
        List<Subtitles.Cue> vtt = Subtitles.parse("WEBVTT\n\n01:02.000 --> 01:04.500 align:start\nHello\n");
        assertEquals(1, vtt.size());
        assertEquals(62_000, vtt.get(0).startMs);
        assertEquals(64_500, vtt.get(0).endMs);
        assertEquals(3_723_045, Subtitles.time("1:02:03,045"));
        assertEquals(-1, Subtitles.time("abc"));
    }

    @Test
    public void overlappingCuesJoin() {
        List<Subtitles.Cue> cues = Subtitles.parse("00:00:01,000 --> 00:00:05,000\nA\n\n00:00:02,000 --> 00:00:03,000\nB\n");
        assertEquals("A\nB", Subtitles.at(cues, 2500));
    }

    @Test
    public void inFileSamples() {
        byte[] tx3g = {0, 2, 'h', 'i'};
        assertEquals("hi", Subtitles.sampleText("text/3gpp-tt", tx3g, 4));
        assertEquals("", Subtitles.sampleText("text/3gpp-tt", new byte[]{0, 0}, 2));
        assertEquals("plain", Subtitles.sampleText("application/x-subrip", "plain".getBytes(StandardCharsets.UTF_8), 5));
        // an MP4 WebVTT sample: vttc { payl "Hey" }
        byte[] vtt = {0, 0, 0, 19, 'v', 't', 't', 'c', 0, 0, 0, 11, 'p', 'a', 'y', 'l', 'H', 'e', 'y'};
        assertEquals("Hey", Subtitles.sampleText("text/vtt", vtt, vtt.length));
        List<Subtitles.Cue> cues = Subtitles.fromSamples(Arrays.asList(0L, 1000L, 2500L, 4000L), Arrays.asList("a", "", "b", "c"));
        assertEquals(3, cues.size());
        assertEquals(1000, cues.get(0).endMs); // the empty sample clears it
        assertEquals(4000, cues.get(1).endMs);
        assertEquals(9000, cues.get(2).endMs); // the last one: 5 s
    }

    @Test
    public void decoderOrder() {
        assertTrue(VideoDecoders.softwareName("c2.android.avc.decoder"));
        assertTrue(VideoDecoders.softwareName("OMX.google.h264.decoder"));
        assertFalse(VideoDecoders.softwareName("c2.qti.avc.decoder"));
        List<VideoDecoders.Candidate> all = Arrays.asList(new VideoDecoders.Candidate("sw", false), new VideoDecoders.Candidate("hw", true));
        assertEquals("[hw]", names(VideoDecoders.order(all, VideoEngine.Decoder.HW, false)));
        assertEquals("[hw, sw]", names(VideoDecoders.order(all, VideoEngine.Decoder.HW_PLUS, false)));
        assertEquals("[sw]", names(VideoDecoders.order(all, VideoEngine.Decoder.HW_PLUS, true)));
        assertEquals("[sw]", names(VideoDecoders.order(all, VideoEngine.Decoder.SW, false)));
    }

    private static String names(List<VideoDecoders.Candidate> l) {
        StringBuilder s = new StringBuilder("[");
        for (VideoDecoders.Candidate c : l) s.append(s.length() > 1 ? ", " : "").append(c.name);
        return s.append(']').toString();
    }

    @Test
    public void pictureSize() {
        // a 1920x1080 video in a 1000x1000 box
        assertArrayEquals(new int[]{1000, 563}, VideoLayout.size(VideoPlayerActivity.Fit.FIT, 1920, 1080, 1000, 1000, 1f));
        assertArrayEquals(new int[]{1778, 1000}, VideoLayout.size(VideoPlayerActivity.Fit.CROP, 1920, 1080, 1000, 1000, 1f));
        assertArrayEquals(new int[]{1000, 1000}, VideoLayout.size(VideoPlayerActivity.Fit.STRETCH, 1920, 1080, 1000, 1000, 1f));
        assertArrayEquals(new int[]{1920, 1080}, VideoLayout.size(VideoPlayerActivity.Fit.ORIGINAL, 1920, 1080, 1000, 1000, 1f));
        assertArrayEquals(new int[]{1000, 750}, VideoLayout.size(VideoPlayerActivity.Fit.R4_3, 1920, 1080, 1000, 1000, 1f));
        assertArrayEquals(new int[]{2000, 1125}, VideoLayout.size(VideoPlayerActivity.Fit.FIT, 1920, 1080, 1000, 1000, 2f));
        // a portrait video keeps 9:16 in the 16:9 mode
        assertArrayEquals(new int[]{563, 1000}, VideoLayout.size(VideoPlayerActivity.Fit.R16_9, 1080, 1920, 1000, 1000, 1f));
    }

    @Test
    public void labels() {
        assertEquals("1x", VideoPlayerActivity.speedLabel(1f));
        assertEquals("1.25x", VideoPlayerActivity.speedLabel(1.25f));
        assertEquals("0.5x", VideoPlayerActivity.speedLabel(.5f));
        assertEquals("H.264", VideoEngine.codecLabel("video/avc"));
        assertEquals("HEVC", VideoEngine.codecLabel("video/hevc"));
        assertEquals("XYZ", VideoEngine.codecLabel("video/xyz"));
    }

    @Test
    public void subtitleCharset() {
        byte[] cp1251 = {(byte) 0xCF, (byte) 0xF0, (byte) 0xE8}; // «При» in Windows-1251, not valid UTF-8
        assertEquals("При", Subtitles.decode(cp1251));
        assertEquals("При", Subtitles.decode("При".getBytes(StandardCharsets.UTF_8)));
    }
}
