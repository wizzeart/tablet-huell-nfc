package com.petronova.kiosk.data.db;

import com.petronova.kiosk.data.model.Worker;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Acceso a datos de trabajadores en PostgreSQL.
 * Porta 1:1 las funciones de fingerprint_db_pkg/fingerprint_db.py.
 * Tabla: public.users (id, name, carnet, huella, nfc, activo, deleted_at, updated_at)
 *
 * NOTA: todos los métodos son bloqueantes — llamar siempre desde hilo de fondo.
 */
public final class WorkerDao {

    private WorkerDao() {}

    // ─── Queries (idénticas a fingerprint_db.py) ─────────────────────────────

    private static final String SQL_FIND_BY_ID =
        "SELECT \"name\", carnet FROM public.users " +
        "WHERE id = ? AND activo = TRUE AND deleted_at IS NULL";

    private static final String SQL_FIND_BY_CI =
        "SELECT id, \"name\", carnet FROM public.users " +
        "WHERE carnet = ? AND activo = TRUE AND deleted_at IS NULL";

    private static final String SQL_FIND_BY_NFC =
        "SELECT id, \"name\", carnet FROM public.users " +
        "WHERE \"nfc\" = ? AND activo = TRUE AND deleted_at IS NULL";

    private static final String SQL_GET_WITH_HUELLA =
        "SELECT id, \"name\", NULL::text, carnet, huella FROM public.users " +
        "WHERE huella IS NOT NULL AND activo = TRUE AND deleted_at IS NULL";

    private static final String SQL_MAX_UPDATED_AT =
        "SELECT MAX(updated_at) FROM public.users " +
        "WHERE huella IS NOT NULL AND activo = TRUE AND deleted_at IS NULL";

    private static final String SQL_SAVE_HUELLA =
        "UPDATE public.users SET huella = ?, updated_at = NOW() WHERE id = ?";

    private static final String SQL_CLEAR_HUELLA =
        "UPDATE public.users SET huella = NULL, updated_at = NOW() WHERE id = ?";

    private static final String SQL_SAVE_NFC =
        "UPDATE public.users SET \"nfc\" = ?, updated_at = NOW() WHERE id = ?";

    // ─── Equivalente: get_trabajador_by_id() ─────────────────────────────────

    /** Retorna Worker parcial (sin huella/nfc) o null si no existe. */
    public static Worker findById(int id) throws SQLException {
        try (Connection c = DbConnectionFactory.getConnection();
             PreparedStatement ps = c.prepareStatement(SQL_FIND_BY_ID)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new Worker(id, rs.getString(1), "", rs.getString(2), null, null);
            }
        }
    }

    // ─── Equivalente: get_trabajador_by_ci() ─────────────────────────────────

    /** Retorna Worker por CI o null si no existe. */
    public static Worker findByCi(String ci) throws SQLException {
        try (Connection c = DbConnectionFactory.getConnection();
             PreparedStatement ps = c.prepareStatement(SQL_FIND_BY_CI)) {
            ps.setString(1, ci.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new Worker(rs.getInt(1), rs.getString(2), "", rs.getString(3), null, null);
            }
        }
    }

    // ─── Equivalente: get_trabajador_by_nfc() ────────────────────────────────

    /** Retorna Worker por UID NFC o null si no existe. */
    public static Worker findByNfc(String uid) throws SQLException {
        try (Connection c = DbConnectionFactory.getConnection();
             PreparedStatement ps = c.prepareStatement(SQL_FIND_BY_NFC)) {
            ps.setString(1, uid.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new Worker(rs.getInt(1), rs.getString(2), "", rs.getString(3), null, uid);
            }
        }
    }

    // ─── Equivalente: get_trabajadores_con_huella_secugen() ──────────────────

    /** Devuelve todos los trabajadores con huella no nula. Usado para matching iterativo. */
    public static List<Worker> getWorkersWithHuella() throws SQLException {
        List<Worker> result = new ArrayList<>();
        try (Connection c = DbConnectionFactory.getConnection();
             PreparedStatement ps = c.prepareStatement(SQL_GET_WITH_HUELLA);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(new Worker(
                    rs.getInt(1),       // id
                    rs.getString(2),    // name
                    "",                 // apellidos (NULL en la query)
                    rs.getString(4),    // carnet
                    rs.getString(5),    // huella hex
                    null
                ));
            }
        }
        return result;
    }

    // ─── Polling ligero: MAX(updated_at) de las huellas activas ───────────────

    /**
     * Devuelve el MAX(updated_at) de los trabajadores con huella activa, o null si no hay ninguno.
     * Query muy liviana (no transfiere filas) usada para detectar si la cache está obsoleta
     * antes de recargarla completa.
     */
    public static java.sql.Timestamp getMaxUpdatedAt() throws SQLException {
        try (Connection c = DbConnectionFactory.getConnection();
             PreparedStatement ps = c.prepareStatement(SQL_MAX_UPDATED_AT);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) return rs.getTimestamp(1);
            return null;
        }
    }

    // ─── Equivalente: guardar_huella_secugen_trabajador() ────────────────────

    /** Guarda el template de huella en hex. Retorna true si se actualizó alguna fila. */
    public static boolean saveHuella(int workerId, String huellaHex) throws SQLException {
        try (Connection c = DbConnectionFactory.getConnection();
             PreparedStatement ps = c.prepareStatement(SQL_SAVE_HUELLA)) {
            ps.setString(1, huellaHex);
            ps.setInt(2, workerId);
            return ps.executeUpdate() > 0;
        }
    }

    // ─── Equivalente: limpiar_huella_secugen_trabajador() ────────────────────

    /** Pone la huella a NULL (limpia el campo antes de re-enrolar). */
    public static boolean clearHuella(int workerId) throws SQLException {
        try (Connection c = DbConnectionFactory.getConnection();
             PreparedStatement ps = c.prepareStatement(SQL_CLEAR_HUELLA)) {
            ps.setInt(1, workerId);
            return ps.executeUpdate() > 0;
        }
    }

    // ─── Equivalente: actualizar_nfc_trabajador() ────────────────────────────

    /** Guarda o actualiza el UID NFC del trabajador. */
    public static boolean saveNfc(int workerId, String nfcUid) throws SQLException {
        try (Connection c = DbConnectionFactory.getConnection();
             PreparedStatement ps = c.prepareStatement(SQL_SAVE_NFC)) {
            ps.setString(1, nfcUid.trim());
            ps.setInt(2, workerId);
            return ps.executeUpdate() > 0;
        }
    }
}
