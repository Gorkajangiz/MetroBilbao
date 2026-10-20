package com.example.metrobilbao;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

public class MainPagerAdapter extends FragmentStateAdapter {

    public MainPagerAdapter(@NonNull FragmentActivity fa) { super(fa); }

    @NonNull @Override
    public Fragment createFragment(int position) {
        switch (position) {
            case 1: return new ViajeFragment();
            case 2: return new NoticiasFragment();
            case 3: return new AvisosFragment();
            default: return new MapaFragment();
        }
    }

    @Override public int getItemCount() { return 4; }
}