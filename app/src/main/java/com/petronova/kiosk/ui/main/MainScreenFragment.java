package com.petronova.kiosk.ui.main;

import android.animation.ValueAnimator;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.petronova.kiosk.R;
import com.petronova.kiosk.data.local.LocalConfigStore;
import com.petronova.kiosk.databinding.FragmentMainScreenBinding;
import com.petronova.kiosk.sensors.SensorCoordinator;
import com.petronova.kiosk.ui.registration.RegistrationFragment;
import com.petronova.kiosk.ui.scan.PinDialogFragment;

/**
 * Pantalla principal del kiosko.
 * Equivalente de mainScreen + funciones de main.js: backToMain(), showMainScreen().
 *
 * Incluye botones de Tanques, Ubicaciones y Pistero equivalentes a los botones
 * fuel-button de main_screen.html (showTankSelector, showLocationSelector, openPisteroModal).
 */
public class MainScreenFragment extends Fragment {

    private FragmentMainScreenBinding binding;
    private int logoTapCount = 0;
    private ValueAnimator novaPulse;
    private ValueAnimator betaPulse;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        binding = FragmentMainScreenBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Reanudar sensores al volver a main (equivalente resume_loop en main.js:44)
        SensorCoordinator.getInstance().resumeLoop();

        MainActivity activity = (MainActivity) requireActivity();

        binding.btnStartScan.setOnClickListener(v -> activity.showScanScreen());

        binding.btnRegisterFingerprint.setOnClickListener(v ->
            activity.showRegistrationScreen(RegistrationFragment.Mode.FINGERPRINT));

        binding.btnRegisterNfc.setOnClickListener(v ->
            activity.showRegistrationScreen(RegistrationFragment.Mode.NFC));

        // Configuración: pide el PIN global y revela Tanques/Ubicaciones
        binding.btnConfig.setOnClickListener(v -> showConfigPin());

        // Tanques (equivalente onclick="showTankSelector()")
        binding.btnTanques.setOnClickListener(v -> activity.showTankSelector());

        // Ubicaciones (equivalente onclick="showLocationSelector()")
        binding.btnUbicaciones.setOnClickListener(v -> activity.showLocationSelector());

        // Pistero (equivalente onclick="openPisteroModal()")
        binding.btnPistero.setOnClickListener(v -> activity.showPisteroScreen());

        // Admin oculto: 5 taps en el título activan el botón
        binding.getRoot().setOnClickListener(v -> {
            logoTapCount++;
            if (logoTapCount >= 5) {
                logoTapCount = 0;
                binding.btnAdmin.setVisibility(View.VISIBLE);
            }
        });

        binding.btnAdmin.setOnClickListener(v -> {
            binding.btnAdmin.setVisibility(View.GONE);
            activity.showAdminScreen();
        });

        // Efecto neón pulsante (glow) sobre "NOVA" y la etiqueta beta
        int neon = requireContext().getColor(R.color.color_neon_yellow);
        novaPulse = neonPulse(binding.tvLogoNova, neon, 4f, 14f, 900);
        betaPulse = neonPulse(binding.tvBeta,     neon, 3f, 12f, 750);
    }

    /** Anima el radio del glow (setShadowLayer) para un efecto neón pulsante. */
    private ValueAnimator neonPulse(TextView tv, int color, float min, float max, long durationMs) {
        ValueAnimator anim = ValueAnimator.ofFloat(min, max);
        anim.setDuration(durationMs);
        anim.setRepeatMode(ValueAnimator.REVERSE);
        anim.setRepeatCount(ValueAnimator.INFINITE);
        anim.addUpdateListener(a -> {
            if (binding == null) return;
            tv.setShadowLayer((float) a.getAnimatedValue(), 0, 0, color);
        });
        anim.start();
        return anim;
    }

    /** Pide el PIN global; si es correcto, revela los botones de configuración. */
    private void showConfigPin() {
        PinDialogFragment dialog = new PinDialogFragment();
        dialog.setAllowCancel(true); // se puede cerrar si se tocó por error
        dialog.setOnPinVerifiedListener(this::revealConfigButtons);
        dialog.show(getChildFragmentManager(), "config_pin");
    }

    /** Muestra Tanques/Ubicaciones y oculta el botón Configuración. */
    private void revealConfigButtons() {
        if (binding == null) return;
        binding.btnTanques.setVisibility(View.VISIBLE);
        binding.btnUbicaciones.setVisibility(View.VISIBLE);
        binding.btnConfig.setVisibility(View.GONE);
    }

    @Override
    public void onResume() {
        super.onResume();
        // Refrescar nombre del pistero al volver a main
        // (equivalente loadPisteroIntoButton() en scanning.js)
        refreshPisteroName();
    }

    private void refreshPisteroName() {
        if (binding == null) return;
        LocalConfigStore store = new LocalConfigStore(requireContext());
        LocalConfigStore.PisteroData p = store.getPistero();
        if (p != null) {
            binding.tvPisteroName.setText(p.getFullName());
            binding.tvPisteroName.setTextColor(requireContext().getColor(R.color.color_pistero));
        } else {
            binding.tvPisteroName.setText(getString(R.string.pistero_no_active));
            binding.tvPisteroName.setTextColor(requireContext().getColor(R.color.color_text_secondary));
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (novaPulse != null) { novaPulse.cancel(); novaPulse = null; }
        if (betaPulse != null) { betaPulse.cancel(); betaPulse = null; }
        binding = null;
    }
}
