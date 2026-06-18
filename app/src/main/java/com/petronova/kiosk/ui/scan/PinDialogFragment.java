package com.petronova.kiosk.ui.scan;

import android.app.Dialog;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import com.petronova.kiosk.R;
import com.petronova.kiosk.audio.SoundManager;
import com.petronova.kiosk.config.AppConfig;

/**
 * Dialog de PIN para salir de la pantalla de escaneo.
 * Equivalente de las funciones PIN en scanning.js:
 *   showPinModal(), pinPress(), pinDelete(), verifyPin().
 *
 * PIN: AppConfig.EXIT_PIN ("11235")
 * Máximo intentos: AppConfig.PIN_MAX_ATTEMPTS (3)
 * Cooldown tras agotar: AppConfig.PIN_COOLDOWN_MS (10 min)
 */
public class PinDialogFragment extends DialogFragment {

    private final StringBuilder pinBuffer = new StringBuilder();
    private int                 attempts  = 0;
    private boolean             blocked   = false;
    private CountDownTimer      cooldownTimer;
    private Runnable            onPinVerified;
    private boolean             allowCancel = false;

    private TextView tvDisplay;
    private TextView tvError;
    private Button   btnOk;

    public void setOnPinVerifiedListener(Runnable listener) {
        this.onPinVerified = listener;
    }

    /** Permite cerrar el diálogo sin PIN (back / tocar fuera). Por defecto: false. */
    public void setAllowCancel(boolean allow) {
        this.allowCancel = allow;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        View view = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_pin, null);

        tvDisplay = view.findViewById(R.id.tv_pin_display);
        tvError   = view.findViewById(R.id.tv_pin_error);
        btnOk     = view.findViewById(R.id.btn_pin_ok);

        // Teclas numéricas
        int[] numBtnIds = {
            R.id.btn_0, R.id.btn_1, R.id.btn_2, R.id.btn_3, R.id.btn_4,
            R.id.btn_5, R.id.btn_6, R.id.btn_7, R.id.btn_8, R.id.btn_9
        };
        for (int i = 0; i < numBtnIds.length; i++) {
            final String digit = String.valueOf(i);
            Button b = view.findViewById(numBtnIds[i]);
            if (b != null) b.setOnClickListener(v -> pressDigit(digit));
        }

        view.findViewById(R.id.btn_pin_delete).setOnClickListener(v -> deleteDigit());
        btnOk.setOnClickListener(v -> verifyPin());

        setCancelable(allowCancel);
        AlertDialog dialog = new AlertDialog.Builder(requireContext(), R.style.KioskDialog)
            .setView(view)
            .create();
        dialog.setCanceledOnTouchOutside(allowCancel);
        return dialog;
    }

    private void pressDigit(String digit) {
        if (blocked || pinBuffer.length() >= 5) return;
        SoundManager.playNumpadBeep();
        pinBuffer.append(digit);
        updateDisplay();
    }

    private void deleteDigit() {
        if (pinBuffer.length() > 0) {
            pinBuffer.deleteCharAt(pinBuffer.length() - 1);
            updateDisplay();
        }
    }

    private void updateDisplay() {
        StringBuilder stars = new StringBuilder();
        for (int i = 0; i < pinBuffer.length(); i++) stars.append("●");
        tvDisplay.setText(stars.toString());
    }

    private void verifyPin() {
        if (blocked) return;
        if (pinBuffer.toString().equals(AppConfig.EXIT_PIN)) {
            dismiss();
            if (onPinVerified != null) onPinVerified.run();
        } else {
            attempts++;
            pinBuffer.setLength(0);
            updateDisplay();
            if (attempts >= AppConfig.PIN_MAX_ATTEMPTS) {
                startCooldown();
            } else {
                tvError.setText(getString(R.string.pin_wrong));
                tvError.setVisibility(View.VISIBLE);
            }
        }
    }

    private void startCooldown() {
        blocked = true;
        btnOk.setEnabled(false);
        cooldownTimer = new CountDownTimer(AppConfig.PIN_COOLDOWN_MS, 1000) {
            @Override public void onTick(long ms) {
                long minutos = ms / 60000;
                tvError.setText(getString(R.string.pin_blocked, (int) minutos + 1));
                tvError.setVisibility(View.VISIBLE);
            }
            @Override public void onFinish() {
                blocked = false;
                attempts = 0;
                btnOk.setEnabled(true);
                tvError.setVisibility(View.GONE);
            }
        }.start();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (cooldownTimer != null) cooldownTimer.cancel();
    }
}
