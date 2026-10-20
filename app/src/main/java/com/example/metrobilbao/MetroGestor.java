package com.example.metrobilbao;

import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.List;

public class MetroGestor {

    public static class Station {
        public final String name;
        public final String code;
        public final String parentId;
        public final double latitude;
        public final double longitude;
        public final int accessCount;

        public Station(String name, String code, String parentId,
                       double latitude, double longitude, int accessCount) {
            this.name = name;
            this.code = code;
            this.parentId = parentId;
            this.latitude = latitude;
            this.longitude = longitude;
            this.accessCount = accessCount;
        }
    }

    public List<Station> getStations(MetroRepository.GtfsSource source) throws Exception {
        List<Station> out = new ArrayList<>();
        try (BufferedReader r = source.open("stops.txt", "paradas.txt")) {
            String header = r.readLine();
            if (header == null) return out;
            String[] cols = header.split(",", -1);
            int idxId = MetroRepository.colIdx(cols, "stop_id");
            int idxCode = MetroRepository.colIdx(cols, "stop_code");
            int idxName = MetroRepository.colIdx(cols, "stop_name");
            int idxLat = MetroRepository.colIdx(cols, "stop_lat");
            int idxLon = MetroRepository.colIdx(cols, "stop_lon");
            int idxLoc = MetroRepository.colIdx(cols, "location_type");
            if (idxId < 0) { idxId = 0; idxCode = 1; idxName = 2; idxLat = 3; idxLon = 4; idxLoc = 6; }

            String l;
            while ((l = r.readLine()) != null) {
                String[] c = l.split(",", -1);
                if (c.length <= Math.max(Math.max(idxId, idxName), Math.max(idxLat, Math.max(idxLon, idxLoc))))
                    continue;
                try {
                    int locType = idxLoc >= 0 ? Integer.parseInt(c[idxLoc].trim()) : 0;
                    if (locType != 1) continue;  // solo estaciones

                    String stopId = c[idxId].trim();
                    String code = idxCode >= 0 ? c[idxCode].trim() : "";
                    String name = c[idxName].trim();
                    double lat = Double.parseDouble(c[idxLat].trim());
                    double lon = Double.parseDouble(c[idxLon].trim());
                    out.add(new Station(name, code, stopId, lat, lon, 1));
                } catch (NumberFormatException ignored) {}
            }
        }
        return out;
    }
}