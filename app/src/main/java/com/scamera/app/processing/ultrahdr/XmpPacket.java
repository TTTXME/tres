package com.scamera.app.processing.ultrahdr;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * The two XMP packets of an Ultra HDR JPEG: the primary image's (the GContainer directory naming the gain-map image and its
 * length) and the gain map's (the hdrgm metadata of ISO 21496-1 / Adobe's gain map). Written as fixed text: one attribute of
 * the description per line, two spaces per level - the layout the app has always written.
 */
final class XmpPacket {
    private static final String RDF_NS = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
    private static final String CONTAINER_NS = "http://ns.google.com/photos/1.0/container/";
    private static final String HDRGM_NS = "http://ns.adobe.com/hdr-gain-map/1.0/";

    private final StringBuilder text = new StringBuilder(1024);

    private XmpPacket() {
        text.append("<?xpacket begin=\"﻿\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n")
                .append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"SCAMERA\">\n")
                .append(" <rdf:RDF xmlns:rdf=\"").append(RDF_NS).append("\">\n")
                .append("  <rdf:Description rdf:about=\"\"");
    }

    /** An attribute of the description, on a line of its own. */
    private XmpPacket attribute(String name, Object value) {
        text.append("\n    ").append(name).append("=\"").append(value).append('"');
        return this;
    }

    /** The description closed - empty ({@code body} null) or around {@code body} - and the packet's end, as UTF-8. */
    private byte[] finish(String body) {
        if (body == null) text.append("/>\n");
        else text.append(">\n").append(body).append("  </rdf:Description>\n");
        text.append(" </rdf:RDF>\n").append("</x:xmpmeta>\n").append("<?xpacket end=\"w\"?>\n");
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String indent(int depth) {
        return "      ".substring(0, depth);
    }

    /** The primary image's packet: hdrgm version 1.0 and the container directory (the primary, then the gain map of {@code gainMapLength} bytes). */
    static byte[] primary(int gainMapLength) {
        final StringBuilder directory = new StringBuilder(512);
        directory.append(indent(3)).append("<Container:Directory>\n").append(indent(4)).append("<rdf:Seq>\n");
        final String[] items = {" Item:Semantic=\"Primary\" Item:Mime=\"image/jpeg\"",
                " Item:Semantic=\"GainMap\" Item:Mime=\"image/jpeg\" Item:Length=\"" + gainMapLength + "\""};
        for (String item : items) {
            directory.append(indent(5)).append("<rdf:li rdf:parseType=\"Resource\">\n")
                    .append(indent(6)).append("<Container:Item").append(item).append("/>\n")
                    .append(indent(5)).append("</rdf:li>\n");
        }
        directory.append(indent(4)).append("</rdf:Seq>\n").append(indent(3)).append("</Container:Directory>\n");
        return new XmpPacket()
                .attribute("xmlns:Container", CONTAINER_NS)
                .attribute("xmlns:Item", CONTAINER_NS + "item/")
                .attribute("xmlns:hdrgm", HDRGM_NS)
                .attribute("hdrgm:Version", "1.0")
                .finish(directory.toString());
    }

    /** The gain map's packet: its log2 range, gamma 1, the decode offsets, the HDR capacity, an SDR base. */
    static byte[] gainMap(float min, float max, float capacityMax, float offset) {
        return new XmpPacket()
                .attribute("xmlns:hdrgm", HDRGM_NS)
                .attribute("hdrgm:Version", "1.0")
                .attribute("hdrgm:GainMapMin", decimal(min))
                .attribute("hdrgm:GainMapMax", decimal(max))
                .attribute("hdrgm:Gamma", "1")
                .attribute("hdrgm:OffsetSDR", decimal(offset))
                .attribute("hdrgm:OffsetHDR", decimal(offset))
                .attribute("hdrgm:HDRCapacityMin", "0")
                .attribute("hdrgm:HDRCapacityMax", decimal(capacityMax))
                .attribute("hdrgm:BaseRenditionIsHDR", "False")
                .finish(null);
    }

    /** Six fixed decimals (%g's scientific notation, "1.2345e-05", is refused by some XMP readers); not finite: 0. */
    static String decimal(float v) {
        return String.format(Locale.US, "%.6f", Float.isFinite(v) ? v : 0f);
    }
}
