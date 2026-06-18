package com.petronova.kiosk.hardware;

/**
 * Resultado de comparar dos templates de huella.
 * Equivalente del par (matched, score) devuelto por SecuGenFingerprint.match_templates().
 */
public class MatchResult {
    public final boolean matched;
    public final int     score;

    public MatchResult(boolean matched, int score) {
        this.matched = matched;
        this.score   = score;
    }
}
