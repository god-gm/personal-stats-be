package com.godsofdeath.monitor.service;

import com.godsofdeath.monitor.dto.internal.DiscordTokenResponseDTO;
import com.godsofdeath.monitor.dto.internal.DiscordUserResponseDTO;
import com.godsofdeath.monitor.dto.output.GenericResponseDTO;
import com.godsofdeath.monitor.dto.output.LoginDataDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Slf4j
@Service
public class DiscordService {

    private static final String DISCORD_TOKEN_URL = "https://discord.com/api/oauth2/token";
    private static final String DISCORD_USER_URL  = "https://discord.com/api/users/@me";

    @Value("${discord.client-id}")
    private String clientId;

    @Value("${discord.client-secret}")
    private String clientSecret;

    @Value("${discord.redirect-uri}")
    private String redirectUri;

    private final RestClient restClient;
    private final AuthService authService;

    public DiscordService(AuthService authService) {
        this.restClient  = RestClient.create();
        this.authService = authService;
    }

    public GenericResponseDTO<LoginDataDTO> handleCallback(String code) {
        // Step 1 — Scambia il code con Discord
        DiscordTokenResponseDTO tokenResponse = exchangeCodeForToken(code);
        if (tokenResponse == null || tokenResponse.getAccessToken() == null) {
            return GenericResponseDTO.ko("Autenticazione Discord fallita: codice non valido o scaduto.");
        }

        // Step 2 — Recupera l'identità Discord
        DiscordUserResponseDTO discordUser = fetchDiscordUser(tokenResponse.getAccessToken());
        if (discordUser == null || discordUser.getUsername() == null) {
            return GenericResponseDTO.ko("Impossibile recuperare l'identità Discord.");
        }

        // Step 3 & 4 — Verifica guild e genera JWT
        GenericResponseDTO<LoginDataDTO> loginResult = authService.login(discordUser.getUsername());
        if (!"OK".equals(loginResult.getStatus())) {
            return GenericResponseDTO.denied("Operativo non riconosciuto.");
        }
        return loginResult;
    }

    private DiscordTokenResponseDTO exchangeCodeForToken(String code) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("client_id",     clientId);
        body.add("client_secret", clientSecret);
        body.add("grant_type",    "authorization_code");
        body.add("code",          code);
        body.add("redirect_uri",  redirectUri);

        try {
            return restClient.post()
                    .uri(DISCORD_TOKEN_URL)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(body)
                    .retrieve()
                    .body(DiscordTokenResponseDTO.class);
        } catch (RestClientException e) {
            log.warn("Discord token exchange failed: {}", e.getMessage());
            return null;
        }
    }

    private DiscordUserResponseDTO fetchDiscordUser(String accessToken) {
        try {
            return restClient.get()
                    .uri(DISCORD_USER_URL)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .body(DiscordUserResponseDTO.class);
        } catch (RestClientException e) {
            log.warn("Discord user fetch failed: {}", e.getMessage());
            return null;
        }
    }
}
