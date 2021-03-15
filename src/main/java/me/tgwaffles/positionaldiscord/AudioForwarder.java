package me.tgwaffles.positionaldiscord;/*
 * Copyright 2015-2020 Austin Keener, Michael Ritter, Florian Spieß, and the JDA contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import net.dv8tion.jda.api.audio.AudioReceiveHandler;
import net.dv8tion.jda.api.audio.AudioSendHandler;
import net.dv8tion.jda.api.audio.UserAudio;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.events.message.guild.GuildMessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.managers.AudioManager;
import org.bukkit.entity.Player;
import sun.awt.image.ImageWatched;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.logging.Level;

public class AudioForwarder extends ListenerAdapter
{
    PositionalDiscord plugin;
    private final HashMap<String, UUID> idsToUUIDs = new HashMap<>();
    private final HashMap<UUID, String> uuidsToIds = new HashMap<>();
    public final HashMap<String, LinkedList<byte[]>> userToLinkedList = new HashMap<>();
    public final HashMap<String, Integer> userToAmounts = new HashMap<>();
    public final HashMap<String, ArrayList<String>> usersInQueue = new HashMap<>();

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
        }
    }

    private void registerUser(GuildMessageReceivedEvent event, String userName) {
        Player player = plugin.getServer().getPlayer(userName);
        if (player == null) {
            onUnknownUser(event.getChannel(), userName);
            return;
        }
        UUID uuid = player.getUniqueId();
        uuidsToIds.put(uuid, event.getAuthor().getId());
        idsToUUIDs.put(event.getAuthor().getId(), uuid);
        userToLinkedList.put(event.getAuthor().getId(), new LinkedList<>());
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
        // Note: None of these can be null due to our configuration with the JDABuilder!
        Member member = event.getMember();                              // Member is the context of the user for the specific guild, containing voice state and roles
        assert member != null;
        GuildVoiceState voiceState = member.getVoiceState();            // Check the current voice state of the user
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
                         // Use the channel the user is currently connected to
        if (channel != null) {
            plugin.log.log(Level.INFO, channel.toString());
        }
        if (channel != null)
        {
            connectTo(channel, event.getAuthor().getId());                                         // Join the channel of the user
            onConnecting(channel, event.getChannel());                  // Tell the user about our success
        }
        else
        {
            onUnknownChannel(event.getChannel()); // Tell the user about our failure
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
        textChannel.sendMessage("Connecting to " + channel.getName()).queue(); // never forget to queue()!
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
        channel.sendMessage("Unable to connect to ``your voice channel``, no such channel!").queue(); // never forget to queue()!
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
        // Get an audio manager for this guild, this will be created upon first use for each guild
        AudioManager audioManager = guild.getAudioManager();

        audioManager.closeAudioConnection();

        // Create our Send/Receive handler for the audio connection
        AudioHandler handler = new AudioHandler(this, callerId);

        // The order of the following instructions does not matter!

        // Set the sending handler to our echo system
        audioManager.setSendingHandler(handler);
        // Set the receiving handler to the same echo system, otherwise we can't echo anything
        audioManager.setReceivingHandler(handler);
        // Connect to the voice channel
        audioManager.openAudioConnection(channel);
    }

    public static class AudioHandler implements AudioSendHandler, AudioReceiveHandler
    {
        /*
            All methods in this class are called by JDA threads when resources are available/ready for processing.
            The receiver will be provided with the latest 20ms of PCM stereo audio
            Note you can receive even while setting yourself to deafened!
            The sender will provide 20ms of PCM stereo audio (pass-through) once requested by JDA
            When audio is provided JDA will automatically set the bot to speaking!
         */
        private final AudioForwarder forwarder;
        private final String receiveUserId;
        public AudioHandler(AudioForwarder caller, String callerId) {
            forwarder = caller;
            receiveUserId = callerId;
            forwarder.plugin.log.log(Level.INFO, "registered audio");
        }

        /* Receive Handling */

        @Override // combine multiple user audio-streams into a single one
        public boolean canReceiveCombined() {
            // limit queue to 10 entries, if that is exceeded we can not receive more until the send system catches up
            return false;
        }

        //        Disable per-user audio since we want to echo the entire channel and not specific users.
        @Override // give audio separately for each user that is speaking
        public boolean canReceiveUser()
        {
            // this is not useful if we want to echo the audio of the voice channel, thus disabled for this purpose
            return true;
        }

        public byte[] doRegularMultiplier(byte[] newData, double[] positionalMultiplier) {
            byte[] combinedAudio;
            byte[] positionalisedData = new byte[newData.length];
            for (int i=0; i<newData.length; i+=2) {
                short buf1 = newData[i];
                short buf2 = newData[i + 1];
                buf1 = (short) ((buf1 & 0xff) << 8);
                buf2 = (short) (buf2 & 0xff);
                short resultantShort = (short) (buf1 | buf2);
                if (i % 4 == 0) {
                    resultantShort = (short) (resultantShort * positionalMultiplier[0]);
                } else {
                    resultantShort = (short) (resultantShort * positionalMultiplier[1]);
                }
                positionalisedData[i] = (byte) (resultantShort >> 8);
                positionalisedData[i + 1] = (byte) (resultantShort);
            }
            combinedAudio = positionalisedData;
            return combinedAudio;
        }

        public void combineAudio(String userId, byte[] newData, double[] positionalMultiplier, String inputId) {
            Integer previousAmount = forwarder.userToAmounts.get(userId);
            if (previousAmount == null) {
                previousAmount = 0;
            }
            previousAmount += 1;
            forwarder.userToAmounts.put(userId, previousAmount);
            LinkedList<byte[]> userList = forwarder.userToLinkedList.get(userId);
            byte[] combinedAudio;
            if (userList.size() > 10) {
                return;
            } else if (userList.size() == 0) {
                combinedAudio = doRegularMultiplier(newData, positionalMultiplier);
            } else {
                byte[] oldAudio;
                ArrayList<String> usersInTheQueue = forwarder.usersInQueue.get(userId);
                if (usersInTheQueue != null && usersInTheQueue.contains(inputId)) {
                    forwarder.userToAmounts.put(userId, 1);
                    combinedAudio = doRegularMultiplier(newData, positionalMultiplier);
                    usersInTheQueue.clear();
                    usersInTheQueue.add(inputId);
                    forwarder.usersInQueue.put(userId, usersInTheQueue);
                } else {
                    try {
                        oldAudio = userList.removeLast();
                        combinedAudio = new byte[oldAudio.length];
                        double oldMultiplier = (double) (previousAmount - 1) / previousAmount;
                        double leftPositionalMultiplier = positionalMultiplier[0] / previousAmount;
                        double rightPositionalMultiplier = positionalMultiplier[1] / previousAmount;
                        for (int i=0; i<oldAudio.length; i+=2) {
                            short buf1 = oldAudio[i];
                            short buf2 = oldAudio[i + 1];
                            buf1 = (short) ((buf1 & 0xff) << 8);
                            buf2 = (short) (buf2 & 0xff);
                            short oldShort = (short) (buf1 | buf2);
                            buf1 = newData[i];
                            buf2 = newData[i + 1];
                            buf1 = (short) ((buf1 & 0xff) << 8);
                            buf2 = (short) (buf2 & 0xff);
                            short newShort = (short) (buf1 | buf2);
                            short combinedShort;
                            if (i % 4 == 0) {
                                combinedShort = (short) (oldShort * oldMultiplier + newShort * leftPositionalMultiplier);
                            } else {
                                combinedShort = (short) (oldShort * oldMultiplier + newShort * rightPositionalMultiplier);
                            }
                            combinedAudio[i] = (byte) (combinedShort >> 8);
                            combinedAudio[i + 1] = (byte) (combinedShort);
                        }
                    } catch (NoSuchElementException e) {
                        combinedAudio = doRegularMultiplier(newData, positionalMultiplier);
                    }
                }

            }
            userList.addLast(combinedAudio);
        }

        public void addToQueues(String inputUserID, byte[] inputData) {
            UUID playerUUID = forwarder.idsToUUIDs.get(inputUserID);
            if (playerUUID == null) {
                return;
            }
            Player player = forwarder.plugin.getServer().getPlayer(playerUUID);
            if (player == null) {
                return;
            }
            ArrayList<Player> nearbyPlayers = forwarder.plugin.nearbyPlayers.get(player);
            if (nearbyPlayers == null) {
                return;
            }
            for (Player nearbyPlayer : nearbyPlayers) {
                String discordId = forwarder.uuidsToIds.get(nearbyPlayer.getUniqueId());
                if (discordId == null) {
                    continue;
                }
                double[] multiplication = forwarder.plugin.getAngularVolume(nearbyPlayer, player);
                combineAudio(discordId, inputData, multiplication, inputUserID);
            }
        }

        @Override
        public void handleUserAudio(UserAudio userAudio) {
            String userId = userAudio.getUser().getId();
            addToQueues(userId, userAudio.getAudioData(1));
        }


        /* Send Handling */

        @Override
        public boolean canProvide()
        {
            // If we have something in our buffer we can provide it to the send system
            LinkedList<byte[]> userList = forwarder.userToLinkedList.get(receiveUserId);
            if (userList == null) {
                return false;
            }
            return userList.size() != 0;
        }

        @Override
        public ByteBuffer provide20MsAudio()
        {
            byte[] data;
            // use what we have in our buffer to send audio as PCM
            try {
                data = forwarder.userToLinkedList.get(receiveUserId).removeFirst();
            } catch (NoSuchElementException e) {
                data = null;
            }
            return data == null ? null : ByteBuffer.wrap(data); // Wrap this in a java.nio.ByteBuffer
        }

        @Override
        public boolean isOpus()
        {
            // since we send audio that is received from discord we don't have opus but PCM
            return false;
        }
    }
}