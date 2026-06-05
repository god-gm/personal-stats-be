package com.godsofdeath.monitor.service;

import com.godsofdeath.monitor.document.PlayerDocument;
import com.godsofdeath.monitor.dto.output.GenericResponseDTO;
import com.godsofdeath.monitor.dto.output.LoginDataDTO;
import com.godsofdeath.monitor.repository.AdminUserRepository;
import com.godsofdeath.monitor.repository.PlayerRepository;
import com.godsofdeath.monitor.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final PlayerRepository    playerRepository;
    private final AdminUserRepository adminUserRepository;
    private final JwtUtil             jwtUtil;

    public GenericResponseDTO<LoginDataDTO> login(String discordName) {
        String normalized = discordName.startsWith("@") ? discordName.substring(1) : discordName;

        return playerRepository.findByDiscordNameIgnoreCase(normalized)
                .map(player -> {
                    String role  = adminUserRepository.isAdmin(normalized) ? "ADMIN" : "USER";
                    String token = jwtUtil.generateToken(player.getUserId(), player.getUserGameName(), role);
                    return GenericResponseDTO.ok("Login effettuato",
                            LoginDataDTO.builder()
                                    .token(token)
                                    .userGameName(player.getUserGameName())
                                    .role(role)
                                    .build());
                })
                .orElseGet(() -> GenericResponseDTO.ko(
                        "Username Discord non riconosciuto o account disabilitato"));
    }
}
