package com.petronova.kiosk.ui;

import android.app.Dialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import com.petronova.kiosk.R;
import com.petronova.kiosk.audio.SoundManager;
import com.petronova.kiosk.audio.TtsManager;
import com.petronova.kiosk.config.AppConfig;

/**
 * Dialog de éxito / error con autocierre.
 * Equivalente de showSuccessModal() y showErrorModal() en ui.js.
 * Autocierre en AppConfig.MODAL_AUTO_CLOSE_MS (2500 ms).
 * También reproduce TTS del mensaje y el beep correspondiente.
 */
public class StatusDialog extends DialogFragment {

    public enum Type { SUCCESS, ERROR }

    private static final String ARG_TYPE    = "type";
    private static final String ARG_MESSAGE = "message";

    public static StatusDialog success(String message) {
        return create(Type.SUCCESS, message);
    }

    public static StatusDialog error(String message) {
        return create(Type.ERROR, message);
    }

    private static StatusDialog create(Type type, String message) {
        StatusDialog d = new StatusDialog();
        Bundle args = new Bundle();
        args.putString(ARG_TYPE,    type.name());
        args.putString(ARG_MESSAGE, message);
        d.setArguments(args);
        d.setCancelable(true);
        return d;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        String typeName = getArguments().getString(ARG_TYPE, Type.SUCCESS.name());
        String message  = getArguments().getString(ARG_MESSAGE, "");
        Type   type     = Type.valueOf(typeName);

        View view = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_status, null);

        TextView tvIcon    = view.findViewById(R.id.tv_status_icon);
        TextView tvMessage = view.findViewById(R.id.tv_status_message);

        if (type == Type.SUCCESS) {
            tvIcon.setText("✓");
            tvIcon.setTextColor(requireContext().getColor(R.color.color_success));
            SoundManager.playSuccessBeep();
        } else {
            tvIcon.setText("✗");
            tvIcon.setTextColor(requireContext().getColor(R.color.color_error));
            SoundManager.playErrorBeep();
        }
        tvMessage.setText(message);

        // TTS del mensaje
        TtsManager.getInstance().speak(message);

        // Autocierre tras MODAL_AUTO_CLOSE_MS (equivalente setTimeout en ui.js)
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (isAdded()) dismissAllowingStateLoss();
        }, AppConfig.MODAL_AUTO_CLOSE_MS);

        return new AlertDialog.Builder(requireContext(), R.style.KioskDialog)
            .setView(view)
            .create();
    }
}
