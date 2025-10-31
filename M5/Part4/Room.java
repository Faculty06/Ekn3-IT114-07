package M5.Part4;

import java.util.concurrent.ConcurrentHashMap;

import M5.Part4.TextFX.Color;

public class Room {
    private final String name;// unique name of the Room
    private volatile boolean isRunning = false;
    private final ConcurrentHashMap<Long, ServerThread> clientsInRoom = new ConcurrentHashMap<Long, ServerThread>();

    public final static String LOBBY = "lobby";

    private void info(String message) {
        System.out.println(TextFX.colorize(String.format("Room[%s]: %s", name, message), Color.PURPLE));
    }

    public Room(String name) {
        this.name = name;
        isRunning = true;
        info("Created");
    }

    public String getName() {
        return this.name;
    }

    protected synchronized void addClient(ServerThread client) {
        if (!isRunning) { // block action if Room isn't running
            return;
        }
        if (clientsInRoom.containsKey(client.getClientId())) {
            info("Attempting to add a client that already exists in the room");
            return;
        }
        clientsInRoom.put(client.getClientId(), client);
        client.setCurrentRoom(this);
        // notify clients of someone joining
        // relay(null, String.format("User[%s] joined the room", client.getClientId()));
        joinStatusRelay(client, true);

    }

    protected synchronized void removeClient(ServerThread client) {
        if (!isRunning) { // block action if Room isn't running
            return;
        }
        if (!clientsInRoom.containsKey(client.getClientId())) {
            info("Attempting to remove a client that doesn't exist in the room");
            return;
        }
        ServerThread removedClient = clientsInRoom.get(client.getClientId());
        if (removedClient != null) {
            // notify clients of someone joining
            joinStatusRelay(removedClient, false);
            clientsInRoom.remove(client.getClientId());
            autoCleanup();
        }
    }

    private void joinStatusRelay(ServerThread client, boolean didJoin) {
        clientsInRoom.values().removeIf(serverThread -> {
            String formattedMessage = String.format("Room[%s] %s %s the room",
                    getName(),
                    client.getClientId() == serverThread.getClientId() ? "You"
                            : String.format("User[%s]", client.getClientId()),
                    didJoin ? "joined" : "left");
            boolean failedToSend = !serverThread.sendToClient(formattedMessage);
            if (failedToSend) {
                System.out.println(
                        String.format("Removing disconnected client[%s] from list", serverThread.getClientId()));
                disconnect(serverThread);
            }
            return failedToSend;
        });
    }

    /**
     * Sends a basic String message from the sender to all connectedClients
     * Internally calls processCommand and evaluates as necessary.
     * Note: Clients that fail to receive a message get removed from
     * connectedClients.
     * Adding the synchronized keyword ensures that only one thread can execute
     * these methods at a time,
     * preventing concurrent modification issues and ensuring thread safety
     * 
     * @param message
     * @param sender  ServerThread (client) sending the message or null if it's a
     *                server-generated message
     */
    protected synchronized void relay(ServerThread sender, String message) {
        if (!isRunning) { // block action if Room isn't running
            return;
        }

        // Note: any desired changes to the message must be done before this line
        String senderString = sender == null ? String.format("Room[%s]", getName())
                : String.format("User[%s]", sender.getClientId());
        // Note: formattedMessage must be final (or effectively final) since outside
        // scope can't be changed inside a callback function (see removeIf() below)
        final String formattedMessage = String.format("%s: %s", senderString, message);

        // loop over clients and send out the message; remove client if message failed
        // to be sent
        // Note: this uses a lambda expression for each item in the values() collection,
        // it's one way we can safely remove items during iteration
        info(String.format("sending message to %s recipients: %s", clientsInRoom.size(), formattedMessage));

        clientsInRoom.values().removeIf(serverThread -> {
            boolean failedToSend = !serverThread.sendToClient(formattedMessage);
            if (failedToSend) {
                System.out.println(
                        String.format("Removing disconnected client[%s] from list", serverThread.getClientId()));
                disconnect(serverThread);
            }
            return failedToSend;
        });
    }

    /**
     * Takes a ServerThread and removes them from the Server
     * Adding the synchronized keyword ensures that only one thread can execute
     * these methods at a time,
     * preventing concurrent modification issues and ensuring thread safety
     * 
     * @param client
     */
    private synchronized void disconnect(ServerThread client) {
        if (!isRunning) { // block action if Room isn't running
            return;
        }
        ServerThread disconnectingServerThread = clientsInRoom.remove(client.getClientId());
        if (disconnectingServerThread != null) {
            disconnectingServerThread.disconnect();
            relay(null, "User[" + disconnectingServerThread.getClientId() + "] disconnected");
        }
        autoCleanup();
    }

    protected synchronized void disconnectAll() {
        info("Disconnect All triggered");
        if (!isRunning) {
            return;
        }
        clientsInRoom.values().removeIf(client -> {
            disconnect(client);
            return true;
        });
        info("Disconnect All finished");
    }

    /**
     * Attempts to close the room to free up resources if it's empty
     */
    private void autoCleanup() {
        if (!Room.LOBBY.equalsIgnoreCase(name) && clientsInRoom.isEmpty()) {
            close();
        }
    }

    public void close() {
        // attempt to gracefully close and migrate clients
        if (!clientsInRoom.isEmpty()) {
            relay(null, "Room is shutting down, migrating to lobby");
            info(String.format("migrating %s clients", clientsInRoom.size()));
            clientsInRoom.values().removeIf(client -> {
                try {
                    Server.INSTANCE.joinRoom(Room.LOBBY, client);
                } catch (RoomNotFoundException e) {
                    e.printStackTrace();
                    // TODO, fill in, this shouldn't happen though
                }
                return true;
            });
        }
        Server.INSTANCE.removeRoom(this);
        isRunning = false;
        clientsInRoom.clear();
        info(String.format("closed"));
    }

    // start handle methods
    public void handleCreateRoom(ServerThread sender, String roomName) {
        try {
            Server.INSTANCE.createRoom(roomName);
            Server.INSTANCE.joinRoom(roomName, sender);
        } catch (RoomNotFoundException e) {
            info("Room wasn't found (this shouldn't happen)");
            e.printStackTrace();
        } catch (DuplicateRoomException e) {
            sender.sendToClient(String.format("Room %s already exists", roomName));
        }
    }

    public void handleJoinRoom(ServerThread sender, String roomName) {
        try {
            Server.INSTANCE.joinRoom(roomName, sender);
        } catch (RoomNotFoundException e) {
            sender.sendToClient(String.format("Room %s doesn't exist", roomName));
        }
    }

    /**
     * Expose access to the disconnect action
     * 
     * @param serverThread
     */
    protected synchronized void handleDisconnect(ServerThread sender) {
        disconnect(sender);
    }

    protected synchronized void handleReverseText(ServerThread sender, String text) {
        StringBuilder sb = new StringBuilder(text);
        sb.reverse();
        String rev = sb.toString();
        relay(sender, rev);
    }

    protected synchronized void handleMessage(ServerThread sender, String text) {
        relay(sender, text);
    }
    // end handle methods
}
