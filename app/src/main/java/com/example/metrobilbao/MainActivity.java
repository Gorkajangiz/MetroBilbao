package com.example.metrobilbao;

import android.animation.AnimatorSet;
import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.os.Bundle;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;

import androidx.activity.OnBackPressedCallback;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.widget.ViewPager2;

public class MainActivity extends FragmentActivity {

    // iOS-style accent color (Metro Bilbao orange) and muted inactive
    private static final int COLOR_ACTIVO   = 0xFFFF6512;
    private static final int COLOR_INACTIVO = 0x80FFFFFF;

    private static final int[] TABS  = {R.id.tabMapa,  R.id.tabViaje,  R.id.tabNoticias,  R.id.tabAvisos};
    private static final int[] ICONS = {R.id.iconMapa, R.id.iconViaje, R.id.iconNoticias, R.id.iconAvisos};
    private static final int[] PILLS = {R.id.pillMapa, R.id.pillViaje, R.id.pillNoticias, R.id.pillAvisos};

    private ViewPager2 viewPager;
    private View splashOverlay;
    private int currentTab = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        viewPager = findViewById(R.id.viewPager);
        splashOverlay = findViewById(R.id.splashOverlay);

        viewPager.setAdapter(new MainPagerAdapter(this));
        viewPager.setOffscreenPageLimit(3);
        // FIX: deshabilitar swipe lateral (Google Maps gestiona los gestos)
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

        // Set initial state without animation
        setTabImmediate(0);

        iniciarAnimacionSplash();

        // Entrance animation for the nav bar itself
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

        // El splash se oculta cuando el repositorio (GTFS + shapes + estaciones) está listo.
        // Al ser idempotente, si MapaFragment ya lo lanzó antes, el callback llega al instante.
        MetroRepository.getInstance().init(getApplicationContext(), new MetroRepository.Callback() {
            @Override public void onReady(MetroRepository repository) { ocultarSplash(); }
            @Override public void onError(Throwable error) { ocultarSplash(); }
        });

        // Fallback de seguridad: si por lo que sea el repo tarda demasiado, ocultamos igual
        viewPager.postDelayed(this::ocultarSplash, 12000);
    }

    // FIX #2: animación suave de pulso del logo (placeholder mientras exista el definitivo)
    private void iniciarAnimacionSplash() {
        ImageView logo = findViewById(R.id.splashLogo);
        if (logo == null) return;
        ObjectAnimator sx = ObjectAnimator.ofFloat(logo, "scaleX", 0.92f, 1.08f);
        ObjectAnimator sy = ObjectAnimator.ofFloat(logo, "scaleY", 0.92f, 1.08f);
        sx.setDuration(900); sy.setDuration(900);
        sx.setRepeatMode(ValueAnimator.REVERSE); sy.setRepeatMode(ValueAnimator.REVERSE);
        sx.setRepeatCount(ValueAnimator.INFINITE); sy.setRepeatCount(ValueAnimator.INFINITE);
        AnimatorSet set = new AnimatorSet();
        set.playTogether(sx, sy);
        set.start();
    }

    // FIX #2: fade-out del splash
    private void ocultarSplash() {
        if (splashOverlay == null || splashOverlay.getVisibility() != View.VISIBLE) return;
        splashOverlay.animate()
                .alpha(0f)
                .setDuration(400)
                .withEndAction(() -> splashOverlay.setVisibility(View.GONE))
                .start();
    }

    /**
     * Set initial tab state without animations.
     */
    private void setTabImmediate(int position) {
        for (int i = 0; i < TABS.length; i++) {
            boolean activo = i == position;
            ImageView icon = findViewById(ICONS[i]);
            View pill = findViewById(PILLS[i]);
            if (icon != null) {
                icon.setColorFilter(activo ? COLOR_ACTIVO : COLOR_INACTIVO);
                icon.setScaleX(activo ? 1.15f : 1.0f);
                icon.setScaleY(activo ? 1.15f : 1.0f);
            }
            if (pill != null) {
                pill.setAlpha(activo ? 1.0f : 0.0f);
            }
        }
        currentTab = position;
    }

    /**
     * Animate tab switch with iOS-style spring physics and color transitions.
     */
    private void animateToTab(int position) {
        if (position == currentTab) return;

        for (int i = 0; i < TABS.length; i++) {
            ImageView icon = findViewById(ICONS[i]);
            View pill = findViewById(PILLS[i]);
            boolean activo = i == position;
            boolean wasActive = i == currentTab;

            if (icon != null) {
                // Tint color animation
                int fromColor = wasActive ? COLOR_ACTIVO : COLOR_INACTIVO;
                int toColor   = activo   ? COLOR_ACTIVO : COLOR_INACTIVO;
                if (fromColor != toColor) {
                    ValueAnimator colorAnim = ValueAnimator.ofObject(new ArgbEvaluator(), fromColor, toColor);
                    colorAnim.setDuration(250);
                    colorAnim.addUpdateListener(a -> icon.setColorFilter((int) a.getAnimatedValue()));
                    colorAnim.start();
                }

                // Spring scale: active tab bounces up to 1.15, others settle back to 1.0
                float targetScale = activo ? 1.15f : 1.0f;
                createSpring(icon, DynamicAnimation.SCALE_X, targetScale).start();
                createSpring(icon, DynamicAnimation.SCALE_Y, targetScale).start();
            }

            if (pill != null) {
                // Pill fade in / out
                pill.animate()
                        .alpha(activo ? 1.0f : 0.0f)
                        .setDuration(activo ? 300 : 200)
                        .start();

                // Quick scale pop for the new active pill
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

    /**
     * Create a spring animation with natural iOS-like feel.
     */
    private SpringAnimation createSpring(View view, DynamicAnimation.ViewProperty property, float target) {
        SpringForce spring = new SpringForce(target)
                .setDampingRatio(SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY)
                .setStiffness(SpringForce.STIFFNESS_LOW);
        return new SpringAnimation(view, property).setSpring(spring);
    }
}
