package me.tgwaffles.positionaldiscord;

import java.util.UUID;

public class RegistrationData {
    private final UUID playerUUID;
    private final String discordUserId;
    public RegistrationData(UUID playerUUID, String discordUserId) {
        this.playerUUID = playerUUID;
        this.discordUserId = discordUserId;
    }

    public UUID getPlayerUUID() {
        return playerUUID;
    }

    public String getDiscordId() {
        return discordUserId;
    }
}
