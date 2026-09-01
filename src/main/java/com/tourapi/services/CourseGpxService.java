package com.tourapi.services;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;

/** 편집 코스의 경로와 경유지를 Garmin Connect가 읽는 표준 GPX 1.1 Track으로 만든다. */
@ApplicationScoped
public class CourseGpxService {

    public String create(JsonNode course) {
        if (course == null || !course.path("polyline").isArray()
                || validTrackPointCount(course.path("polyline")) < 2) {
            throw new IllegalArgumentException("GPX로 내보낼 경로 없음");
        }
        String name = course.path("n").asText("러닝 코스").trim();
        if (name.isEmpty()) {
            name = "러닝 코스";
        }

        StringBuilder xml = new StringBuilder(32_768);
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<gpx version=\"1.1\" creator=\"Tour API\" ")
                .append("xmlns=\"http://www.topografix.com/GPX/1/1\" ")
                .append("xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" ")
                .append("xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 ")
                .append("http://www.topografix.com/GPX/1/1/gpx.xsd\">\n")
                .append("  <metadata>\n")
                .append("    <name>").append(escape(name)).append("</name>\n")
                .append("    <desc>한국관광공사 관광정보와 TMAP 보행 경로를 바탕으로 만든 러닝 코스</desc>\n")
                .append("  </metadata>\n");

        for (JsonNode poi : course.path("poi")) {
            if (!validCoordinate(poi.path("lat"), poi.path("lng"))) {
                continue;
            }
            String waypointName = poi.path("n").asText("").trim();
            xml.append("  <wpt lat=\"").append(number(poi.path("lat")))
                    .append("\" lon=\"").append(number(poi.path("lng"))).append("\">\n");
            if (!waypointName.isEmpty()) {
                xml.append("    <name>").append(escape(waypointName)).append("</name>\n");
            }
            xml.append("    <type>Waypoint</type>\n")
                    .append("  </wpt>\n");
        }

        xml.append("  <trk>\n")
                .append("    <name>").append(escape(name)).append("</name>\n")
                .append("    <type>running</type>\n")
                .append("    <trkseg>\n");
        for (JsonNode point : course.path("polyline")) {
            if (!point.isArray() || point.size() < 2
                    || !validCoordinate(point.get(0), point.get(1))) {
                continue;
            }
            xml.append("      <trkpt lat=\"").append(number(point.get(0)))
                    .append("\" lon=\"").append(number(point.get(1))).append("\"/>\n");
        }
        xml.append("    </trkseg>\n")
                .append("  </trk>\n")
                .append("</gpx>\n");
        return xml.toString();
    }

    private static boolean validCoordinate(JsonNode latNode, JsonNode lngNode) {
        if (latNode == null || lngNode == null || !latNode.isNumber() || !lngNode.isNumber()) {
            return false;
        }
        double lat = latNode.asDouble();
        double lng = lngNode.asDouble();
        return Double.isFinite(lat) && Double.isFinite(lng)
                && lat >= -90 && lat <= 90 && lng >= -180 && lng <= 180;
    }

    private static int validTrackPointCount(JsonNode polyline) {
        int count = 0;
        for (JsonNode point : polyline) {
            if (point.isArray() && point.size() >= 2
                    && validCoordinate(point.get(0), point.get(1))) {
                count++;
            }
        }
        return count;
    }

    private static String number(JsonNode node) {
        return node.decimalValue().stripTrailingZeros().toPlainString();
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
