package com.petronova.kiosk.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.util.Base64;
import android.widget.ImageView;

/** Utilidades de imagen compartidas por la UI (preview de huella, etc.). */
public final class ImageUtils {

    private ImageUtils() {}

    /**
     * Filtro cyberpunk para el preview de la huella: remapea la imagen en escala de
     * grises (crestas oscuras sobre fondo claro) a un fondo azul oscuro con las líneas
     * de la huella en cian neón brillante, igual que el color_primary (#00FFFF) del resto de la UI.
     *
     * Por luminancia Y: Y=0 (cresta) → cian neón (0,255,255); Y=255 (fondo) → azul oscuro (10,18,38).
     */
    private static final ColorMatrixColorFilter NEON_FINGERPRINT_FILTER =
            new ColorMatrixColorFilter(new ColorMatrix(new float[] {
                 0.0117f,  0.0230f,  0.0045f, 0f,   0f,   // R
                -0.2778f, -0.5453f, -0.1059f, 0f, 255f,   // G
                -0.2544f, -0.4995f, -0.0970f, 0f, 255f,   // B
                 0f,       0f,       0f,      1f,   0f     // A
            }));

    /** Aplica el filtro neón (fondo azul oscuro, líneas azul neón) al ImageView del preview. */
    public static void applyNeonFingerprint(ImageView iv) {
        if (iv != null) iv.setColorFilter(NEON_FINGERPRINT_FILTER);
    }

    /**
     * Decodifica un data-URI base64 ("data:image/png;base64,...") o un base64 plano a Bitmap.
     * Devuelve null si el string es vacío o no se puede decodificar.
     */
    public static Bitmap decodeDataUri(String dataUri) {
        if (dataUri == null || dataUri.isEmpty()) return null;
        try {
            int comma = dataUri.indexOf(',');
            String b64 = comma >= 0 ? dataUri.substring(comma + 1) : dataUri;
            byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Throwable t) {
            return null;
        }
    }
}
