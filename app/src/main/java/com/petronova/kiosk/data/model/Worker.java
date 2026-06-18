package com.petronova.kiosk.data.model;

/** Modelo de trabajador. Equivalente del dict worker en secugen_service.py y fingerprint_db.py. */
public class Worker {
    public final int    id;
    public final String nombre;
    public final String apellidos;
    public final String carnet;
    /** Template de huella en hex (800 chars para SG400 400 bytes). Puede ser null si no tiene huella. */
    public final String huellaHex;
    /** UID de tarjeta NFC. Puede ser null si no tiene NFC registrado. */
    public final String nfcUid;

    public Worker(int id, String nombre, String apellidos, String carnet,
                  String huellaHex, String nfcUid) {
        this.id        = id;
        this.nombre    = nombre != null ? nombre : "";
        this.apellidos = apellidos != null ? apellidos : "";
        this.carnet    = carnet != null ? carnet : "";
        this.huellaHex = huellaHex;
        this.nfcUid    = nfcUid;
    }

    public String getFullName() {
        return (nombre + " " + apellidos).trim();
    }

    @Override
    public String toString() {
        return "Worker{id=" + id + ", nombre=" + nombre + ", carnet=" + carnet + "}";
    }
}
