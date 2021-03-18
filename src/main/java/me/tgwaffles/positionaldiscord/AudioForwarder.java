package me.tgwaffles.positionaldiscord;

import net.dv8tion.jda.api.audio.AudioReceiveHandler;
import net.dv8tion.jda.api.audio.AudioSendHandler;
import net.dv8tion.jda.api.audio.UserAudio;
import net.dv8tion.jda.api.audio.hooks.ConnectionListener;
import net.dv8tion.jda.api.audio.hooks.ConnectionStatus;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.events.message.guild.GuildMessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.managers.AudioManager;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;

public class AudioForwarder extends ListenerAdapter
{
    PositionalDiscord plugin;
    private final ConcurrentHashMap<String, UUID> idsToUUIDs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String> uuidsToIds = new ConcurrentHashMap<>();
    public final ConcurrentHashMap<String, Queue<byte[]>> inputQueueMap = new ConcurrentHashMap<>();
    public final ConcurrentHashMap<String, Queue<byte[]>> outputQueueMap = new ConcurrentHashMap<>();

    public AudioForwarder(PositionalDiscord caller) {
        plugin = caller;
    }

    @Override
    public void onGuildMessageReceived(GuildMessageReceivedEvent event)
    {
        Message message = event.getMessage();
        User author = message.getAuthor();
        String content = message.getContentRaw();

        // Ignore message if bot
        if (author.isBot()) { return; }
        if (content.equals("!echo"))
        {
            connectToChannel(event);
        } else if (content.startsWith("!engage ")) {
            String userName = content.substring("!engage ".length());
            registerUser(event, userName);
        } else if (content.startsWith("!close")) {
            closeGuild(event.getGuild());
        }
    }

    public void closeGuild(Guild guild) {
        AudioManager manager = guild.getAudioManager();
        AudioHandler handler = (AudioHandler) manager.getReceivingHandler();
        if (handler != null) {
            handler.close();
            String handlerId = handler.receiveUserId;
            outputQueueMap.remove(handlerId);
            inputQueueMap.remove(handlerId);
        }
        manager.closeAudioConnection();
    }

    private void registerUser(GuildMessageReceivedEvent event, String userName) {
        Player player = plugin.getServer().getPlayer(userName);
        if (player == null) {
            onUnknownUser(event.getChannel(), userName);
            return;
        }
        closeGuild(event.getGuild());
        String discordUserId = event.getAuthor().getId();
        inputQueueMap.put(discordUserId, new ConcurrentLinkedQueue<>());
        outputQueueMap.put(discordUserId, new ConcurrentLinkedQueue<>());
        UUID uuid = player.getUniqueId();
        uuidsToIds.put(uuid, discordUserId);
        idsToUUIDs.put(discordUserId, uuid);
        plugin.getServer().broadcastMessage(ChatColor.GRAY + player.getName() + " is connecting to audio...");
        connectToChannel(event);
    }

    /**
     * Handle command without arguments.
     *
     * @param event
     *        The event for this command
     */
    private void connectToChannel(GuildMessageReceivedEvent event)
    {
        Member member = event.getMember();
        assert member != null;
        GuildVoiceState voiceState = member.getVoiceState();
        assert voiceState != null;
        Guild guild = voiceState.getGuild();
        VoiceChannel channel = voiceState.getChannel();
        if (channel == null) {
            for (VoiceChannel possibleChannel : guild.getVoiceChannels()) {
                for (Member voiceMember : possibleChannel.getMembers()) {
                    if (voiceMember.getId().equals(member.getId())) {
                        channel = possibleChannel;
                        break;
                    }
                }
                if (channel != null) {
                    break;
                }
            }
        }
        if (channel != null) {
            plugin.log.log(Level.INFO, channel.toString());
        }
        if (channel != null)
        {
            connectTo(channel, event.getAuthor().getId());
            onConnecting(channel, event.getChannel());
        }
        else
        {
            onUnknownChannel(event.getChannel());
        }
    }

    /**
     * Inform user about successful connection.
     *
     * @param channel
     *        The voice channel we connected to
     * @param textChannel
     *        The text channel to send the message in
     */
    private void onConnecting(VoiceChannel channel, TextChannel textChannel)
    {
        textChannel.sendMessage("Connecting to " + channel.getName()).queue();
    }

    private void onUnknownUser(MessageChannel channel, String userName) {
        channel.sendMessage("Could not find a logged-in user with the name " + userName + "!").queue();
    }

    /**
     * The channel to connect to is not known to us.
     *  @param channel
     *        The message channel (text channel abstraction) to send failure information to
     *
     */
    private void onUnknownChannel(MessageChannel channel)
    {
        channel.sendMessage("Unable to connect to ``your voice channel``, no such channel!").queue();
    }

    /**
     * Connect to requested channel and start echo handler
     *
     * @param channel
     *        The channel to connect to
     */
    private void connectTo(VoiceChannel channel, String callerId)
    {
        Guild guild = channel.getGuild();
        AudioManager audioManager = guild.getAudioManager();

        AudioHandler handler = new AudioHandler(this, callerId, channel.getGuild());

        audioManager.setSendingHandler(handler);
        audioManager.setReceivingHandler(handler);

        audioManager.setConnectionListener(handler);
        audioManager.openAudioConnection(channel);
    }

    public static class AudioHandler implements AudioSendHandler, AudioReceiveHandler, ConnectionListener
    {
        private final AudioForwarder forwarder;
        private final String receiveUserId;
        public final Guild guild;
        public final Timer timer;
        public AudioHandler(AudioForwarder caller, String callerId, Guild guild) {
            this.guild = guild;
            forwarder = caller;
            receiveUserId = callerId;
            forwarder.plugin.log.log(Level.INFO, "registered audio");

            timer = new Timer();
            timer.scheduleAtFixedRate(new UpdateQueue(this), 0L, 20L);
        }

        @Override
        public boolean canReceiveCombined() {
            // we don't want combined audio, as we're manually combining from multiple guilds
            return false;
        }

        @Override
        public void onPing(long ping) { }

        @Override
        public void onStatusChange(@NotNull ConnectionStatus status) {
            List<ConnectionStatus> disconnectedStatuses = Arrays.asList(ConnectionStatus.DISCONNECTED_CHANNEL_DELETED,
                    ConnectionStatus.DISCONNECTED_AUTHENTICATION_FAILURE, ConnectionStatus.DISCONNECTED_LOST_PERMISSION,
                    ConnectionStatus.DISCONNECTED_KICKED_FROM_CHANNEL,
                    ConnectionStatus.DISCONNECTED_REMOVED_DURING_RECONNECT,
                    ConnectionStatus.DISCONNECTED_REMOVED_FROM_GUILD);
            if (disconnectedStatuses.contains(status)) {
                forwarder.closeGuild(guild);
            }
        }


        @Override
        public void onUserSpeaking(@NotNull User user, boolean speaking) { }

        public void close() {
            this.timer.cancel();
        }

        @Override
        public boolean canReceiveUser()
        {
            // we can always attempt to receive user audio, it will only be used if we have space for it
            return true;
        }

        @Override
        public void handleUserAudio(UserAudio userAudio) {
            String userId = userAudio.getUser().getId();
            Queue<byte[]> inputQueue = forwarder.inputQueueMap.get(userId);
            if (inputQueue == null) {
                return;
            }
            if (inputQueue.size() < 10) {
                inputQueue.add(userAudio.getAudioData(1));
            } if (inputQueue.size() > 2) {
                inputQueue.remove();
            }
        }

        /* Send Handling */

        @Override
        public boolean canProvide()
        {
            // If we have something in our buffer we can provide it to the send system
            Queue<byte[]> outputQueue = forwarder.outputQueueMap.get(receiveUserId);
            if (outputQueue == null) {
                return false;
            }
            return !outputQueue.isEmpty();
        }

        @Override
        public ByteBuffer provide20MsAudio()
        {
            // use what we have in our buffer to send audio as PCM
            byte[] data = forwarder.outputQueueMap.get(receiveUserId).poll();
            return data == null ? null : ByteBuffer.wrap(data); // Wrap this in a java.nio.ByteBuffer
        }

        @Override
        public boolean isOpus()
        {
            // since we send audio that is received from discord we don't have opus but PCM
            return false;
        }
    }

    public static class AudioAndMultiplier {
        private final byte[] audioData;
        private double[] multiplier;

        public AudioAndMultiplier (byte[] data, double[] multiplier) {
            this.audioData = data;
            this.multiplier = multiplier;
        }

        public byte[] getData() {
            return audioData;
        }

        public double[] getMultiplier() {
            return multiplier;
        }

        public int length() {
            return audioData.length;
        }

        public void setTotalSamples(int totalSamples) {
            double[] newMultiplier = new double[2];
            newMultiplier[0] = multiplier[0] / totalSamples;
            newMultiplier[1] = multiplier[1] / totalSamples;
            this.multiplier = newMultiplier;
        }
    }

    public static class UpdateQueue extends TimerTask {
        AudioHandler handler;
        PositionalDiscord plugin;
        final AudioForwarder forwarder;
        String outputUserId;
        UUID outputUserUUID;
        Queue<byte[]> outputQueue;
        HashMap<String, byte[]> lastKnownBytesMap = new HashMap<>();

        public UpdateQueue(AudioHandler caller) {
            handler = caller;
            plugin = handler.forwarder.plugin;
            forwarder = handler.forwarder;
            outputUserId = handler.receiveUserId;
            outputQueue = forwarder.outputQueueMap.get(outputUserId);
            outputUserUUID = forwarder.idsToUUIDs.get(outputUserId);
        }

        public void run() {
            if (outputQueue == null) {
                outputQueue = forwarder.outputQueueMap.get(outputUserId);
                return;
            }
            if (outputQueue.size() > 10) {
                return;
            }
            Player player = plugin.getServer().getPlayer(outputUserUUID);
            if (player == null) {
                return;
            }
            ArrayList<Player> nearbyPlayers = plugin.nearbyPlayers.get(player);
            if (nearbyPlayers == null) {
                return;
            }
            ArrayList<AudioAndMultiplier> combining = new ArrayList<>();
            for (Player nearbyPlayer : nearbyPlayers) {
                String discordId = forwarder.uuidsToIds.get(nearbyPlayer.getUniqueId());
                if (discordId == null) {
                    continue;
                }
                Queue<byte[]> otherUserQueue = forwarder.inputQueueMap.get(discordId);
                if (otherUserQueue == null || otherUserQueue.isEmpty()) {
                    continue;
                }
                byte[] data = otherUserQueue.peek();
                if (data == null) {
                    continue;
                }
                byte[] lastKnownBytes = lastKnownBytesMap.get(discordId);
                if (lastKnownBytes != null) {
                    if (lastKnownBytes == data) {
                        try {
                            otherUserQueue.remove();
                        } catch (NoSuchElementException ignored) {}
                        data = otherUserQueue.peek();
                        if (data == null) {
                            continue;
                        }
                    }
                }
                double[] multiplication = plugin.getAngularVolume(player, nearbyPlayer);
                lastKnownBytesMap.put(discordId, data);
                combining.add(new AudioAndMultiplier(data, multiplication));
            }
            if (!combining.isEmpty()) {
                byte[] combinedData = new byte[3840];
                int maxLength = combining.stream().mapToInt(AudioAndMultiplier::length).max().getAsInt();
                int sample;
                short toAdd;
                double multiplier;
                AudioAndMultiplier audioInfo;
                for (int i=0; i<maxLength; i+=2) {
                    sample = 0;
                    for (Iterator<AudioAndMultiplier> iterator = combining.iterator(); iterator.hasNext(); ) {
                        audioInfo = iterator.next();
                        byte[] audio = audioInfo.getData();
                        if (i < audio.length - 1) {
                            short buf1 = audio[i];
                            short buf2 = audio[i + 1];
                            buf1 = (short) ((buf1 & 0xff) << 8);
                            buf2 = (short) (buf2 & 0xff);
                            short currentShort = (short) (buf1 | buf2);
                            if (i % 4 == 0) {
                                multiplier = audioInfo.getMultiplier()[0];
                            } else {
                                multiplier = audioInfo.getMultiplier()[1];
                            }
                            sample += (int) (currentShort * multiplier);
                        } else {
                            iterator.remove();
                        }
                    }
                    if (sample > Short.MAX_VALUE) {
                        toAdd = Short.MAX_VALUE;
                    } else if (sample < Short.MIN_VALUE) {
                        toAdd = Short.MIN_VALUE;
                    } else {
                        toAdd = (short) sample;
                    }
                    combinedData[i] = (byte) (toAdd >> 8);
                    combinedData[i + 1] = (byte) (toAdd);
                }
                outputQueue.add(combinedData);
            }
        }
    }
}