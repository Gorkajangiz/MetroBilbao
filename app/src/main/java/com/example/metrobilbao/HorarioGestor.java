package com.example.metrobilbao;

import android.graphics.Color;

import com.google.android.gms.maps.model.LatLng;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

public class HorarioGestor {

    private static final String LOG_TAG = "MetroTren";
    private static final TimeZone TZ_MADRID = TimeZone.getTimeZone("Europe/Madrid");

    public static final int COLOR_L1 = Color.rgb(255, 101, 5);
    public static final int COLOR_L2 = Color.rgb(0, 170, 100);

    private static final double HINT_SEARCH_METERS = 2000.0;
    private static final double MAX_REVERSE_ARTIFACT_M = 500.0;

    public static class StopTime {
        public final int stopSequence;
        public final int arrivalSec;
        public final int departureSec;
        public final String stopId;

        public StopTime(int stopSequence, int arrivalSec, int departureSec, String stopId) {
            this.stopSequence = stopSequence;
            this.arrivalSec = arrivalSec;
            this.departureSec = departureSec;
            this.stopId = stopId;
        }
    }

    public static class Trip {
        public final String tripId;
        public final String routeId;         // "L1" o "L2"
        public final String serviceId;
        public final String headsign;
        public final int directionId;
        public final String shapeId;
        public final ShapePath path;
        public final List<StopTime> stops;
        public final double[] stopOffsets;
        public final int startSec;
        public final int endSec;

        public Trip(String tripId, String routeId, String serviceId, String headsign,
                    int directionId, String shapeId, ShapePath path,
                    List<StopTime> stops, double[] stopOffsets) {
            this.tripId = tripId;
            this.routeId = routeId;
            this.serviceId = serviceId;
            this.headsign = headsign;
            this.directionId = directionId;
            this.shapeId = shapeId;
            this.path = path;
            this.stops = stops;
            this.stopOffsets = stopOffsets;
            this.startSec = stops.isEmpty() ? 0 : stops.get(0).arrivalSec;
            this.endSec = stops.isEmpty() ? 0 : stops.get(stops.size() - 1).departureSec;
        }
    }

    public static class TrenEstado {
        public final String tripId;
        public final String vehicleId;
        public final String linea;
        public final int lineaColor;
        public final String destino;
        public final LatLng posicion;
        public final float bearing;
        public final boolean enEstacion;
        public final String nombreEstacionActual;
        public final String idEstacionActual;
        public final String nombreProximaEstacion;
        public final String idProximaEstacion;
        public final String horaLlegadaProxima;
        public final int segundosLlegadaProxima;
        public final double velocidadKmH;

        public TrenEstado(String tripId, String vehicleId, String linea, int lineaColor,
                          String destino, LatLng posicion, float bearing,
                          boolean enEstacion, String nombreEstacionActual, String idEstacionActual,
                          String nombreProximaEstacion, String idProximaEstacion,
                          String horaLlegadaProxima, int segundosLlegadaProxima,
                          double velocidadKmH) {
            this.tripId = tripId;
            this.vehicleId = vehicleId;
            this.linea = linea;
            this.lineaColor = lineaColor;
            this.destino = destino;
            this.posicion = posicion;
            this.bearing = bearing;
            this.enEstacion = enEstacion;
            this.nombreEstacionActual = nombreEstacionActual;
            this.idEstacionActual = idEstacionActual;
            this.nombreProximaEstacion = nombreProximaEstacion;
            this.idProximaEstacion = idProximaEstacion;
            this.horaLlegadaProxima = horaLlegadaProxima;
            this.segundosLlegadaProxima = segundosLlegadaProxima;
            this.velocidadKmH = velocidadKmH;
        }
    }

    private static class CalendarEntry {
        final String serviceId;
        final boolean[] days;   // 1..7 = lunes..domingo
        final int startDate;    // yyyyMMdd
        final int endDate;

        CalendarEntry(String serviceId, boolean[] days, int startDate, int endDate) {
            this.serviceId = serviceId;
            this.days = days;
            this.startDate = startDate;
            this.endDate = endDate;
        }
    }

    private final Map<String, LatLng> paradas = new HashMap<>();
    private final Map<String, String> nombresParadas = new HashMap<>();
    private final Map<String, Trip> todosLosTrips = new HashMap<>();
    private final Map<String, List<Trip>> tripsPorServicio = new HashMap<>();
    private final List<CalendarEntry> calendarEntries = new ArrayList<>();
    private final Map<Integer, Map<String, Integer>> calendarDates = new HashMap<>();

    // Cache de offsets por shape_id y stop_id
    private final Map<String, Map<String, Double>> offsetCachePorShape = new HashMap<>();

    private volatile String servicioActivo = "";

    // ---- API pública ----

    public void cargar(MetroRepository.GtfsSource source, Map<String, ShapePath> shapesPorId) throws Exception {
        long t0 = System.currentTimeMillis();

        cargarParadas(source);
        Map<String, String[]> viajesMeta = cargarViajes(source);
        Map<String, List<StopTime>> stopTimesPorTrip = cargarHorarios(source);
        cargarCalendar(source);
        cargarCalendarDates(source);

        construirTrips(viajesMeta, stopTimesPorTrip, shapesPorId);

        servicioActivo = resolverServicio();

        android.util.Log.d(LOG_TAG, "HorarioGestor: " + todosLosTrips.size() + " viajes · servicio="
                + servicioActivo + " · total=" + (System.currentTimeMillis() - t0) + "ms");
    }

    // ---- Paradas ----

    private void cargarParadas(MetroRepository.GtfsSource source) throws IOException {
        try (BufferedReader r = source.open("stops.txt", "paradas.txt")) {
            String header = r.readLine();
            if (header == null) return;
            String[] cols = header.split(",", -1);
            int idxId = MetroRepository.colIdx(cols, "stop_id");
            int idxName = MetroRepository.colIdx(cols, "stop_name");
            int idxLat = MetroRepository.colIdx(cols, "stop_lat");
            int idxLon = MetroRepository.colIdx(cols, "stop_lon");
            if (idxId < 0) { idxId = 0; idxName = 2; idxLat = 3; idxLon = 4; }

            String l;
            while ((l = r.readLine()) != null) {
                String[] c = l.split(",", -1);
                if (c.length <= Math.max(Math.max(idxId, idxName), Math.max(idxLat, idxLon))) continue;
                try {
                    String id = c[idxId].trim();
                    String name = c[idxName].trim();
                    double lat = Double.parseDouble(c[idxLat].trim());
                    double lon = Double.parseDouble(c[idxLon].trim());
                    if (lat == 0 && lon == 0) continue;
                    paradas.put(id, new LatLng(lat, lon));
                    if (!name.isEmpty()) nombresParadas.put(id, name);
                } catch (NumberFormatException ignored) {}
            }
        }
    }

    // ---- Viajes ----

    private Map<String, String[]> cargarViajes(MetroRepository.GtfsSource source) throws IOException {
        Map<String, String[]> out = new HashMap<>();
        try (BufferedReader r = source.open("trips.txt", "viajes.txt")) {
            String header = r.readLine();
            if (header == null) return out;
            String[] cols = header.split(",", -1);
            int idxTrip = MetroRepository.colIdx(cols, "trip_id");
            int idxRoute = MetroRepository.colIdx(cols, "route_id");
            int idxSvc = MetroRepository.colIdx(cols, "service_id");
            int idxHs = MetroRepository.colIdx(cols, "trip_headsign");
            int idxDir = MetroRepository.colIdx(cols, "direction_id");
            int idxShape = MetroRepository.colIdx(cols, "shape_id");

            String l;
            while ((l = r.readLine()) != null) {
                String[] c = l.split(",", -1);
                if (c.length <= Math.max(Math.max(idxTrip, idxRoute), idxShape)) continue;
                String tripId = c[idxTrip].trim();
                if (tripId.isEmpty()) continue;
                String route = idxRoute >= 0 ? c[idxRoute].trim() : "";
                String svc = idxSvc >= 0 ? c[idxSvc].trim() : "";
                String hs = idxHs >= 0 ? c[idxHs].trim() : "";
                String dir = idxDir >= 0 ? c[idxDir].trim() : "0";
                String shape = idxShape >= 0 ? c[idxShape].trim() : "";
                out.put(tripId, new String[]{route, svc, hs, dir, shape});
            }
        }
        return out;
    }

    // ---- Horarios ----

    private Map<String, List<StopTime>> cargarHorarios(MetroRepository.GtfsSource source) throws IOException {
        Map<String, List<StopTime>> out = new HashMap<>();
        try (BufferedReader r = source.open("stop_times.txt", "horarios.txt")) {
            String header = r.readLine();
            if (header == null) return out;
            String[] cols = header.split(",", -1);
            int idxTrip = MetroRepository.colIdx(cols, "trip_id");
            int idxSeq = MetroRepository.colIdx(cols, "stop_sequence");
            int idxArr = MetroRepository.colIdx(cols, "arrival_time");
            int idxDep = MetroRepository.colIdx(cols, "departure_time");
            int idxStop = MetroRepository.colIdx(cols, "stop_id");
            // fallback a orden del fichero del usuario si no hay header reconocible
            if (idxTrip < 0) { idxTrip = 0; idxSeq = 1; idxArr = 2; idxDep = 3; idxStop = 4; }

            String l;
            while ((l = r.readLine()) != null) {
                String[] c = l.split(",", -1);
                if (c.length <= Math.max(Math.max(idxTrip, idxSeq), Math.max(idxArr, Math.max(idxDep, idxStop))))
                    continue;
                try {
                    String tripId = c[idxTrip].trim();
                    int seq = Integer.parseInt(c[idxSeq].trim());
                    int arr = parseHora(c[idxArr].trim());
                    int dep = parseHora(c[idxDep].trim());
                    String stopId = c[idxStop].trim();
                    if (arr < 0 || dep < 0) continue;
                    out.computeIfAbsent(tripId, k -> new ArrayList<>())
                            .add(new StopTime(seq, arr, dep, stopId));
                } catch (NumberFormatException ignored) {}
            }
        }
        for (List<StopTime> list : out.values()) {
            list.sort((a, b) -> Integer.compare(a.stopSequence, b.stopSequence));
        }
        return out;
    }

    // ---- calendar.txt ----

    private void cargarCalendar(MetroRepository.GtfsSource source) {
        try (BufferedReader r = source.open("calendar.txt")) {
            String header = r.readLine();
            if (header == null) return;
            String[] cols = header.split(",", -1);
            int idxSvc = MetroRepository.colIdx(cols, "service_id");
            int idxMon = MetroRepository.colIdx(cols, "monday");
            int idxTue = MetroRepository.colIdx(cols, "tuesday");
            int idxWed = MetroRepository.colIdx(cols, "wednesday");
            int idxThu = MetroRepository.colIdx(cols, "thursday");
            int idxFri = MetroRepository.colIdx(cols, "friday");
            int idxSat = MetroRepository.colIdx(cols, "saturday");
            int idxSun = MetroRepository.colIdx(cols, "sunday");
            int idxStart = MetroRepository.colIdx(cols, "start_date");
            int idxEnd = MetroRepository.colIdx(cols, "end_date");

            String l;
            while ((l = r.readLine()) != null) {
                String[] c = l.split(",", -1);
                if (idxSvc < 0 || idxSvc >= c.length) continue;
                boolean[] d = new boolean[8];
                d[1] = idxMon >= 0 && idxMon < c.length && "1".equals(c[idxMon].trim());
                d[2] = idxTue >= 0 && idxTue < c.length && "1".equals(c[idxTue].trim());
                d[3] = idxWed >= 0 && idxWed < c.length && "1".equals(c[idxWed].trim());
                d[4] = idxThu >= 0 && idxThu < c.length && "1".equals(c[idxThu].trim());
                d[5] = idxFri >= 0 && idxFri < c.length && "1".equals(c[idxFri].trim());
                d[6] = idxSat >= 0 && idxSat < c.length && "1".equals(c[idxSat].trim());
                d[7] = idxSun >= 0 && idxSun < c.length && "1".equals(c[idxSun].trim());
                int sd = 0, ed = 0;
                try {
                    if (idxStart >= 0 && idxStart < c.length) sd = Integer.parseInt(c[idxStart].trim());
                    if (idxEnd >= 0 && idxEnd < c.length) ed = Integer.parseInt(c[idxEnd].trim());
                } catch (NumberFormatException ignored) {}
                calendarEntries.add(new CalendarEntry(c[idxSvc].trim(), d, sd, ed));
            }
        } catch (IOException ignored) {
            // calendar.txt opcional
        }
    }

    private void cargarCalendarDates(MetroRepository.GtfsSource source) {
        try (BufferedReader r = source.open("calendar_dates.txt")) {
            String header = r.readLine();
            if (header == null) return;
            String[] cols = header.split(",", -1);
            int idxSvc = MetroRepository.colIdx(cols, "service_id");
            int idxDate = MetroRepository.colIdx(cols, "date");
            int idxEx = MetroRepository.colIdx(cols, "exception_type");

            String l;
            while ((l = r.readLine()) != null) {
                String[] c = l.split(",", -1);
                if (idxSvc < 0 || idxSvc >= c.length) continue;
                if (idxDate < 0 || idxDate >= c.length) continue;
                if (idxEx < 0 || idxEx >= c.length) continue;
                try {
                    String svc = c[idxSvc].trim();
                    int date = Integer.parseInt(c[idxDate].trim());
                    int ex = Integer.parseInt(c[idxEx].trim());
                    calendarDates
                            .computeIfAbsent(date, k -> new HashMap<>())
                            .put(svc, ex);
                } catch (NumberFormatException ignored) {}
            }
        } catch (IOException ignored) {
            // calendar_dates.txt opcional
        }
    }

    // ---- Construcción de trips con proyección por shape ----

    private void construirTrips(Map<String, String[]> viajesMeta,
                                Map<String, List<StopTime>> stopTimesPorTrip,
                                Map<String, ShapePath> shapesPorId) {
        int construidos = 0;
        int sinShape = 0;
        for (Map.Entry<String, List<StopTime>> entry : stopTimesPorTrip.entrySet()) {
            String tripId = entry.getKey();
            List<StopTime> stops = entry.getValue();
            String[] meta = viajesMeta.get(tripId);
            if (meta == null) continue;

            String serviceId = meta[1];
            String headsign = meta[2];
            int dirId = 0;
            try { dirId = Integer.parseInt(meta[3]); } catch (Exception ignored) {}
            String shapeId = meta[4];

            ShapePath path = shapesPorId.get(shapeId);
            if (path == null) {
                sinShape++;
                // Fallback: probar el primer shape de la misma línea geográfica
                path = pickFallbackPath(shapesPorId, headsign);
            }
            String line = (path != null) ? classifyLine(path, shapeId, headsign) : "L1";

            double[] offsets = new double[stops.size()];
            if (path != null) {
                proyectarStops(stops, path, shapeId, offsets);
            }

            Trip trip = new Trip(tripId, line, serviceId, headsign, dirId, shapeId,
                    path, stops, offsets);
            todosLosTrips.put(tripId, trip);
            tripsPorServicio.computeIfAbsent(serviceId, k -> new ArrayList<>()).add(trip);
            construidos++;
        }
        if (sinShape > 0) {
            android.util.Log.w(LOG_TAG, "HorarioGestor: " + sinShape + " trips con shape_id desconocido");
        }
    }

    private ShapePath pickFallbackPath(Map<String, ShapePath> shapes, String headsign) {
        // elige un shape cuyo headsign clasifique igual
        for (Map.Entry<String, ShapePath> e : shapes.entrySet()) {
            if (classifyLine(e.getValue(), e.getKey(), headsign).equals("L1")) return e.getValue();
        }
        for (ShapePath p : shapes.values()) return p;
        return null;
    }

    private void proyectarStops(List<StopTime> stops, ShapePath path, String shapeId,
                                double[] offsets) {
        Map<String, Double> cache = offsetCachePorShape
                .computeIfAbsent(shapeId, k -> new HashMap<>());
        double lastOff = 0;
        boolean firstDone = false;

        for (int i = 0; i < stops.size(); i++) {
            StopTime st = stops.get(i);
            LatLng spos = paradas.get(st.stopId);

            if (spos == null) {
                if (i > 0) offsets[i] = offsets[i - 1];
                continue;
            }
            Double cached = cache.get(st.stopId);
            if (cached != null) {
                offsets[i] = cached;
                lastOff = cached;
                firstDone = true;
                continue;
            }
            double off = firstDone
                    ? path.projectOffset(spos, lastOff, HINT_SEARCH_METERS)
                    : path.projectOffset(spos, -1, 0);
            if (firstDone && off < lastOff && (lastOff - off) < MAX_REVERSE_ARTIFACT_M) {
                off = lastOff;
            }
            offsets[i] = off;
            lastOff = off;
            firstDone = true;
            cache.put(st.stopId, off);
        }
    }

    // ---- Clasificación de línea ----

    static String classifyLine(ShapePath shape, String shapeId, String headsign) {
        if (shape != null && !shape.points.isEmpty()) {
            for (LatLng p : shape.points) if (p.latitude > 43.35) return "L1";
            for (LatLng p : shape.points) if (p.longitude < -3.00) return "L2";
        }
        if (shapeId != null) {
            if (shapeId.contains("PLE") || shapeId.contains("SOP") || shapeId.contains("URD")
                    || shapeId.contains("LAR") || shapeId.contains("BID")
                    || shapeId.contains("IBB"))
                return "L1";
            if (shapeId.contains("KAB") || shapeId.contains("ANS") || shapeId.contains("STZ"))
                return "L2";
        }
        if (headsign != null) {
            String h = headsign.toLowerCase(Locale.ROOT);
            if (h.contains("plentzia") || h.contains("sopela") || h.contains("larrabasterra")
                    || h.contains("bidezabal") || h.contains("ibarbengoa") || h.contains("urduliz"))
                return "L1";
            if (h.contains("kabiezes") || h.contains("santurtzi") || h.contains("basauri"))
                return "L2";
        }
        return "L1";
    }

    // ---- Resolución de servicio activo ----

    private String resolverServicio() {
        Calendar cal = Calendar.getInstance(TZ_MADRID);
        int dow = cal.get(Calendar.DAY_OF_WEEK);
        int dayIdx = (dow == Calendar.SUNDAY) ? 7 : (dow - 1);
        int dateInt = cal.get(Calendar.YEAR) * 10000
                + (cal.get(Calendar.MONTH) + 1) * 100
                + cal.get(Calendar.DAY_OF_MONTH);

        Map<String, Integer> exceptions = calendarDates.get(dateInt);

        // 1) exception_type=1 (añadidos) → máxima prioridad
        if (exceptions != null) {
            for (Map.Entry<String, Integer> e : exceptions.entrySet()) {
                if (e.getValue() == 1 && tripsPorServicio.containsKey(e.getKey())) {
                    return e.getKey();
                }
            }
        }

        // 2) calendar.txt, saltando los exception_type=2
        for (CalendarEntry ce : calendarEntries) {
            if (dateInt < ce.startDate || dateInt > ce.endDate) continue;
            if (!ce.days[dayIdx]) continue;
            if (exceptions != null && exceptions.getOrDefault(ce.serviceId, 0) == 2) continue;
            if (tripsPorServicio.containsKey(ce.serviceId)) return ce.serviceId;
        }

        // 3) Fallback: servicio con más trips cuyo startSec esté más cerca de ahora
        double ahoraSec = ahoraSegundosMadridContinuo();
        String mejor = null;
        double menorDist = Double.MAX_VALUE;
        int maxTrips = -1;
        for (Map.Entry<String, List<Trip>> e : tripsPorServicio.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            double distMin = Double.MAX_VALUE;
            for (Trip t : e.getValue()) {
                double diff = Math.abs(t.startSec - ahoraSec);
                if (diff < distMin) distMin = diff;
            }
            if (distMin < menorDist
                    || (Math.abs(distMin - menorDist) < 1e-3 && e.getValue().size() > maxTrips)) {
                menorDist = distMin;
                maxTrips = e.getValue().size();
                mejor = e.getKey();
            }
        }
        if (mejor != null) return mejor;

        // 4) cualquiera disponible
        if (!tripsPorServicio.isEmpty()) return tripsPorServicio.keySet().iterator().next();
        return "";
    }

    public void actualizarServicioConFeed(Set<String> tripsEnFeed) {
        if (tripsEnFeed == null || tripsEnFeed.isEmpty()) return;
        Map<String, Integer> conteo = new HashMap<>();
        for (String tid : tripsEnFeed) {
            Trip t = todosLosTrips.get(tid);
            if (t != null && t.serviceId != null)
                conteo.put(t.serviceId, conteo.getOrDefault(t.serviceId, 0) + 1);
        }
        String mejor = null;
        int max = 0;
        for (Map.Entry<String, Integer> e : conteo.entrySet()) {
            if (e.getValue() > max) { max = e.getValue(); mejor = e.getKey(); }
        }
        if (mejor != null && !mejor.equals(servicioActivo)) {
            int actual = conteo.getOrDefault(servicioActivo, 0);
            if (max > actual * 1.2) {
                android.util.Log.i(LOG_TAG, "Servicio RT: " + servicioActivo + " -> " + mejor);
                servicioActivo = mejor;
            }
        }
    }

    // ---- Tiempo ----

    public static double ahoraSegundosMadridContinuo() {
        Calendar cal = Calendar.getInstance(TZ_MADRID);
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        int min = cal.get(Calendar.MINUTE);
        int sec = cal.get(Calendar.SECOND);
        int ms = cal.get(Calendar.MILLISECOND);
        double totalSec = hour * 3600 + min * 60 + sec + ms / 1000.0;
        if (hour < 4) totalSec += 86400.0;
        return totalSec;
    }

    public static int ahoraSegundosMadrid() {
        return (int) Math.floor(ahoraSegundosMadridContinuo());
    }

    // ---- Cálculo de trenes activos ----

    public List<TrenEstado> getTrenesActivos(double ahoraSec, Map<String, String> vehiculosPorTrip) {
        List<Trip> tripsDelServicio = tripsPorServicio.get(servicioActivo);
        if (tripsDelServicio == null || tripsDelServicio.isEmpty())
            return Collections.emptyList();

        List<TrenEstado> resultado = new ArrayList<>();

        for (Trip trip : tripsDelServicio) {
            if (ahoraSec < trip.startSec || ahoraSec > trip.endSec) continue;
            if (trip.path == null) continue;

            String vehicleId = (vehiculosPorTrip != null) ? vehiculosPorTrip.get(trip.tripId) : null;
            if (vehicleId == null || vehicleId.isEmpty()) vehicleId = trip.tripId;

            int color = "L2".equals(trip.routeId) ? COLOR_L2 : COLOR_L1;
            List<StopTime> stops = trip.stops;
            int nStops = stops.size();
            boolean enEstacion = false;

            for (int i = 0; i < nStops; i++) {
                StopTime s = stops.get(i);
                if (ahoraSec >= s.arrivalSec && ahoraSec <= s.departureSec) {
                    enEstacion = true;
                    double off = trip.stopOffsets[i];
                    LatLng pos = trip.path.pointAt(off);
                    float bearing = trip.path.bearingAt(off);
                    String curName = getNombreParada(s.stopId);
                    String nextName = (i + 1 < nStops) ? getNombreParada(stops.get(i + 1).stopId) : curName;
                    String nextId = (i + 1 < nStops) ? stops.get(i + 1).stopId : s.stopId;
                    int nextArr = (i + 1 < nStops) ? stops.get(i + 1).arrivalSec : s.departureSec;
                    int segProx = Math.max(0, (int) Math.round(nextArr - ahoraSec));
                    String horaLlegada = String.format(Locale.US, "%02d:%02d",
                            (nextArr / 3600) % 24, (nextArr / 60) % 60);

                    resultado.add(new TrenEstado(
                            trip.tripId, vehicleId, trip.routeId, color, trip.headsign,
                            pos, bearing, true, curName, s.stopId,
                            nextName, nextId, horaLlegada, segProx, 0.0));
                    break;
                }
            }
            if (enEstacion) continue;

            for (int i = 0; i < nStops - 1; i++) {
                StopTime sA = stops.get(i);
                StopTime sB = stops.get(i + 1);
                if (ahoraSec > sA.departureSec && ahoraSec < sB.arrivalSec) {
                    double dur = sB.arrivalSec - sA.departureSec;
                    double elapsed = ahoraSec - sA.departureSec;
                    double p = dur > 0 ? (elapsed / dur) : 0.0;
                    p = Math.max(0, Math.min(1, p));
                    double pSmooth = p * p * (3.0 - 2.0 * p);

                    double offA = trip.stopOffsets[i];
                    double offB = trip.stopOffsets[i + 1];
                    double currentOff = offA + pSmooth * (offB - offA);

                    LatLng pos = trip.path.pointAt(currentOff);
                    float bearing = trip.path.bearingAt(currentOff);

                    double distMeters = Math.abs(offB - offA);
                    double speedKmH = dur > 0 ? ((distMeters / dur) * 3.6) : 0.0;

                    String nextName = getNombreParada(sB.stopId);
                    int segProx = Math.max(0, (int) Math.round(sB.arrivalSec - ahoraSec));
                    String horaLlegada = String.format(Locale.US, "%02d:%02d",
                            (sB.arrivalSec / 3600) % 24, (sB.arrivalSec / 60) % 60);

                    resultado.add(new TrenEstado(
                            trip.tripId, vehicleId, trip.routeId, color, trip.headsign,
                            pos, bearing, false, null, null,
                            nextName, sB.stopId, horaLlegada, segProx, speedKmH));
                    break;
                }
            }
        }
        return resultado;
    }

    public String getNombreParada(String stopId) {
        if (stopId == null) return "—";
        String name = nombresParadas.get(stopId);
        if (name != null) return name;
        if (stopId.endsWith(".0")) {
            String base = stopId.substring(0, stopId.length() - 2);
            name = nombresParadas.get(base);
            if (name != null) return name;
        }
        return "Estación " + stopId;
    }

    public LatLng getParada(String stopId) {
        if (stopId == null) return null;
        LatLng p = paradas.get(stopId);
        if (p == null && stopId.endsWith(".0"))
            p = paradas.get(stopId.substring(0, stopId.length() - 2));
        return p;
    }

    public StopTime proximaParada(String tripId, int ahoraSec) {
        Trip trip = todosLosTrips.get(tripId);
        if (trip == null) return null;
        for (StopTime s : trip.stops) if (s.arrivalSec > ahoraSec) return s;
        return null;
    }

    private static int parseHora(String hhmmss) {
        int n = hhmmss.length();
        if (n < 7) return -1;
        try {
            int p1 = hhmmss.indexOf(':');
            int p2 = hhmmss.indexOf(':', p1 + 1);
            if (p1 < 1 || p2 < 0) return -1;
            int h = Integer.parseInt(hhmmss.substring(0, p1));
            int m = Integer.parseInt(hhmmss.substring(p1 + 1, p2));
            int s = Integer.parseInt(hhmmss.substring(p2 + 1));
            return h * 3600 + m * 60 + s;
        } catch (Exception e) { return -1; }
    }

    public Map<String, Trip> getTodosLosTrips() { return todosLosTrips; }
    public Map<String, List<Trip>> getTripsPorServicio() { return tripsPorServicio; }
    public String getServicioActivo() { return servicioActivo; }
    public int getTotalTrips() { return todosLosTrips.size(); }
}