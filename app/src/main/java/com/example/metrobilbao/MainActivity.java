package com.example.metrobilbao;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.widget.ViewPager2;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends FragmentActivity {

    private int colorActivo;
    private int colorInactivo;

    private static final int[] TABS  = {R.id.tabMapa,  R.id.tabViaje,  R.id.tabNoticias,  R.id.tabAvisos};
    private static final int[] ICONS = {R.id.iconMapa, R.id.iconViaje, R.id.iconNoticias, R.id.iconAvisos};
    private static final int[] PILLS = {R.id.pillMapa, R.id.pillViaje, R.id.pillNoticias, R.id.pillAvisos};

    static volatile Map<String, List<String>> viajeGrafoCache;
    static volatile List<String> viajeNombresCache;

    private ViewPager2 viewPager;
    private View splashOverlay;
    private TextView splashText;
    private View splashProgress;
    private int currentTab = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        colorActivo   = ContextCompat.getColor(this, R.color.icon_active);
        colorInactivo = ContextCompat.getColor(this, R.color.icon_inactive);

        viewPager       = findViewById(R.id.viewPager);
        splashOverlay   = findViewById(R.id.splashOverlay);
        splashText      = findViewById(R.id.splashText);
        splashProgress  = findViewById(R.id.splashProgress);
        aplicarInsetsNavBar();

        viewPager.setAdapter(new MainPagerAdapter(this));
        viewPager.setOffscreenPageLimit(3);
        viewPager.setUserInputEnabled(false);

        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override public void onPageSelected(int position) { animateToTab(position); }
        });

        for (int i = 0; i < TABS.length; i++) {
            final int idx = i;
            findViewById(TABS[i]).setOnClickListener(v -> {
                viewPager.setCurrentItem(idx, true);
                animateToTab(idx);
            });
        }

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (viewPager.getCurrentItem() != 0) {
                    viewPager.setCurrentItem(0, true);
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

        setTabImmediate(0);
        iniciarAnimacionSplash();

        View navBarContainer = findViewById(R.id.navBarContainer);
        navBarContainer.setTranslationY(120f);
        navBarContainer.setAlpha(0f);
        navBarContainer.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(600)
                .setStartDelay(300)
                .setInterpolator(new OvershootInterpolator(0.8f))
                .start();

        MetroRepository.getInstance().init(getApplicationContext(), new MetroRepository.Callback() {
            @Override public void onReady(MetroRepository repository) {
                precargarViaje(repository, MainActivity.this::ocultarSplash);
            }
            @Override public void onError(Throwable error) { ocultarSplash(); }
        });

        viewPager.postDelayed(this::ocultarSplash, 12000);
    }

    private void aplicarInsetsNavBar() {
        final View navContainer = findViewById(R.id.navBarContainer);
        if (navContainer == null) return;

        final float density = getResources().getDisplayMetrics().density;
        ViewCompat.setOnApplyWindowInsetsListener(navContainer, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
            int nuevoMargen = (int) (28 * density) + bars.bottom;
            if (lp.bottomMargin != nuevoMargen) {
                lp.bottomMargin = nuevoMargen;
                v.setLayoutParams(lp);
            }
            return insets;
        });
    }

    private void iniciarAnimacionSplash() {
        final ImageView logo = findViewById(R.id.splashLogo);
        if (logo == null || splashOverlay == null || splashText == null) {
            android.util.Log.e("Splash", "vistas del splash nulas");
            return;
        }

        splashOverlay.setVisibility(View.VISIBLE);
        splashOverlay.setAlpha(1f);

        logo.setVisibility(View.VISIBLE);
        logo.setAlpha(1f);
        logo.setScaleX(1f);
        logo.setScaleY(1f);

        splashText.setAlpha(1f);

        if (splashProgress != null) splashProgress.setAlpha(1f);
    }

    private void ocultarSplash() {
        if (splashOverlay == null || splashOverlay.getVisibility() != View.VISIBLE) return;

        splashOverlay.animate()
                .alpha(0f)
                .setDuration(400)
                .withEndAction(() -> splashOverlay.setVisibility(View.GONE))
                .start();
    }

    private void precargarViaje(MetroRepository repo, Runnable onDone) {
        new Thread(() -> {
            try {
                HorarioGestor hg = repo.getHorarioGestor();
                Map<String, List<String>> g = new HashMap<>();
                for (HorarioGestor.Trip trip : hg.getTodosLosTrips().values()) {
                    List<HorarioGestor.StopTime> s = trip.stops;
                    for (int i = 0; i < s.size() - 1; i++) {
                        String a = norm(s.get(i).stopId);
                        String b = norm(s.get(i + 1).stopId);
                        if (a.isEmpty() || b.isEmpty() || a.equals(b)) continue;
                        g.computeIfAbsent(a, k -> new ArrayList<>()).add(b);
                        g.computeIfAbsent(b, k -> new ArrayList<>()).add(a);
                    }
                }
                List<String> nombres = new ArrayList<>();
                for (MetroGestor.Station st : repo.getEstaciones()) nombres.add(st.name);

                viajeNombresCache = nombres;
                viajeGrafoCache   = g;
            } catch (Throwable t) {
                android.util.Log.e("MainActivity", "precargarViaje", t);
                if (viajeGrafoCache   == null) viajeGrafoCache   = new HashMap<>();
                if (viajeNombresCache == null) viajeNombresCache = new ArrayList<>();
            } finally {
                runOnUiThread(onDone);
            }
        }, "preload-viaje").start();
    }

    private static String norm(String id) {
        if (id == null) return "";
        return id.endsWith(".0") ? id.substring(0, id.length() - 2) : id;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    private void setTabImmediate(int position) {
        for (int i = 0; i < TABS.length; i++) {
            boolean activo = i == position;
            ImageView icon = findViewById(ICONS[i]);
            View pill = findViewById(PILLS[i]);
            if (icon != null) {
                icon.setColorFilter(activo ? colorActivo : colorInactivo);
                icon.setScaleX(activo ? 1.15f : 1.0f);
                icon.setScaleY(activo ? 1.15f : 1.0f);
            }
            if (pill != null) pill.setAlpha(activo ? 1.0f : 0.0f);
        }
        currentTab = position;
    }

    private void animateToTab(int position) {
        if (position == currentTab) return;

        for (int i = 0; i < TABS.length; i++) {
            ImageView icon = findViewById(ICONS[i]);
            View pill = findViewById(PILLS[i]);
            boolean activo = i == position;
            boolean wasActive = i == currentTab;

            if (icon != null) {
                int fromColor = wasActive ? colorActivo : colorInactivo;
                int toColor   = activo   ? colorActivo : colorInactivo;
                if (fromColor != toColor) {
                    ValueAnimator colorAnim = ValueAnimator.ofObject(
                            new ArgbEvaluator(), fromColor, toColor);
                    colorAnim.setDuration(250);
                    colorAnim.addUpdateListener(a -> icon.setColorFilter((int) a.getAnimatedValue()));
                    colorAnim.start();
                }

                float targetScale = activo ? 1.15f : 1.0f;
                createSpring(icon, DynamicAnimation.SCALE_X, targetScale).start();
                createSpring(icon, DynamicAnimation.SCALE_Y, targetScale).start();
            }

            if (pill != null) {
                pill.animate()
                        .alpha(activo ? 1.0f : 0.0f)
                        .setDuration(activo ? 300 : 200)
                        .start();

                if (activo) {
                    pill.setScaleX(0.5f);
                    pill.setScaleY(0.5f);
                    pill.animate()
                            .scaleX(1.0f)
                            .scaleY(1.0f)
                            .setDuration(350)
                            .setInterpolator(new OvershootInterpolator(1.5f))
                            .start();
                }
            }
        }

        currentTab = position;
    }

    private SpringAnimation createSpring(View view,
                                         DynamicAnimation.ViewProperty property,
                                         float target) {
        SpringForce spring = new SpringForce(target)
                .setDampingRatio(SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY)
                .setStiffness(SpringForce.STIFFNESS_LOW);
        return new SpringAnimation(view, property).setSpring(spring);
    }
}