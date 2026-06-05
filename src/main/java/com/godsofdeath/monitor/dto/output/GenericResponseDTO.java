package com.godsofdeath.monitor.dto.output;

import lombok.Getter;

/**
 * Envelope generico per tutte le response REST.
 * <p>
 * Utilizzo:
 * <pre>
 *   // Success con payload
 *   return GenericResponseDTO.ok("Login effettuato", new LoginDataDTO(...));
 *
 *   // Errore
 *   return GenericResponseDTO.ko("Credenziali non valide");
 * </pre>
 *
 * @param <T> tipo del payload dati; usare {@link Void} se non c'è payload
 */
@Getter
public class GenericResponseDTO<T> {

    private final String status;
    private final String message;
    private final T data;

    private GenericResponseDTO(String status, String message, T data) {
        this.status  = status;
        this.message = message;
        this.data    = data;
    }

    public static <T> GenericResponseDTO<T> ok(String message, T data) {
        return new GenericResponseDTO<>("OK", message, data);
    }

    public static GenericResponseDTO<Void> ok(String message) {
        return new GenericResponseDTO<>("OK", message, null);
    }

    public static <T> GenericResponseDTO<T> ko(String message) {
        return new GenericResponseDTO<>("KO", message, null);
    }

    public static <T> GenericResponseDTO<T> denied(String message) {
        return new GenericResponseDTO<>("DENIED", message, null);
    }
}
