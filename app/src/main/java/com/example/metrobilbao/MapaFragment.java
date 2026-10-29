package com.example.metrobilbao;

import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
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
import com.google.android.gms.maps.model.MapStyleOptions;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.PolylineOptions;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

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

    private static final String PREFS = "metro_prefs";
    private static final String KEY_MAP_DARK = "map_dark_mode";

    private static final int REFRESCO_SEG = 10;
    private static final int GRACIA_POST_LLEGADA_SEG = 15;
    private static final int MAX_PROXIMOS = 5;

    private static final String MAP_STYLE_NO_POI =
            "[" +
                    "{\"featureType\":\"poi.business\",\"stylers\":[{\"visibility\":\"off\"}]}," +
                    "{\"featureType\":\"poi.government\",\"stylers\":[{\"visibility\":\"off\"}]}," +
                    "{\"featureType\":\"poi.medical\",\"stylers\":[{\"visibility\":\"off\"}]}," +
                    "{\"featureType\":\"poi.place_of_worship\",\"stylers\":[{\"visibility\":\"off\"}]}," +
                    "{\"featureType\":\"poi.school\",\"stylers\":[{\"visibility\":\"off\"}]}," +
                    "{\"featureType\":\"poi.sports_complex\",\"stylers\":[{\"visibility\":\"off\"}]}," +
                    "{\"featureType\":\"transit\",\"stylers\":[{\"visibility\":\"off\"}]}" +
                    "]";

    private TrenGestorTiempoReal gestorTrenes;
    private HorarioGestor horarioGestor;
    private ShapePath pathMasterL1, pathMasterL2;

    private final Map<String, Marker> marcadoresPorTrip = new HashMap<>();
    private final Map<String, String> vehiculosPorTrip = new ConcurrentHashMap<>();
    private final List<MetroGestor.Station> estacionesCache = new ArrayList<>();
    private final List<Marker> marcadoresEstaciones = new ArrayList<>();

    private final List<ProximaSalida> salidasProximas = new ArrayList<>();
    private final List<TextView> badgesProximos = new ArrayList<>();

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
        View topBanner = view.findViewById(R.id.topBanner);
        if (topBanner != null) {
            final int origTop = topBanner.getPaddingTop();
            ViewCompat.setOnApplyWindowInsetsListener(topBanner, (v, insets) -> {
                Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
                v.setPaddingRelative(
                        v.getPaddingStart(),
                        origTop + bars.top,
                        v.getPaddingEnd(),
                        v.getPaddingBottom());
                return insets;
            });
        }
    }

    @Override
    public void onMapReady(GoogleMap googleMap) {
        this.mapa = googleMap;

        SharedPreferences prefs = requireContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        boolean dark = prefs.getBoolean(KEY_MAP_DARK, true);

        googleMap.setMapColorScheme(dark ? MapColorScheme.DARK : MapColorScheme.LIGHT);
        googleMap.setMapStyle(new MapStyleOptions(MAP_STYLE_NO_POI));
        googleMap.setIndoorEnabled(false);
        googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(
                new LatLng(REF_LAT, REF_LNG), 13f));

        googleMap.getUiSettings().setCompassEnabled(false);
        googleMap.getUiSettings().setMapToolbarEnabled(false);
        googleMap.getUiSettings().setZoomControlsEnabled(false);
        googleMap.getUiSettings().setMyLocationButtonEnabled(false);
        googleMap.getUiSettings().setIndoorLevelPickerEnabled(false);
        googleMap.getUiSettings().setRotateGesturesEnabled(false);
        googleMap.getUiSettings().setTiltGesturesEnabled(false);

        iconoTrenL1 = crearIconoTren(COLOR_L1);
        iconoTrenL2 = crearIconoTren(COLOR_L2);

        googleMap.setOnMarkerClickListener(marker -> {
            Object tag = marker.getTag();
            if (tag instanceof HorarioGestor.TrenEstado) {
                mostrarBottomSheetTren((HorarioGestor.TrenEstado) tag);
                return true;
            }
            if (tag instanceof MetroGestor.Station) {
                mostrarBottomSheetEstacion((MetroGestor.Station) tag);
                return true;
            }
            return false;
        });

        googleMap.setOnMapClickListener(latLng -> {
            MetroGestor.Station est = estacionCercanaAlToque(latLng, 40f);
            if (est != null) mostrarBottomSheetEstacion(est);
        });

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
        BottomSheetDialog dialog = new BottomSheetDialog(requireContext(),
                R.style.Theme_MetroBilbao_BottomSheetDialog);
        View vista = getLayoutInflater().inflate(R.layout.display_estacion, null);

        TextView nombre = vista.findViewById(R.id.txtStationName);
        TextView codigo = vista.findViewById(R.id.txtStationCode);
        TextView coords = vista.findViewById(R.id.txtStationCoordinates);
        TextView frec   = vista.findViewById(R.id.txtFrecuencia);
        TextView conex  = vista.findViewById(R.id.txtConexiones);
        TextView info   = vista.findViewById(R.id.txtInfo);
        LinearLayout containerProx = vista.findViewById(R.id.containerProximosTrenes);

        nombre.setText(estacion.name);
        if (estacion.code != null && !estacion.code.trim().isEmpty())
            codigo.setText("Código: " + estacion.code);
        else codigo.setText("ID de estación: " + estacion.parentId);
        coords.setText(String.format(Locale.getDefault(),
                "Coordenadas: %.5f, %.5f", estacion.latitude, estacion.longitude));
        frec.setText(EstacionInfo.frecuencia(estacion.parentId));
        conex.setText(EstacionInfo.conexiones(estacion.parentId));
        info.setText(EstacionInfo.info(estacion.name, estacion.parentId));

        if (containerProx != null) {
            llenarProximosTrenes(containerProx, estacion.parentId);
        }

        final Handler tickH = new Handler(Looper.getMainLooper());
        final int[] ticks = {0};
        final Runnable tickR = new Runnable() {
            @Override public void run() {
                if (!isAdded() || containerProx == null) return;
                ticks[0]++;
                if (ticks[0] % REFRESCO_SEG == 0) {
                    llenarProximosTrenes(containerProx, estacion.parentId);
                } else {
                    actualizarBadgesProximos();
                }
                tickH.postDelayed(this, 1000);
            }
        };
        tickH.postDelayed(tickR, 1000);

        dialog.setOnDismissListener(d -> {
            tickH.removeCallbacks(tickR);
            salidasProximas.clear();
            badgesProximos.clear();
        });

        dialog.setContentView(vista);
        if (dialog.getWindow() != null)
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        final int h = getResources().getDisplayMetrics().heightPixels;

        dialog.setOnShowListener(d -> {
            FrameLayout sheet = dialog.findViewById(
                    com.google.android.material.R.id.design_bottom_sheet);
            if (sheet != null) {
                sheet.setBackground(null);
                ViewGroup.LayoutParams lp = sheet.getLayoutParams();
                lp.height = (int) (h * 0.50);
                sheet.setLayoutParams(lp);
            }
        });

        dialog.show();

        BottomSheetBehavior<FrameLayout> b = dialog.getBehavior();
        b.setFitToContents(false);
        b.setSkipCollapsed(true);
        b.setHideable(false);                    // no se puede ocultar con drag del behavior
        b.setDraggable(false);                   // el behavior no intercepta drags
        b.setHalfExpandedRatio(0.50f);
        b.setPeekHeight((int) (h * 0.50));
        b.setExpandedOffset((int) (h * 0.50));
        b.setState(BottomSheetBehavior.STATE_HALF_EXPANDED);
        final View dragHandle = vista.findViewById(R.id.dragHandle);
        final FrameLayout sheet = dialog.findViewById(
                com.google.android.material.R.id.design_bottom_sheet);

        if (dragHandle != null && sheet != null) {
            final float[] startRawY = {0f};
            final float[] startTransY = {0f};
            final float[] dyAcum = {0f};

            dragHandle.setOnTouchListener((v, event) -> {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startRawY[0] = event.getRawY();
                        startTransY[0] = sheet.getTranslationY();
                        dyAcum[0] = 0f;
                        return true;

                    case MotionEvent.ACTION_MOVE: {
                        float dy = event.getRawY() - startRawY[0];
                        dyAcum[0] = dy;
                        if (dy > 0f) {
                            sheet.setTranslationY(startTransY[0] + dy);
                        }
                        return true;
                    }

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (dyAcum[0] > 100f) {
                            sheet.animate()
                                    .translationY(startTransY[0] + sheet.getHeight())
                                    .setDuration(200)
                                    .withEndAction(() -> {
                                        sheet.setTranslationY(startTransY[0]);
                                        dialog.dismiss();
                                    })
                                    .start();
                        } else {
                            sheet.animate()
                                    .translationY(startTransY[0])
                                    .setDuration(200)
                                    .start();
                        }
                        return true;
                }
                return false;
            });
        }
    }
    private void mostrarBottomSheetTren(HorarioGestor.TrenEstado tren) {
        BottomSheetDialog dialog = new BottomSheetDialog(requireContext(),
                R.style.Theme_MetroBilbao_BottomSheetDialog);
        View vista = getLayoutInflater().inflate(R.layout.display_tren, null);

        TextView titulo       = vista.findViewById(R.id.txtTrenTitulo);
        TextView id           = vista.findViewById(R.id.txtTrenId);
        TextView proxTv       = vista.findViewById(R.id.txtTrenProxima);
        TextView hora         = vista.findViewById(R.id.txtTrenHora);
        TextView destinoTv    = vista.findViewById(R.id.txtTrenDestino);
        TextView puntualidadTv= vista.findViewById(R.id.txtTrenPuntualidad);
        TextView tripTv       = vista.findViewById(R.id.txtTrenTrip);
        Button verEst         = vista.findViewById(R.id.btnVerEstacion);

        titulo.setText("Línea " + tren.linea + " a " + tren.destino);
        titulo.setTextColor(tren.lineaColor);

        boolean unidadEsTrip = tren.vehicleId != null
                && tren.vehicleId.equals(tren.tripId);
        if (unidadEsTrip) {
            id.setText("Unidad no disponible · Viaje " + tren.tripId);
        } else {
            id.setText("Unidad " + tren.vehicleId + " · Viaje " + tren.tripId);
        }

        if (destinoTv != null)
            destinoTv.setText(tren.destino == null ? "" : tren.destino);
        if (puntualidadTv != null)
            puntualidadTv.setText("En hora según horario oficial");
        if (tripTv != null)
            tripTv.setText("Servicio regular · Línea " + tren.linea);

        final Handler countdown = new Handler(Looper.getMainLooper());
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

        final int h = getResources().getDisplayMetrics().heightPixels;

        dialog.setOnShowListener(d -> {
            FrameLayout sheet = dialog.findViewById(
                    com.google.android.material.R.id.design_bottom_sheet);
            if (sheet != null) {
                sheet.setBackground(null);
                ViewGroup.LayoutParams lp = sheet.getLayoutParams();
                lp.height = (int) (h * 0.50);
                sheet.setLayoutParams(lp);
            }
        });

        dialog.show();

        BottomSheetBehavior<FrameLayout> b = dialog.getBehavior();
        b.setFitToContents(false);
        b.setSkipCollapsed(true);
        b.setHideable(false);                    // no se puede ocultar con drag del behavior
        b.setDraggable(false);                   // el behavior no intercepta drags
        b.setHalfExpandedRatio(0.50f);
        b.setPeekHeight((int) (h * 0.50));
        b.setExpandedOffset((int) (h * 0.50));
        b.setState(BottomSheetBehavior.STATE_HALF_EXPANDED);
        final View dragHandle = vista.findViewById(R.id.dragHandle);
        final FrameLayout sheet = dialog.findViewById(
                com.google.android.material.R.id.design_bottom_sheet);

        if (dragHandle != null && sheet != null) {
            final float[] startRawY = {0f};
            final float[] startTransY = {0f};
            final float[] dyAcum = {0f};

            dragHandle.setOnTouchListener((v, event) -> {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startRawY[0] = event.getRawY();
                        startTransY[0] = sheet.getTranslationY();
                        dyAcum[0] = 0f;
                        return true;

                    case MotionEvent.ACTION_MOVE: {
                        float dy = event.getRawY() - startRawY[0];
                        dyAcum[0] = dy;
                        if (dy > 0f) {
                            sheet.setTranslationY(startTransY[0] + dy);
                        }
                        return true;
                    }

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (dyAcum[0] > 100f) {
                            sheet.animate()
                                    .translationY(startTransY[0] + sheet.getHeight())
                                    .setDuration(200)
                                    .withEndAction(() -> {
                                        sheet.setTranslationY(startTransY[0]);
                                        dialog.dismiss();
                                    })
                                    .start();
                        } else {
                            sheet.animate()
                                    .translationY(startTransY[0])
                                    .setDuration(200)
                                    .start();
                        }
                        return true;
                }
                return false;
            });
        }
    }

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

    private static class ProximaSalida {
        final int sec;
        final String headsign;
        ProximaSalida(int sec, String headsign) { this.sec = sec; this.headsign = headsign; }
    }

    private static String normalizeId(String id) {
        if (id == null) return "";
        return id.endsWith(".0") ? id.substring(0, id.length() - 2) : id;
    }

    private int color(int res) {
        return ContextCompat.getColor(requireContext(), res);
    }

    private void llenarProximosTrenes(LinearLayout container, String stopNormRaw) {
        if (container == null) return;
        container.removeAllViews();
        salidasProximas.clear();
        badgesProximos.clear();

        if (horarioGestor == null) {
            agregarTextoVacio(container, "Cargando horarios…");
            return;
        }

        String stopNorm = normalizeId(stopNormRaw);
        if (stopNorm.isEmpty()) {
            agregarTextoVacio(container, "Información no disponible");
            return;
        }

        final int ahora = HorarioGestor.ahoraSegundosMadrid();
        final List<ProximaSalida> encontradas = new ArrayList<>();

        List<HorarioGestor.Trip> trips = horarioGestor.getTripsPorServicio()
                .get(horarioGestor.getServicioActivo());
        if (trips == null) trips = new ArrayList<>();

        for (HorarioGestor.Trip t : trips) {
            for (HorarioGestor.StopTime st : t.stops) {
                if (!normalizeId(st.stopId).equals(stopNorm)) continue;
                int delta = st.arrivalSec - ahora;
                if (delta > 3600) continue;
                if (delta < -GRACIA_POST_LLEGADA_SEG) continue;
                encontradas.add(new ProximaSalida(st.arrivalSec,
                        t.headsign == null ? "" : t.headsign));
            }
        }
        Collections.sort(encontradas, (a, b) -> Integer.compare(a.sec, b.sec));

        int total = Math.min(encontradas.size(), MAX_PROXIMOS);
        if (total == 0) {
            agregarTextoVacio(container, "Sin trenes en los próximos 60 min.");
            return;
        }

        LayoutInflater inf = LayoutInflater.from(requireContext());
        for (int i = 0; i < total; i++) {
            ProximaSalida s = encontradas.get(i);
            View fila = inf.inflate(R.layout.item_tren, container, false);
            ((TextView) fila.findViewById(R.id.txtHora)).setText(
                    String.format(Locale.US, "%02d:%02d",
                            (s.sec / 3600) % 24, (s.sec / 60) % 60));
            ((TextView) fila.findViewById(R.id.txtDestinoTren)).setText(s.headsign);

            TextView badge = fila.findViewById(R.id.txtEnMin);
            if (i == 0) {
                badge.setBackgroundResource(R.drawable.bg_badge_solid);
                badge.setTextColor(color(R.color.white));
            }
            salidasProximas.add(s);
            badgesProximos.add(badge);
            container.addView(fila);
        }
        actualizarBadgesProximos();
    }

    private void actualizarBadgesProximos() {
        final int ahora = HorarioGestor.ahoraSegundosMadrid();
        for (int i = 0; i < salidasProximas.size(); i++) {
            ProximaSalida s = salidasProximas.get(i);
            TextView badge = badgesProximos.get(i);
            if (badge == null) continue;

            int restante = s.sec - ahora;
            if (restante > 0) {
                int min = Math.max(1, (restante + 59) / 60);
                badge.setText("en " + min + " min");
            } else {
                badge.setText("en estación");
            }
        }
    }

    private void agregarTextoVacio(LinearLayout container, String texto) {
        TextView tv = new TextView(requireContext());
        tv.setText(texto);
        tv.setTextColor(color(R.color.text_secondary));
        tv.setTextSize(13);
        tv.setPadding(0, 8, 0, 4);
        container.addView(tv);
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
        salidasProximas.clear();
        badgesProximos.clear();
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