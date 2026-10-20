package com.example.metrobilbao;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.Projection;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptor;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.MapColorScheme;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.PolylineOptions;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

public class MapaFragment extends Fragment implements OnMapReadyCallback {

    private static final String LOG_TAG = "MetroTren";
    private static final int COLOR_L1 = Color.rgb(255, 101, 5);
    private static final int COLOR_L2 = Color.rgb(0, 170, 100);
    private static final String SHAPE_L1 = "ETX_PLE";
    private static final String SHAPE_L2 = "BSR_KAB";

    private static final float POLYLINE_WIDTH_DP = 4.5f;
    private static final double MAX_GAP_METERS = 700.0;
    private static final double METERS_PER_DEG_LAT = 111320.0;
    private static final double PARALLEL_OFFSET_M = 7.0;
    private static final double PARALLEL_THRESHOLD_M = 100.0;
    private static final double REF_LAT = 43.2630, REF_LNG = -2.9350;
    private static final double M_PER_DEG_LAT_APPROX = 111320.0;
    private static final double M_PER_DEG_LNG_APPROX =
            M_PER_DEG_LAT_APPROX * Math.cos(Math.toRadians(REF_LAT));
    private static final double GRID_CELL_M = 100.0;
    private static final long ANIM_FRAME_MS = 33L;
    private static final float Z_LINEA = 2f, Z_TREN = 20f, Z_ESTACION = 10f;

    private TrenGestorTiempoReal gestorTrenes;
    private HorarioGestor horarioGestor;
    private ShapePath pathMasterL1, pathMasterL2;

    private final Map<String, Marker> marcadoresPorTrip = new HashMap<>();
    private final Map<String, String> vehiculosPorTrip = new ConcurrentHashMap<>();
    private final List<MetroGestor.Station> estacionesCache = new ArrayList<>();
    private final List<Marker> marcadoresEstaciones = new ArrayList<>();

    private volatile long lastFeedTs = 0L;
    private BitmapDescriptor iconoTrenL1, iconoTrenL2;
    private volatile boolean mapaListo = false;
    private volatile boolean actividadActiva = true;
    private GoogleMap mapa;
    private final Handler motor = new Handler(Looper.getMainLooper());

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup c,
                             @Nullable Bundle b) {
        return inflater.inflate(R.layout.fragment_mapa, c, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        SupportMapFragment mf = (SupportMapFragment)
                getChildFragmentManager().findFragmentById(R.id.map_container);
        if (mf == null) {
            mf = new SupportMapFragment();
            getChildFragmentManager().beginTransaction()
                    .replace(R.id.map_container, mf).commitNow();
        }
        mf.getMapAsync(this);
    }

    private void añadirVignette(ViewGroup root) {
        View vignette = new View(requireContext());
        vignette.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        vignette.setClickable(false);
        vignette.setFocusable(false);
        vignette.setBackground(new Drawable() {
            final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override public void draw(Canvas c) {
                int w = getBounds().width(), h = getBounds().height();
                if (w <= 0 || h <= 0) return;
                float r = Math.max(w, h) * 0.75f;
                RadialGradient g = new RadialGradient(w / 2f, h / 2f, r,
                        new int[]{Color.TRANSPARENT, Color.TRANSPARENT, 0xAA000000},
                        new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
                paint.setShader(g);
                c.drawRect(0, 0, w, h, paint);
            }
            @Override public void setAlpha(int a) {}
            @Override public void setColorFilter(ColorFilter cf) {}
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        });
        root.addView(vignette);
    }

    @Override
    public void onMapReady(GoogleMap googleMap) {
        this.mapa = googleMap;
        googleMap.setMapColorScheme(MapColorScheme.DARK);
        googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(
                new LatLng(43.2630, -2.9350), 12.5f));

        iconoTrenL1 = crearIconoTren(COLOR_L1);
        iconoTrenL2 = crearIconoTren(COLOR_L2);

        googleMap.setOnMarkerClickListener(marker -> {
            Object tag = marker.getTag();
            if (tag instanceof HorarioGestor.TrenEstado) {
                mostrarBottomSheetTren((HorarioGestor.TrenEstado) tag); return true;
            }
            if (tag instanceof MetroGestor.Station) {
                mostrarBottomSheetEstacion((MetroGestor.Station) tag); return true;
            }
            return false;
        });

        googleMap.setOnMapClickListener(latLng -> {
            MetroGestor.Station est = estacionCercanaAlToque(latLng, 40f);
            if (est != null) mostrarBottomSheetEstacion(est);
        });

        if (getView() instanceof ViewGroup) añadirVignette((ViewGroup) getView());

        mapaListo = true;
        iniciarMotorAnimacion();
        cargarTodoEnSegundoPlano();
    }

    private void cargarTodoEnSegundoPlano() {
    final long t0 = System.currentTimeMillis();
    MetroRepository.getInstance().init(requireContext(), new MetroRepository.Callback() {
        @Override public void onReady(MetroRepository repo) {
            if (!isAdded() || isRemoving() || !mapaListo) return;

            pathMasterL1 = repo.getPathMasterL1();
            pathMasterL2 = repo.getPathMasterL2();
            horarioGestor = repo.getHorarioGestor();

            estacionesCache.clear();
            estacionesCache.addAll(repo.getEstaciones());

            // FIX #3: cada paso aislado, un fallo no tumba el resto
            try { dibujarRecorridos(mapa); }
            catch (Exception e) { android.util.Log.e(LOG_TAG, "Error dibujando", e); }

            try {
                BitmapDescriptor iconoEst = crearIconoEstacion(mapa.getCameraPosition().zoom);
                for (MetroGestor.Station est : estacionesCache) {
                    Marker m = mapa.addMarker(new MarkerOptions()
                            .position(new LatLng(est.latitude, est.longitude))
                            .title(est.name).icon(iconoEst)
                            .anchor(0.5f, 0.5f).zIndex(Z_ESTACION));
                    if (m != null) { m.setTag(est); marcadoresEstaciones.add(m); }
                }
            } catch (Exception e) { android.util.Log.e(LOG_TAG, "Error estaciones", e); }

            iniciarFeedObservador();
            android.util.Log.d(LOG_TAG, "App lista en "
                    + (System.currentTimeMillis() - t0) + "ms");
        }

        @Override public void onError(Throwable t) {
            if (!isAdded() || isRemoving()) return;
            android.util.Log.e(LOG_TAG, "Error carga GTFS", t);
            Toast.makeText(requireContext(),
                    "Error cargando datos: " + t.getMessage(), Toast.LENGTH_LONG).show();
            // Aunque falle, intentamos arrancar el feed de trenes
            iniciarFeedObservador();
        }
    });
}

    private BitmapDescriptor crearIconoEstacion(float zoom) {
        float d = getResources().getDisplayMetrics().density;
        float f = Math.max(0.85f, Math.min((float) Math.pow(2, (zoom - 12.5f) * 0.18f), 1.5f));
        int size = Math.round(18 * d * f);
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp); Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float r = size * 0.38f;
        p.setColor(Color.rgb(65, 190, 255)); c.drawCircle(size/2f, size/2f, r, p);
        p.setColor(Color.WHITE); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(d);
        c.drawCircle(size/2f, size/2f, r, p);
        return BitmapDescriptorFactory.fromBitmap(bmp);
    }

    private BitmapDescriptor crearIconoTren(int colorTren) {
        float d = getResources().getDisplayMetrics().density;
        int size = Math.round(16 * d);
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp); Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float r = size * 0.38f;
        p.setColor(colorTren); c.drawCircle(size/2f, size/2f, r, p);
        p.setColor(Color.WHITE); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(d * 1.5f);
        c.drawCircle(size/2f, size/2f, r, p);
        return BitmapDescriptorFactory.fromBitmap(bmp);
    }

    private void mostrarBottomSheetEstacion(MetroGestor.Station estacion) {
        BottomSheetDialog dialog = new BottomSheetDialog(requireContext());
        View vista = getLayoutInflater().inflate(R.layout.display_estacion, null);

        TextView nombre = vista.findViewById(R.id.txtStationName);
        TextView codigo = vista.findViewById(R.id.txtStationCode);
        TextView coords = vista.findViewById(R.id.txtStationCoordinates);
        TextView frec   = vista.findViewById(R.id.txtFrecuencia);
        TextView conex  = vista.findViewById(R.id.txtConexiones);
        TextView info   = vista.findViewById(R.id.txtInfo);

        nombre.setText(estacion.name);
        if (estacion.code != null && !estacion.code.trim().isEmpty())
            codigo.setText("Código: " + estacion.code);
        else codigo.setText("ID de estación: " + estacion.parentId);
        coords.setText(String.format(Locale.getDefault(),
                "Coordenadas: %.5f, %.5f", estacion.latitude, estacion.longitude));
        frec.setText(EstacionInfo.frecuencia(estacion.parentId));
        conex.setText(EstacionInfo.conexiones(estacion.parentId));
        info.setText(EstacionInfo.info(estacion.name, estacion.parentId));

        dialog.setContentView(vista);
        if (dialog.getWindow() != null)
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        int h = getResources().getDisplayMetrics().heightPixels;
        dialog.show();
        BottomSheetBehavior<FrameLayout> b = dialog.getBehavior();
        b.setFitToContents(false);
        b.setHalfExpandedRatio(0.5f);
        b.setExpandedOffset((int) (h * 0.5f));
        b.setPeekHeight((int) (h * 0.5f));
        b.setState(BottomSheetBehavior.STATE_HALF_EXPANDED);
        View bottomSheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (bottomSheet != null) bottomSheet.setBackgroundColor(Color.TRANSPARENT);
    }

    private void mostrarBottomSheetTren(HorarioGestor.TrenEstado tren) {
        BottomSheetDialog dialog = new BottomSheetDialog(requireContext());
        View vista = getLayoutInflater().inflate(R.layout.display_tren, null);

        TextView titulo = vista.findViewById(R.id.txtTrenTitulo);
        TextView id     = vista.findViewById(R.id.txtTrenId);
        TextView proxTv = vista.findViewById(R.id.txtTrenProxima);
        TextView hora   = vista.findViewById(R.id.txtTrenHora);
        TextView tripTv = vista.findViewById(R.id.txtTrenTrip);
        Button verEst   = vista.findViewById(R.id.btnVerEstacion);

        titulo.setText("Línea " + tren.linea + " a " + tren.destino);
        titulo.setTextColor(tren.lineaColor);
        id.setText("Unidad " + tren.vehicleId + " · Viaje " + tren.tripId);
        tripTv.setText("Puntualidad: En hora según horario oficial · Destino: " + tren.destino);

        // FIX #5: cuenta atrás interactiva mientras el bottom sheet está abierto
        final android.os.Handler countdown = new android.os.Handler(android.os.Looper.getMainLooper());
        final int[] restante = { Math.max(0, tren.segundosLlegadaProxima) };

        final Runnable actualizar = new Runnable() {
            @Override public void run() {
                if (tren.enEstacion) {
                    proxTv.setText("Parado en " + tren.nombreEstacionActual);
                    hora.setText("En andén (salida inminente)");
                    return;
                }
                String p = tren.nombreProximaEstacion;
                if (tren.velocidadKmH > 1.0)
                    p += String.format(Locale.getDefault(), " · %.0f km/h", tren.velocidadKmH);
                proxTv.setText(p);

                String h = tren.horaLlegadaProxima;
                if (restante[0] > 0) {
                    h += " (en " + formatSegundos(restante[0]) + ")";
                } else {
                    h += " (llegando)";
                }
                hora.setText(h);

                if (restante[0] > 0) {
                    restante[0]--;
                    countdown.postDelayed(this, 1000);
                }
            }
        };
        countdown.post(actualizar);
        dialog.setOnDismissListener(d -> countdown.removeCallbacks(actualizar));

        // Botón estación
        String stopBuscada = tren.enEstacion ? tren.idEstacionActual : tren.idProximaEstacion;
        MetroGestor.Station cercana = buscarEstacion(stopBuscada, tren.posicion);
        if (cercana != null) {
            verEst.setVisibility(View.VISIBLE);
            verEst.setText("Ver estación " + cercana.name);
            final MetroGestor.Station fc = cercana;
            verEst.setOnClickListener(v -> { dialog.dismiss(); mostrarBottomSheetEstacion(fc); });
        } else verEst.setVisibility(View.GONE);

        dialog.setContentView(vista);
        if (dialog.getWindow() != null)
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        int h = getResources().getDisplayMetrics().heightPixels;
        dialog.show();
        BottomSheetBehavior<FrameLayout> b = dialog.getBehavior();
        b.setFitToContents(false);
        b.setHalfExpandedRatio(0.5f);
        b.setExpandedOffset((int) (h * 0.5f));   // <- tope arriba = mitad de pantalla
        b.setPeekHeight((int) (h * 0.5f));
        b.setState(BottomSheetBehavior.STATE_HALF_EXPANDED);

        // FIX #6: quitar el fondo blanco del contenedor interno del BottomSheet
        View bottomSheet = dialog.findViewById(
                com.google.android.material.R.id.design_bottom_sheet);
        if (bottomSheet != null) bottomSheet.setBackgroundColor(Color.TRANSPARENT);
    }

    // FIX #5: helper de formato de segundos
    private static String formatSegundos(int s) {
        if (s < 60) return s + " s";
        int m = s / 60;
        int seg = s % 60;
        return m + " min " + seg + " s";
    }

    private MetroGestor.Station buscarEstacion(String stopId, LatLng pos) {
        if (stopId != null) {
            String baseId = stopId.endsWith(".0") ? stopId.substring(0, stopId.length() - 2) : stopId;
            for (MetroGestor.Station s : estacionesCache) {
                if (s.parentId != null && (s.parentId.equals(stopId) || s.parentId.equals(baseId)))
                    return s;
            }
        }
        if (pos == null) return null;
        MetroGestor.Station closest = null; double minD = 500.0;
        for (MetroGestor.Station s : estacionesCache) {
            double d = distanceMeters(pos, new LatLng(s.latitude, s.longitude));
            if (d < minD) { minD = d; closest = s; }
        }
        return closest;
    }

    private static class EstacionInfo {
        static String frecuencia(String id) {
            switch (id == null ? "" : id) {
                case "1": return "L1 y L2 · cada 5–7 min en hora punta, 12 min en valle";
                case "7": return "L1 y L2 · cada 3–5 min (nudo de Abando)";
                case "30": return "L1 · cada 15–20 min (ramal Plentzia)";
                case "42": return "L1 · cada 7–10 min en hora punta";
                default: return "L1 y L2 · cada 5–10 min en hora punta, 12–15 min en valle";
            }
        }
        static String conexiones(String id) {
            switch (id == null ? "" : id) {
                case "7": return "Renfe Cercanías, FEVE, Bilbobus, Bizkaibus";
                case "10": return "Renfe Cercanías, Termibus, Bilbobus";
                case "25": return "Bilbobus, Bizkaibus (Getxo)";
                case "30": return "Bizkaibus (Plentzia), Puente Colgante";
                default: return "Metro L1, L2 · Bilbobus · Bizkaibus";
            }
        }
        static String info(String nombre, String id) {
            String extra;
            switch (id == null ? "" : id) {
                case "7": extra = "Estación principal del centro de Bilbao. Acceso adaptado, "
                        + "atención al cliente y oficina de objetos perdidos."; break;
                case "30": extra = "Terminal del ramal de Plentzia. Salida directa al puerto "
                        + "y a la playa. Aparcamiento disuasorio."; break;
                default: extra = "Acceso adaptado, máquinas de billetes, wifi gratuito "
                        + "y megafonía en euskera y castellano.";
            }
            return "Estación de " + nombre + ". " + extra;
        }
    }


    private void dibujarRecorridos(GoogleMap g) {
        if (pathMasterL1 == null || pathMasterL2 == null) return;
        drawLineWithGaps(g, pathMasterL1.points, COLOR_L1);
        drawLineWithGaps(g, pathMasterL2.points, COLOR_L2);
    }

    private static long gridKey(LatLng p) {
        long gx = (long) Math.floor(((p.longitude - REF_LNG) * M_PER_DEG_LNG_APPROX) / GRID_CELL_M) + 100000L;
        long gy = (long) Math.floor(((p.latitude - REF_LAT) * M_PER_DEG_LAT_APPROX) / GRID_CELL_M) + 100000L;
        return (gy << 32) | (gx & 0xFFFFFFFFL);
    }

    private List<LatLng> aplicarSeparacionParalela(List<LatLng> linea, List<LatLng> otra,
                                                   double maxOffsetMeters) {
        int n = linea.size(); if (n < 2) return new ArrayList<>(linea);
        Map<Long, List<LatLng>> grid = new HashMap<>();
        for (LatLng q : otra) grid.computeIfAbsent(gridKey(q), k -> new ArrayList<>()).add(q);

        List<LatLng> res = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            LatLng p = linea.get(i);
            long gx = (long) Math.floor(((p.longitude - REF_LNG) * M_PER_DEG_LNG_APPROX) / GRID_CELL_M) + 100000L;
            long gy = (long) Math.floor(((p.latitude - REF_LAT) * M_PER_DEG_LAT_APPROX) / GRID_CELL_M) + 100000L;
            double minDist = Double.MAX_VALUE;
            for (long dy = -1; dy <= 1; dy++)
                for (long dx = -1; dx <= 1; dx++) {
                    List<LatLng> cell = grid.get(((gy + dy) << 32) | ((gx + dx) & 0xFFFFFFFFL));
                    if (cell == null) continue;
                    for (LatLng q : cell) {
                        double d = distanceMeters(p, q);
                        if (d < minDist) minDist = d;
                    }
                }
            double factor = factorDesplazamientoSuave(minDist);
            if (factor <= 0.001) { res.add(p); continue; }
            LatLng a, b;
            if (i == 0) { a = linea.get(0); b = linea.get(1); }
            else if (i == n - 1) { a = linea.get(n - 2); b = linea.get(n - 1); }
            else { a = linea.get(i - 1); b = linea.get(i + 1); }
            res.add(desplazarPerpendicular(p, a, b, factor * maxOffsetMeters));
        }
        return res;
    }

    private double factorDesplazamientoSuave(double d) {
        if (d >= PARALLEL_THRESHOLD_M) return 0.0;
        double t = 1.0 - d / PARALLEL_THRESHOLD_M;
        return t * t * (3.0 - 2.0 * t);
    }

    private LatLng desplazarPerpendicular(LatLng p, LatLng a, LatLng b, double m) {
        double mLng = METERS_PER_DEG_LAT * Math.cos(Math.toRadians(p.latitude));
        double tx = (b.longitude - a.longitude) * mLng;
        double ty = (b.latitude - a.latitude) * METERS_PER_DEG_LAT;
        double tLen = Math.sqrt(tx * tx + ty * ty);
        if (tLen < 1e-9) return p;
        tx /= tLen; ty /= tLen;
        double px = -ty, py = tx;
        return new LatLng(p.latitude + (py * m) / METERS_PER_DEG_LAT,
                p.longitude + (px * m) / mLng);
    }

    private void iniciarFeedObservador() {
        gestorTrenes = new TrenGestorTiempoReal();
        gestorTrenes.start(new TrenGestorTiempoReal.Callback() {
            @Override public void onUpdate(List<TrenGestorTiempoReal.TrainPosition> trenes,
                                           long ts, long id, boolean same) {
                // FIX #10: descartar feed obsoleto (S3 a veces sirve snapshot anterior)
                if (ts > 0 && ts < lastFeedTs) {
                    android.util.Log.w(LOG_TAG, "Feed obsoleto descartado: " + ts + " < " + lastFeedTs);
                    return;
                }
                if (ts > 0) lastFeedTs = ts;

                Set<String> trips = new HashSet<>();
                for (TrenGestorTiempoReal.TrainPosition t : trenes) {
                    if (t.tripId != null && !t.tripId.isEmpty()) {
                        trips.add(t.tripId);
                        if (t.vehicleId != null && !t.vehicleId.isEmpty())
                            vehiculosPorTrip.put(t.tripId, t.vehicleId);
                    }
                }
                if (horarioGestor != null && !trips.isEmpty())
                    horarioGestor.actualizarServicioConFeed(trips);
            }
            @Override public void onError(String m) { android.util.Log.w(LOG_TAG, "Feed: " + m); }
        });
    }

    private void iniciarMotorAnimacion() {
        motor.removeCallbacks(animRunnable);
        motor.post(animRunnable);
    }

    private final Runnable animRunnable = new Runnable() {
        @Override public void run() {
            if (!mapaListo || !actividadActiva || !isAdded()) return;
            try { actualizarAnimacionTrenes(); }
            catch (Exception e) { android.util.Log.e(LOG_TAG, "Error anim", e); }
            motor.postDelayed(this, ANIM_FRAME_MS);
        }
    };

    private void actualizarAnimacionTrenes() {
        if (horarioGestor == null || mapa == null) return;
        double ahora = HorarioGestor.ahoraSegundosMadridContinuo();
        List<HorarioGestor.TrenEstado> trenes = horarioGestor.getTrenesActivos(ahora, vehiculosPorTrip);
        Set<String> activos = new HashSet<>();
        for (HorarioGestor.TrenEstado t : trenes) {
            activos.add(t.tripId);
            Marker m = marcadoresPorTrip.get(t.tripId);
            if (m == null) {
                BitmapDescriptor ic = "L2".equals(t.linea) ? iconoTrenL2 : iconoTrenL1;
                m = mapa.addMarker(new MarkerOptions()
                        .position(t.posicion).icon(ic)
                        .title("Línea " + t.linea + " a " + t.destino)
                        .anchor(0.5f, 0.5f).zIndex(Z_TREN));
                if (m != null) { m.setTag(t); marcadoresPorTrip.put(t.tripId, m); }
            } else { m.setPosition(t.posicion); m.setTag(t); }
        }
        Iterator<Map.Entry<String, Marker>> it = marcadoresPorTrip.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Marker> e = it.next();
            if (!activos.contains(e.getKey())) { e.getValue().remove(); it.remove(); }
        }
    }

    @Override public void onStart() {
        super.onStart();
        actividadActiva = true;
        if (mapaListo) iniciarMotorAnimacion();
    }

    @Override public void onStop() {
        super.onStop();
        actividadActiva = false;
        motor.removeCallbacks(animRunnable);
    }

    @Override public void onDestroyView() {
        actividadActiva = false; mapaListo = false;
        motor.removeCallbacks(animRunnable);
        if (gestorTrenes != null) { gestorTrenes.shutdown(); gestorTrenes = null; }
        for (Marker m : marcadoresPorTrip.values()) m.remove();
        marcadoresPorTrip.clear();
        for (Marker m : marcadoresEstaciones) m.remove();
        marcadoresEstaciones.clear();
        estacionesCache.clear();
        super.onDestroyView();
    }

    private static double distanceMeters(LatLng a, LatLng b) {
        double mLng = METERS_PER_DEG_LAT * Math.cos(Math.toRadians((a.latitude + b.latitude) * 0.5));
        double dLat = (b.latitude - a.latitude) * METERS_PER_DEG_LAT;
        double dLng = (b.longitude - a.longitude) * mLng;
        return Math.sqrt(dLat * dLat + dLng * dLng);
    }

    private void drawLineWithGaps(GoogleMap g, List<LatLng> pts, int color) {
        if (pts.size() < 2) return;
        List<LatLng> cur = new ArrayList<>(); cur.add(pts.get(0));
        for (int i = 1; i < pts.size(); i++) {
            if (distanceMeters(pts.get(i - 1), pts.get(i)) > MAX_GAP_METERS) {
                if (cur.size() >= 2) g.addPolyline(new PolylineOptions()
                        .addAll(cur).color(color).width(POLYLINE_WIDTH_DP)
                        .geodesic(false).zIndex(Z_LINEA));
                cur = new ArrayList<>();
            }
            cur.add(pts.get(i));
        }
        if (cur.size() >= 2) g.addPolyline(new PolylineOptions()
                .addAll(cur).color(color).width(POLYLINE_WIDTH_DP)
                .geodesic(false).zIndex(Z_LINEA));
    }

    private MetroGestor.Station estacionCercanaAlToque(LatLng tap, float radioPx) {
        if (mapa == null || estacionesCache.isEmpty()) return null;
        Projection proj = mapa.getProjection();
        android.graphics.Point tp = proj.toScreenLocation(tap);
        MetroGestor.Station mejor = null; float mejorD2 = radioPx * radioPx;
        for (MetroGestor.Station s : estacionesCache) {
            android.graphics.Point sp = proj.toScreenLocation(new LatLng(s.latitude, s.longitude));
            float dx = sp.x - tp.x, dy = sp.y - tp.y;
            float d2 = dx * dx + dy * dy;
            if (d2 <= mejorD2) { mejorD2 = d2; mejor = s; }
        }
        return mejor;
    }
}