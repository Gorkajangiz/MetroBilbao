package com.example.metrobilbao;

import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class NoticiasFragment extends Fragment {

    private static final String TAG = "MetroTren";
    private static final String RSS_URL =
            "https://cms.metrobilbao.eus/get/open_data/rss/noticias/es";

    private NoticiasAdapter adapter;
    private final List<NewsItem> noticias = new ArrayList<>();
    private ProgressBar progress;
    private TextView vacio;
    private boolean cargado = false;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater i, @Nullable ViewGroup c, @Nullable Bundle b) {
        return i.inflate(R.layout.fragment_noticias, c, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        RecyclerView rv = v.findViewById(R.id.recyclerNoticias);
        progress = v.findViewById(R.id.progressNoticias);
        vacio = v.findViewById(R.id.txtVacioNoticias);

        rv.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new NoticiasAdapter(noticias, this::mostrarDetalle);
        rv.setAdapter(adapter);

        // FIX #8: no recargar al rotar
        if (savedInstanceState != null) cargado = savedInstanceState.getBoolean("cargado", false);

        if (!cargado) {
            cargarAsync();
        } else {
            progress.setVisibility(View.GONE);
            if (noticias.isEmpty()) vacio.setVisibility(View.VISIBLE);
        }
    }

    @Override public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("cargado", cargado);
    }

    @Override public void onDestroyView() {
        executor.shutdownNow();
        super.onDestroyView();
    }

    private void mostrarDetalle(NewsItem item) {
        com.google.android.material.bottomsheet.BottomSheetDialog dialog =
                new com.google.android.material.bottomsheet.BottomSheetDialog(requireContext());
        View vista = getLayoutInflater().inflate(R.layout.display_noticia, null);

        TextView titulo  = vista.findViewById(R.id.detailTituloNoticia);
        TextView fecha   = vista.findViewById(R.id.detailFechaNoticia);
        TextView desc    = vista.findViewById(R.id.detailDescNoticia);
        ImageView img    = vista.findViewById(R.id.detailImgNoticia);
        View imgCard     = vista.findViewById(R.id.detailImgCard);
        Button btnLink   = vista.findViewById(R.id.detailBtnLink);

        titulo.setText(item.titulo);
        fecha.setText(item.fecha == null ? "" : item.fecha);
        desc.setText(item.descripcion == null ? "" : item.descripcion);

        // Imagen opcional
        if (item.imagenUrl != null && !item.imagenUrl.isEmpty()) {
            imgCard.setVisibility(View.VISIBLE);
            Glide.with(requireContext())
                    .load(item.imagenUrl)
                    .placeholder(android.R.color.darker_gray)
                    .error(android.R.color.darker_gray)
                    .into(img);
        } else {
            imgCard.setVisibility(View.GONE);
        }

        // Link opcional a la noticia completa en el navegador
        if (item.link != null && !item.link.isEmpty()) {
            btnLink.setVisibility(View.VISIBLE);
            btnLink.setOnClickListener(v -> {
                try {
                    startActivity(new android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(item.link)));
                } catch (Exception e) {
                    Toast.makeText(requireContext(),
                            "No se pudo abrir el enlace", Toast.LENGTH_SHORT).show();
                }
            });
        } else {
            btnLink.setVisibility(View.GONE);
        }

        dialog.setContentView(vista);
        if (dialog.getWindow() != null)
            dialog.getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));

        int h = getResources().getDisplayMetrics().heightPixels;
        dialog.show();

        com.google.android.material.bottomsheet.BottomSheetBehavior<android.widget.FrameLayout> b =
                dialog.getBehavior();
        b.setFitToContents(false);
        b.setHalfExpandedRatio(0.85f);
        b.setExpandedOffset((int) (h * 0.10f));
        b.setPeekHeight((int) (h * 0.85f));
        b.setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_HALF_EXPANDED);

        View bottomSheet = dialog.findViewById(
                com.google.android.material.R.id.design_bottom_sheet);
        if (bottomSheet != null) bottomSheet.setBackgroundColor(android.graphics.Color.TRANSPARENT);
    }

    // FIX #8: AsyncTask → ExecutorService
    private void cargarAsync() {
        progress.setVisibility(View.VISIBLE);
        executor.execute(() -> {
            List<NewsItem> lista = new ArrayList<>();
            String error = null;
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(RSS_URL).openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.setRequestProperty("User-Agent", "MetroBilbaoApp/1.0");
                if (conn.getResponseCode() != 200) {
                    error = "HTTP " + conn.getResponseCode();
                } else {
                    try (InputStream in = conn.getInputStream()) {
                        lista = parsearRss(in);
                    }
                }
            } catch (Throwable t) {
                android.util.Log.e(TAG, "Error noticias", t);
                error = t.getMessage();
            } finally {
                if (conn != null) conn.disconnect();
            }
            final String errF = error;
            final List<NewsItem> listaF = lista;
            mainHandler.post(() -> {
                cargado = true;
                if (!isAdded() || progress == null) return;
                progress.setVisibility(View.GONE);
                noticias.clear();
                noticias.addAll(listaF);
                adapter.notifyDataSetChanged();
                if (listaF.isEmpty()) {
                    vacio.setVisibility(View.VISIBLE);
                    if (errF != null) Toast.makeText(requireContext(),
                            "No se pudieron cargar noticias: " + errF,
                            Toast.LENGTH_LONG).show();
                } else {
                    vacio.setVisibility(View.GONE);
                }
            });
        });
    }

    // FIX #4: parser RSS robusto (CDATA, HTML anidado, type de enclosure)
    private List<NewsItem> parsearRss(InputStream in) throws Exception {
        List<NewsItem> lista = new ArrayList<>();
        XmlPullParser p = XmlPullParserFactory.newInstance().newPullParser();
        p.setInput(in, null);
        int ev = p.getEventType();
        NewsItem cur = null;
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                String tag = p.getName();
                if (tag.equals("item") || tag.equals("entry")) {
                    cur = new NewsItem();
                } else if (cur != null) {
                    if (tag.equals("title")) {
                        cur.titulo = leerTextoCompleto(p).trim();
                    } else if (tag.equals("description") || tag.equals("summary")) {
                        String raw = leerTextoCompleto(p);
                        cur.descripcion = Html.fromHtml(raw,
                                Html.FROM_HTML_MODE_LEGACY).toString().trim();
                    } else if (tag.equals("pubDate") || tag.equals("updated")) {
                        cur.fecha = formatearFecha(leerTextoCompleto(p).trim());   // <-- formateado aquí
                    } else if (tag.equals("link")) {
                        String link = leerTextoCompleto(p).trim();
                        if (cur.link == null || cur.link.isEmpty()) cur.link = link;
                    } else if (tag.equals("image")) {
                        String urlAttr = p.getAttributeValue(null, "url");
                        if (urlAttr != null && !urlAttr.isEmpty()) {
                            cur.imagenUrl = urlAttr;
                        } else {
                            String img = leerTextoCompleto(p).trim();
                            if (img.startsWith("http")) cur.imagenUrl = img;
                        }
                    } else if (tag.equals("enclosure")) {
                        // FIX #4: solo aceptar imágenes
                        String u = p.getAttributeValue(null, "url");
                        String type = p.getAttributeValue(null, "type");
                        if (u != null && !u.isEmpty()
                                && (type == null || type.startsWith("image/"))) {
                            cur.imagenUrl = u;
                        }
                    }
                }
            } else if (ev == XmlPullParser.END_TAG) {
                String tag = p.getName();
                if ((tag.equals("item") || tag.equals("entry")) && cur != null) {
                    if (cur.titulo != null && !cur.titulo.isEmpty()) lista.add(cur);
                    cur = null;
                }
            }
            ev = p.next();
        }
        return lista;
    }

    /** Lee todo el texto interior de un tag (incluye CDATA y HTML anidado) hasta su END_TAG. */
    private static String leerTextoCompleto(XmlPullParser p) throws Exception {
        StringBuilder sb = new StringBuilder();
        int startDepth = p.getDepth();
        while (true) {
            int ev = p.next();
            if (ev == XmlPullParser.END_DOCUMENT) break;

            // FIX: break al cerrar EXACTAMENTE el tag del que partimos,
            // no cuando bajamos de profundidad (que era el bug).
            if (ev == XmlPullParser.END_TAG && p.getDepth() == startDepth) break;

            if (ev == XmlPullParser.TEXT || ev == XmlPullParser.CDSECT) {
                sb.append(p.getText());
            } else if (ev == XmlPullParser.START_TAG) {
                sb.append('<').append(p.getName()).append('>');
            } else if (ev == XmlPullParser.END_TAG) {
                // Tags anidados sin CDATA (raro en RSS, pero por si acaso)
                sb.append("</").append(p.getName()).append('>');
            }
        }
        return sb.toString();
    }
    private static String formatearFecha(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "";
        String s = raw.trim();
        String[] patterns = {
                "EEE, dd MMM yyyy HH:mm:ss Z",     // RFC 822 (típico RSS)
                "EEE, dd MMM yyyy HH:mm:ss z",
                "yyyy-MM-dd'T'HH:mm:ssXXX",        // ISO 8601 con offset
                "yyyy-MM-dd'T'HH:mm:ss'Z'",        // ISO 8601 UTC
                "yyyy-MM-dd HH:mm:ss",
                "yyyy-MM-dd"
        };
        for (String pat : patterns) {
            try {
                java.text.SimpleDateFormat in =
                        new java.text.SimpleDateFormat(pat, java.util.Locale.US);
                java.util.Date d = in.parse(s);
                java.text.SimpleDateFormat out =
                        new java.text.SimpleDateFormat("d MMM yyyy · HH:mm",
                                new java.util.Locale("es", "ES"));
                return out.format(d);
            } catch (Exception ignored) {}
        }
        return s;
    }

}