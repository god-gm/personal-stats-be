package com.godsofdeath.monitor.service;

import com.godsofdeath.monitor.dto.output.GenericResponseDTO;
import com.godsofdeath.monitor.dto.output.PlayerInfoDTO;
import com.godsofdeath.monitor.repository.PlayerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class PlayerService {

    private static final ZoneId           ROME      = ZoneId.of("Europe/Rome");
    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ROME);

    private final PlayerRepository playerRepository;

    @Value("${tacticus.api.base-url}")
    private String tacticusBaseUrl;

    public GenericResponseDTO<PlayerInfoDTO> getPlayerInfo(String userId) {
        String apiKey = playerRepository.findById(userId)
                .map(p -> p.getApiKey())
                .orElse(null);

        if (apiKey == null || apiKey.isBlank()) {
            return GenericResponseDTO.ko("API key non configurata per questo operativo");
        }

        Map<String, Object> body = callPlayerApi(apiKey);
        if (body == null) {
            return GenericResponseDTO.ko("Chiamata API Tacticus fallita");
        }

        try {
            PlayerInfoDTO info = parsePlayerInfo(body);
            return GenericResponseDTO.ok("Info operativo recuperate", info);
        } catch (Exception e) {
            log.warn("Parsing player API response failed: {}", e.getMessage());
            return GenericResponseDTO.ko("Risposta API non valida");
        }
    }

    @SuppressWarnings("unchecked")
    private PlayerInfoDTO parsePlayerInfo(Map<String, Object> body) {
        Map<String, Object> player    = (Map<String, Object>) body.get("player");
        Map<String, Object> details   = (Map<String, Object>) player.get("details");
        Map<String, Object> progress  = (Map<String, Object>) player.get("progress");
        Map<String, Object> guildRaid = (Map<String, Object>) progress.get("guildRaid");

        String apiPlayerName = (String) details.get("name");

        Map<String, Object> tokens     = (Map<String, Object>) guildRaid.get("tokens");
        Map<String, Object> bombTokens = (Map<String, Object>) guildRaid.get("bombTokens");

        int tokensCurrent = toInt(tokens, "current");
        int tokensMax     = toInt(tokens, "max");
        String tokensNextAt = null;
        if (tokensCurrent < tokensMax) {
            long secsToNext = toLong(tokens, "nextTokenInSeconds");
            tokensNextAt = FORMATTER.format(Instant.now().plusSeconds(secsToNext));
        }

        int bombCurrent   = toInt(bombTokens, "current");
        boolean bombAvail = bombCurrent > 0;
        String bombNextAt = null;
        if (!bombAvail) {
            long secsToNext = toLong(bombTokens, "nextTokenInSeconds");
            bombNextAt = FORMATTER.format(Instant.now().plusSeconds(secsToNext));
        }

        return PlayerInfoDTO.builder()
                .apiPlayerName(apiPlayerName)
                .tokensCurrent(tokensCurrent)
                .tokensMax(tokensMax)
                .tokensNextAt(tokensNextAt)
                .bombAvailable(bombAvail)
                .bombNextAt(bombNextAt)
                .build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> callPlayerApi(String apiKey) {
        RestTemplate rt = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-KEY", apiKey);
        headers.set("accept", "application/json");
        try {
            ResponseEntity<Map> response = rt.exchange(
                    tacticusBaseUrl + "/player",
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    Map.class);
            return response.getStatusCode().is2xxSuccessful() ? response.getBody() : null;
        } catch (RestClientException e) {
            log.warn("Tacticus player API call failed: {}", e.getMessage());
            return null;
        }
    }

    private static int toInt(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof Number ? ((Number) v).intValue() : 0;
    }

    private static long toLong(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof Number ? ((Number) v).longValue() : 0L;
    }
}
