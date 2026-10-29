package com.example.metrobilbao;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatAutoCompleteTextView;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButtonToggleGroup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

public class ViajeFragment extends Fragment {

    private static final int MIN_POR_PARADA = 2;
    private static final int MAX_SALIDAS = 8;
    private static final int INTERMEDIAS_SIN_COLAPSAR = 3;

    private static final int TIPO_ORIGEN = 0, TIPO_INTERMEDIA = 1, TIPO_DESTINO = 2, TIPO_TOGGLE = 3;

    private ProgressBar progressViaje;
    private AppCompatAutoCompleteTextView spinnerOrigen, spinnerDestino;
    private MetroGestor.Station origenSeleccionado, destinoSeleccionado;

    private Button btnConsultar;
    private View btnIntercambiar, cardResultado, cardTarifas, detalleRuta, cardProximos;
    private TextView txtRuta, txtPrecio, txtPrecioIndividual, txtTiempo, txtProximosEstacion;
    private ViewGroup containerRuta, containerProximos;

    private MaterialButtonToggleGroup togglePerfil;
    private int perfilSeleccionado = 0; // 0=Ordinario, 1=F20, 2=F50
    private String ultimoIdO, ultimoIdD;

    private List<MetroGestor.Station> estaciones;
    private HorarioGestor horarioGestor;
    private Map<String, List<String>> grafoEstaciones;

    private static class Salida {
        final int sec;
        final String headsign;
        Salida(int sec, String headsign) { this.sec = sec; this.headsign = headsign; }
    }

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater i, @Nullable ViewGroup c, @Nullable Bundle b) {
        return i.inflate(R.layout.fragment_viaje, c, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        spinnerOrigen       = v.findViewById(R.id.spinnerOrigen);
        spinnerDestino      = v.findViewById(R.id.spinnerDestino);
        btnConsultar        = v.findViewById(R.id.btnConsultarViaje);
        btnIntercambiar     = v.findViewById(R.id.btnIntercambiar);
        progressViaje       = v.findViewById(R.id.progressViaje);
        cardResultado       = v.findViewById(R.id.cardResultado);
        cardTarifas         = v.findViewById(R.id.cardTarifas);
        detalleRuta         = v.findViewById(R.id.detalleRuta);
        cardProximos        = v.findViewById(R.id.cardProximos);
        txtRuta             = v.findViewById(R.id.txtRutaResultado);
        txtTiempo           = v.findViewById(R.id.txtTiempo);
        txtPrecio           = v.findViewById(R.id.txtPrecio);
        txtPrecioIndividual = v.findViewById(R.id.txtPrecioIndividual);
        txtProximosEstacion = v.findViewById(R.id.txtProximosEstacion);
        containerRuta       = v.findViewById(R.id.containerRuta);
        containerProximos   = v.findViewById(R.id.containerProximos);

        progressViaje.setVisibility(View.GONE);
        btnConsultar.setEnabled(false);
        btnIntercambiar.setEnabled(false);

        btnConsultar.setOnClickListener(x -> consultarViaje());
        btnIntercambiar.setOnClickListener(x -> intercambiar());

        togglePerfil = v.findViewById(R.id.togglePerfil);
        if (togglePerfil != null) {
            perfilSeleccionado = 0;
            togglePerfil.check(R.id.btnPerfilOrdinario);

            togglePerfil.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
                if (!isChecked) return;
                if (checkedId == R.id.btnPerfilF20) perfilSeleccionado = 1;
                else if (checkedId == R.id.btnPerfilF50) perfilSeleccionado = 2;
                else perfilSeleccionado = 0;
                if (ultimoIdO != null && ultimoIdD != null) actualizarTarjetas();
            });
        }

        View btnVerTarifas = v.findViewById(R.id.btnVerTarifas);
        if (btnVerTarifas != null) btnVerTarifas.setOnClickListener(x -> mostrarDialogoTodosTitulos());

        cargarDatosEnSegundoPlano();
    }

    private static String normalize(String id) {
        if (id == null) return "";
        return id.endsWith(".0") ? id.substring(0, id.length() - 2) : id;
    }

    private void cargarDatosEnSegundoPlano() {
        MetroRepository.getInstance().init(requireContext(), new MetroRepository.Callback() {
            @Override public void onReady(MetroRepository repo) {
                if (!isAdded()) return;
                estaciones = repo.getEstaciones();
                horarioGestor = repo.getHorarioGestor();

                new Thread(() -> {
                    Map<String, List<String>> g = MainActivity.viajeGrafoCache;
                    List<String> nombres = MainActivity.viajeNombresCache;

                    if (g == null) {
                        g = new HashMap<>();
                        for (HorarioGestor.Trip trip : horarioGestor.getTodosLosTrips().values()) {
                            List<HorarioGestor.StopTime> s = trip.stops;
                            for (int i = 0; i < s.size() - 1; i++) {
                                String a = normalize(s.get(i).stopId);
                                String b = normalize(s.get(i + 1).stopId);
                                if (a.isEmpty() || b.isEmpty() || a.equals(b)) continue;
                                g.computeIfAbsent(a, k -> new ArrayList<>()).add(b);
                                g.computeIfAbsent(b, k -> new ArrayList<>()).add(a);
                            }
                        }
                    }
                    if (nombres == null) {
                        nombres = new ArrayList<>();
                        for (MetroGestor.Station s : estaciones) nombres.add(s.name);
                    }

                    final Map<String, List<String>> gF = g;
                    final List<String> nF = nombres;
                    if (!isAdded()) return;
                    requireActivity().runOnUiThread(() -> {
                        if (!isAdded()) return;
                        grafoEstaciones = gF;
                        btnConsultar.setEnabled(true);
                        btnIntercambiar.setEnabled(true);
                        configurarSpinners(nF);
                    });
                }, "ViajeFragment-init").start();
            }

            @Override public void onError(Throwable t) {
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> {
                    if (!isAdded()) return;
                    new AlertDialog.Builder(requireContext())
                            .setTitle("Error")
                            .setMessage("No se pudieron cargar los datos:\n" + t.getMessage())
                            .setPositiveButton("OK", null).show();
                });
            }
        });
    }

    private void configurarSpinners(List<String> nombres) {
        List<String> ordenados = new ArrayList<>(nombres);
        Collections.sort(ordenados, String.CASE_INSENSITIVE_ORDER);

        spinnerOrigen.setAdapter(new EstacionesAdapter(
                requireContext(), R.layout.item_spinner_dropdown, ordenados));
        spinnerDestino.setAdapter(new EstacionesAdapter(
                requireContext(), R.layout.item_spinner_dropdown, ordenados));

        setupAutoComplete(spinnerOrigen, R.id.containerOrigen, true);
        setupAutoComplete(spinnerDestino, R.id.containerDestino, false);
    }

    private void setupAutoComplete(AppCompatAutoCompleteTextView field,
                                   int containerId,
                                   boolean isOrigen) {
        View container = requireView().findViewById(containerId);

        container.post(() -> field.setDropDownWidth(container.getWidth()));

        View.OnClickListener abrir = v -> {
            field.requestFocus();
            field.showDropDown();
        };
        container.setOnClickListener(abrir);
        field.setOnClickListener(abrir);

        field.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                field.post(field::showDropDown);
            } else {
                MetroGestor.Station sel = isOrigen ? origenSeleccionado : destinoSeleccionado;
                if (sel != null && !sel.name.equals(field.getText().toString().trim())) {
                    if (isOrigen) origenSeleccionado = null;
                    else destinoSeleccionado = null;
                }
            }
        });

        field.setOnItemClickListener((parent, view, position, id) -> {
            String nombre = (String) parent.getItemAtPosition(position);
            MetroGestor.Station est = buscarEstacionPorNombre(nombre);
            if (isOrigen) origenSeleccionado = est;
            else destinoSeleccionado = est;
        });
    }

    private MetroGestor.Station buscarEstacionPorNombre(String nombre) {
        if (nombre == null) return null;
        for (MetroGestor.Station s : estaciones) {
            if (s.name.equals(nombre)) return s;
        }
        return null;
    }

    private MetroGestor.Station resolverEstacion(AppCompatAutoCompleteTextView view,
                                                 MetroGestor.Station guardada) {
        if (guardada != null) return guardada;
        String txt = view.getText().toString().trim();
        if (txt.isEmpty()) return null;
        return buscarEstacionPorNombre(txt);
    }

    private void intercambiar() {
        String textoO = spinnerOrigen.getText().toString();
        String textoD = spinnerDestino.getText().toString();
        spinnerOrigen.setText(textoD, false);
        spinnerDestino.setText(textoO, false);

        MetroGestor.Station tmp = origenSeleccionado;
        origenSeleccionado = destinoSeleccionado;
        destinoSeleccionado = tmp;
    }

    private void consultarViaje() {
        MetroGestor.Station o = resolverEstacion(spinnerOrigen, origenSeleccionado);
        MetroGestor.Station d = resolverEstacion(spinnerDestino, destinoSeleccionado);

        if (o == null || d == null) {
            Toast.makeText(requireContext(),
                    "Elige origen y destino de la lista", Toast.LENGTH_SHORT).show();
            return;
        }
        if (o.parentId != null && o.parentId.equals(d.parentId)) {
            Toast.makeText(requireContext(),
                    "Elige origen y destino distintos", Toast.LENGTH_SHORT).show();
            return;
        }

        String idO = normalize(o.parentId), idD = normalize(d.parentId);

        cardResultado.setVisibility(View.VISIBLE);
        List<String> ruta = bfs(idO, idD);
        if (ruta == null || ruta.size() < 2) {
            txtRuta.setText("No se ha encontrado ruta entre estas estaciones.");
            detalleRuta.setVisibility(View.GONE);
            cardTarifas.setVisibility(View.GONE);
            cardProximos.setVisibility(View.GONE);
            return;
        }

        detalleRuta.setVisibility(View.VISIBLE);
        cardTarifas.setVisibility(View.VISIBLE);
        txtRuta.setText(o.name + "  →  " + d.name);
        txtTiempo.setText(((ruta.size() - 1) * MIN_POR_PARADA) + " min");

        ultimoIdO = idO;
        ultimoIdD = idD;
        actualizarTarjetas();

        pintarRuta(ruta);
        mostrarProximosTrenes(idO, o.name);
    }

    private void actualizarTarjetas() {
        if (ultimoIdO == null || ultimoIdD == null) return;
        MetroRepository repo = MetroRepository.getInstance();
        Context ctx = requireContext();

        double ocasional;
        if (perfilSeleccionado == 1) ocasional = repo.getPrecioOcasionalF20(ctx, ultimoIdO, ultimoIdD);
        else if (perfilSeleccionado == 2) ocasional = repo.getPrecioOcasionalF50(ctx, ultimoIdO, ultimoIdD);
        else ocasional = repo.getPrecioOcasional(ultimoIdO, ultimoIdD);

        double barik;
        if (perfilSeleccionado == 1) barik = repo.getPrecioBarikF20(ctx, ultimoIdO, ultimoIdD);
        else if (perfilSeleccionado == 2) barik = repo.getPrecioBarikF50(ctx, ultimoIdO, ultimoIdD);
        else barik = repo.getPrecioBarik(ctx, ultimoIdO, ultimoIdD);

        txtPrecio.setText(fmt(ocasional));
        txtPrecioIndividual.setText(fmt(barik));
    }

    private static String fmt(double v) {
        if (v < 0) return "—";
        return String.format(new Locale("es", "ES"), "%.2f €", v);
    }

    private void mostrarDialogoTodosTitulos() {
        if (!isAdded()) return;

        Dialog dialog = new Dialog(requireContext(), R.style.Theme_MetroBilbao_Dialog);
        View vista = getLayoutInflater().inflate(R.layout.dialog_todos_titulos, null);

        TextView txtZonas = vista.findViewById(R.id.txtZonasDialogo);
        TextView txtContenido = vista.findViewById(R.id.txtContenidoDialogo);
        Button btnCerrar = vista.findViewById(R.id.btnCerrarDialogo);

        if (ultimoIdO != null && ultimoIdD != null) {
            int z = MetroRepository.getInstance().getZonasCruzadas(ultimoIdO, ultimoIdD);
            txtZonas.setText(z > 0 ? z + " zona" + (z != 1 ? "s" : "") : "—");
            txtContenido.setText(construirTextoDialogo());
        } else {
            txtZonas.setText("Sin ruta");
            txtContenido.setText(
                    "Busca primero una ruta para ver las tarifas aplicables a tu trayecto.\n\n" +
                            "Las tarifas varían según las zonas cruzadas (1, 2 o 3) y el perfil de la tarjeta (Ordinario, F20 o F50).");
        }

        btnCerrar.setOnClickListener(v -> dialog.dismiss());

        dialog.setContentView(vista);
        dialog.setCancelable(true);
        dialog.show();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int width = (int) (getResources().getDisplayMetrics().widthPixels * 0.90);
            int height = (int) (getResources().getDisplayMetrics().heightPixels * 0.75);
            dialog.getWindow().setLayout(width, height);
        }
    }

    private String construirTextoDialogo() {
        StringBuilder sb = new StringBuilder();
        MetroRepository repo = MetroRepository.getInstance();
        Context ctx = requireContext();
        int z = repo.getZonasCruzadas(ultimoIdO, ultimoIdD);

        sb.append("Zonas cruzadas: ").append(z).append("\n\n");

        sb.append("OCASIONAL\n");
        sb.append("  Ordinario: ").append(fmt(repo.getPrecioOcasional(ultimoIdO, ultimoIdD))).append("\n");
        sb.append("  F20: ").append(fmt(repo.getPrecioOcasionalF20(ctx, ultimoIdO, ultimoIdD))).append("\n");
        sb.append("  F50: ").append(fmt(repo.getPrecioOcasionalF50(ctx, ultimoIdO, ultimoIdD))).append("\n\n");

        sb.append("BARIK CREDITRANS\n");
        sb.append("  Ordinario: ").append(fmt(repo.getPrecioBarik(ctx, ultimoIdO, ultimoIdD))).append("\n");
        sb.append("  F20: ").append(fmt(repo.getPrecioBarikF20(ctx, ultimoIdO, ultimoIdD))).append("\n");
        sb.append("  F50: ").append(fmt(repo.getPrecioBarikF50(ctx, ultimoIdO, ultimoIdD))).append("\n\n");

        sb.append("GIZATRANS (por viaje)\n");
        sb.append("  Ordinario: ").append(fmt(repo.getPrecioGizatrans(ctx, 0))).append("\n");
        sb.append("  F20: ").append(fmt(repo.getPrecioGizatrans(ctx, 1))).append("\n");
        sb.append("  F50: ").append(fmt(repo.getPrecioGizatrans(ctx, 2))).append("\n\n");

        sb.append("ABONOS TEMPORALES (").append(z).append(" zona(s))\n");
        sb.append("  Bidai 50: ").append(fmtAbono(ctx, R.array.bidai50_ordinario, z)).append("\n");
        sb.append("  Bidai 70: ").append(fmtAbono(ctx, R.array.bidai70_ordinario, z)).append("\n");
        sb.append("  Bidai Oro: ").append(fmtAbono(ctx, R.array.bidaiOro_ordinario, z)).append("\n");
        sb.append("  Gazte 70: ").append(fmtAbono(ctx, R.array.gazte70_ordinario, z)).append("\n");
        sb.append("  Gazte Oro: ").append(fmtAbono(ctx, R.array.gazteOro_ordinario, z)).append("\n");

        return sb.toString();
    }

    private String fmtAbono(Context ctx, int arrayRes, int zonas) {
        if (zonas <= 0) return "—";
        String[] arr = ctx.getResources().getStringArray(arrayRes);
        if (zonas - 1 >= arr.length) return "—";
        return arr[zonas - 1].replace(".", ",") + " €";
    }

    private List<String> bfs(String ini, String fin) {
        if (ini.isEmpty() || fin.isEmpty()) return null;
        Queue<String> q = new LinkedList<>();
        Map<String, String> padres = new HashMap<>();
        Set<String> vis = new HashSet<>();
        q.add(ini); vis.add(ini);
        while (!q.isEmpty()) {
            String a = q.poll();
            if (a.equals(fin)) {
                LinkedList<String> c = new LinkedList<>();
                String p = fin; while (p != null) { c.addFirst(p); p = padres.get(p); }
                return c;
            }
            List<String> vec = grafoEstaciones.get(a);
            if (vec == null) continue;
            for (String v : vec) if (!vis.contains(v)) {
                vis.add(v); padres.put(v, a); q.add(v);
            }
        }
        return null;
    }

    private void pintarRuta(List<String> ruta) {
        containerRuta.removeAllViews();
        LayoutInflater inf = LayoutInflater.from(requireContext());
        int n = ruta.size();
        int intermedias = n - 2;

        containerRuta.addView(filaParada(inf, nombre(ruta.get(0)), TIPO_ORIGEN, true, false));

        if (intermedias > 0 && intermedias <= INTERMEDIAS_SIN_COLAPSAR) {
            for (int i = 1; i < n - 1; i++)
                containerRuta.addView(filaParada(inf, nombre(ruta.get(i)), TIPO_INTERMEDIA, false, false));
        } else if (intermedias > INTERMEDIAS_SIN_COLAPSAR) {
            final String textoCerrado = "Ver " + intermedias + " paradas intermedias";
            View toggle = filaParada(inf, textoCerrado, TIPO_TOGGLE, false, false);
            containerRuta.addView(toggle);
            final List<View> ocultas = new ArrayList<>();
            for (int i = 1; i < n - 1; i++) {
                View f = filaParada(inf, nombre(ruta.get(i)), TIPO_INTERMEDIA, false, false);
                f.setVisibility(View.GONE);
                containerRuta.addView(f);
                ocultas.add(f);
            }
            final boolean[] abierto = {false};
            toggle.setOnClickListener(v -> {
                abierto[0] = !abierto[0];
                for (View f : ocultas) f.setVisibility(abierto[0] ? View.VISIBLE : View.GONE);
                ((TextView) v.findViewById(R.id.txtNombreParada))
                        .setText(abierto[0] ? "Ocultar paradas intermedias" : textoCerrado);
            });
        }

        containerRuta.addView(filaParada(inf, nombre(ruta.get(n - 1)), TIPO_DESTINO, false, true));
    }

    private String nombre(String stopId) {
        return horarioGestor.getNombreParada(stopId);
    }

    private View filaParada(LayoutInflater inf, String texto, int tipo, boolean primera, boolean ultima) {
        View fila = inf.inflate(R.layout.item_parada, containerRuta, false);
        View lineTop = fila.findViewById(R.id.lineTop);
        View lineBottom = fila.findViewById(R.id.lineBottom);
        ImageView dot = fila.findViewById(R.id.imgDot);
        TextView txt = fila.findViewById(R.id.txtNombreParada);

        lineTop.setVisibility(primera ? View.INVISIBLE : View.VISIBLE);
        lineBottom.setVisibility(ultima ? View.INVISIBLE : View.VISIBLE);
        txt.setText(texto);

        switch (tipo) {
            case TIPO_ORIGEN:
                dot.setImageResource(R.drawable.dot_origen);
                txt.setTextColor(color(R.color.text_primary));
                txt.setTypeface(txt.getTypeface(), android.graphics.Typeface.BOLD);
                break;
            case TIPO_DESTINO:
                dot.setImageResource(R.drawable.dot_destino);
                txt.setTextColor(color(R.color.text_primary));
                txt.setTypeface(txt.getTypeface(), android.graphics.Typeface.BOLD);
                break;
            case TIPO_TOGGLE:
                dot.setImageResource(R.drawable.dot_mid);
                txt.setTextColor(color(R.color.metro_orange));
                txt.setTypeface(txt.getTypeface(), android.graphics.Typeface.BOLD);
                break;
            default:
                dot.setImageResource(R.drawable.dot_mid);
                txt.setTextColor(color(R.color.text_secondary));
        }
        return fila;
    }

    private int color(int res) {
        return ContextCompat.getColor(requireContext(), res);
    }

    private void mostrarProximosTrenes(String stopNorm, String nombreEstacion) {
        int ahora = HorarioGestor.ahoraSegundosMadrid();
        List<Salida> salidas = new ArrayList<>();
        List<HorarioGestor.Trip> ts = horarioGestor.getTripsPorServicio()
                .get(horarioGestor.getServicioActivo());
        if (ts == null) ts = new ArrayList<>();
        for (HorarioGestor.Trip t : ts)
            for (HorarioGestor.StopTime st : t.stops) {
                if (!normalize(st.stopId).equals(stopNorm)) continue;
                if (st.arrivalSec <= ahora) continue;
                if (st.arrivalSec - ahora > 3600) continue;
                salidas.add(new Salida(st.arrivalSec, t.headsign == null ? "" : t.headsign));
            }
        Collections.sort(salidas, (a, b) -> Integer.compare(a.sec, b.sec));

        txtProximosEstacion.setText("Desde " + nombreEstacion + " · próximos 60 min");
        containerProximos.removeAllViews();
        cardProximos.setVisibility(View.VISIBLE);

        if (salidas.isEmpty()) {
            TextView vacio = new TextView(requireContext());
            vacio.setText("Sin trenes en los próximos 60 min.");
            vacio.setTextColor(color(R.color.text_secondary));
            vacio.setTextSize(15);
            vacio.setPadding(0, 12, 0, 4);
            containerProximos.addView(vacio);
            return;
        }

        LayoutInflater inf = LayoutInflater.from(requireContext());
        int total = Math.min(salidas.size(), MAX_SALIDAS);
        for (int i = 0; i < total; i++) {
            Salida s = salidas.get(i);
            View fila = inf.inflate(R.layout.item_tren, containerProximos, false);
            ((TextView) fila.findViewById(R.id.txtHora)).setText(
                    String.format(Locale.US, "%02d:%02d", (s.sec / 3600) % 24, (s.sec / 60) % 60));
            ((TextView) fila.findViewById(R.id.txtDestinoTren)).setText(s.headsign);
            TextView badge = fila.findViewById(R.id.txtEnMin);
            int min = Math.max(1, (s.sec - ahora + 59) / 60);
            badge.setText("en " + min + " min");
            if (i == 0) {
                badge.setBackgroundResource(R.drawable.bg_badge_solid);
                badge.setTextColor(color(R.color.white));
            }
            containerProximos.addView(fila);
        }
    }

    private static class EstacionesAdapter extends BaseAdapter implements Filterable {

        private final LayoutInflater inflater;
        private final int itemLayout;
        private final List<String> original;
        private final List<String> visible;
        private final ArrayFilter filter = new ArrayFilter();

        EstacionesAdapter(Context ctx, int itemLayout, List<String> items) {
            this.inflater = LayoutInflater.from(ctx);
            this.itemLayout = itemLayout;
            this.original = new ArrayList<>(items);
            this.visible = new ArrayList<>(items);
        }

        @Override public int getCount() { return visible.size(); }
        @Override public String getItem(int pos) { return visible.get(pos); }
        @Override public long getItemId(int pos) { return pos; }

        @Override
        public View getView(int pos, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = inflater.inflate(itemLayout, parent, false);
            }
            ((TextView) convertView).setText(visible.get(pos));
            return convertView;
        }

        @Override public Filter getFilter() { return filter; }

        private class ArrayFilter extends Filter {
            @Override
            protected FilterResults performFiltering(CharSequence constraint) {
                FilterResults results = new FilterResults();
                List<String> result = new ArrayList<>();
                if (constraint == null || constraint.length() == 0) {
                    result.addAll(original);
                } else {
                    String q = constraint.toString().toLowerCase(Locale.getDefault());
                    for (String s : original) {
                        if (s.toLowerCase(Locale.getDefault()).contains(q)) {
                            result.add(s);
                        }
                    }
                }
                results.values = result;
                results.count = result.size();
                return results;
            }

            @Override @SuppressWarnings("unchecked")
            protected void publishResults(CharSequence constraint, FilterResults results) {
                visible.clear();
                if (results.values != null) {
                    visible.addAll((List<String>) results.values);
                }
                if (results.count > 0) notifyDataSetChanged();
                else notifyDataSetInvalidated();
            }
        }
    }
}