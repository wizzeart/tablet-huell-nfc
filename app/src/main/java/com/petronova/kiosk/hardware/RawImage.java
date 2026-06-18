package com.petronova.kiosk.hardware;

/**
 * Imagen cruda del sensor de huella.
 * Equivalente del buffer cImgBuf + dimensiones en secugen_fingerprint.py.
 */
public class RawImage {
    public final byte[] pixels;
    public final int    width;
    public final int    height;
    public final int    quality;

    public RawImage(byte[] pixels, int width, int height, int quality) {
        this.pixels  = pixels;
        this.width   = width;
        this.height  = height;
        this.quality = quality;
    }
}
