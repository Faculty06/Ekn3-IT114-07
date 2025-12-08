package Projects.Client;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import Projects.CommonFiles.Command;
import Projects.CommonFiles.ConnectionPayload;
import Projects.CommonFiles.Constants;
import Projects.CommonFiles.GameStatePayload;
import Projects.CommonFiles.LoggerUtil;
import Projects.CommonFiles.Payload;
import Projects.CommonFiles.PayloadType;
import Projects.CommonFiles.Phase;
import Projects.CommonFiles.PointsPayload;
import Projects.CommonFiles.ReadyPayload;
import Projects.CommonFiles.RoomAction;
import Projects.CommonFiles.RoomResultPayload;
import Projects.CommonFiles.TextFX;
import Projects.CommonFiles.User;
import Projects.CommonFiles.TextFX.Color;

/**
 * Demoing bi-directional communication between client and server in a
 * multi-client scenario
 */
public enum Client {
    INSTANCE;

    {
        // statically initialize the client-side LoggerUtil
        LoggerUtil.LoggerConfig config = new LoggerUtil.LoggerConfig();
        config.setFileSizeLimit(2048 * 1024); // 2MB
        config.setFileCount(1);
        config.setLogLocation("client.log");
        // Set the logger configuration
        LoggerUtil.INSTANCE.setConfig(config);
    }

    private Socket server = null;
    private ObjectOutputStream out = null;
    private ObjectInputStream in = null;
    final Pattern ipAddressPattern = Pattern
            .compile("/connect\\s+(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}:\\d{3,5})");
    final Pattern localhostPattern = Pattern.compile("/connect\\s+(localhost:\\d{3,5})");
    private volatile boolean isRunning = true; // volatile for thread-safe visibility
    private final ConcurrentHashMap<Long, User> knownClients = new ConcurrentHashMap<Long, User>();
    private User myUser = new User();
    private Phase currentPhase = Phase.READY;

    // --- UI state fields ---
    private volatile GameStatePayload latestGameState = null;
    private volatile String lastTurnMessage = null;
    private volatile long currentTurnClientId = Constants.DEFAULT_CLIENT_ID;

    // buffer of game-related messages (for Game Events panel)
    private final CopyOnWriteArrayList<String> gameEvents = new CopyOnWriteArrayList<>();

    /**
     * UI helper: drain all accumulated game events since last call.
     */
    public List<String> drainGameEvents() {
        List<String> copy = new java.util.ArrayList<>(gameEvents);
        gameEvents.clear();
        return copy;
    }

    // ---------------------------------------------------------------------
    // UI accessors
    // ---------------------------------------------------------------------

    public ConcurrentHashMap<Long, User> getKnownClients() {
        return knownClients;
    }

    public User getMyUser() {
        return myUser;
    }

    public Phase getCurrentPhase() {
        return currentPhase;
    }

    public GameStatePayload getLatestGameState() {
        return latestGameState;
    }

    public String getLastTurnMessage() {
        return lastTurnMessage;
    }

    public long getCurrentTurnClientId() {
        return currentTurnClientId;
    }

    /**
     * UI-friendly connect:
     *  - opens socket
     *  - starts listenToServer()
     *  - sends username to server
     */
    public boolean connect(String host, int port, String username) {
        myUser.setClientName(username);
        boolean ok = connect(host, port);
        if (ok) {
            try {
                sendClientName(username);
            } catch (IOException e) {
                LoggerUtil.INSTANCE.severe("Failed to send client name", e);
                return false;
            }
        }
        return ok;
    }

    // ---------------------------------------------------------------------
    // UI helper methods (core implementations)
    // ---------------------------------------------------------------------

    public void sendConfigFromUI(boolean removeStrikeOnCorrectGuess, boolean hardMode) {
        try {
            String message = String.format("/config removeStrike=%s hardMode=%s",
                    removeStrikeOnCorrectGuess, hardMode);
            sendDoTurn(message);
        } catch (IOException e) {
            LoggerUtil.INSTANCE.severe("Failed to send config", e);
        }
    }

    /**
     * UI helper: create a game room with the given name.
     */
    public void createRoomFromUI(String roomName) {
        try {
            if (roomName == null || roomName.isBlank()) {
                LoggerUtil.INSTANCE.warning("Room name cannot be empty");
                return;
            }
            sendRoomAction(roomName.trim(), RoomAction.CREATE);
        } catch (IOException e) {
            LoggerUtil.INSTANCE.severe("Failed to create room", e);
        }
    }

    /**
     * UI helper: join an existing game room with the given name.
     */
    public void joinRoomFromUI(String roomName) {
        try {
            if (roomName == null || roomName.isBlank()) {
                LoggerUtil.INSTANCE.warning("Room name cannot be empty");
                return;
            }
            sendRoomAction(roomName.trim(), RoomAction.JOIN);
        } catch (IOException e) {
            LoggerUtil.INSTANCE.severe("Failed to join room", e);
        }
    }

    /**
     * Wrapper for UI to send chat messages.
     */
    public void sendChatFromUI(String message) {
        try {
            if (message != null && !message.isBlank()) {
                sendMessage(message);
            }
        } catch (IOException e) {
            LoggerUtil.INSTANCE.severe("Failed to send chat", e);
        }
    }

    /**
     * Wrapper for UI to toggle ready.
     */
    public void toggleReadyFromUI() {
        try {
            sendReady();
        } catch (IOException e) {
            LoggerUtil.INSTANCE.severe("Failed to send ready", e);
        }
    }

    /**
     * Wrapper for UI to send a single-letter guess as a TURN.
     * Uses /letter <char> to match console behavior and server parsing.
     */
    public void guessLetterFromUI(char c) {
        try {
            String letter = String.valueOf(Character.toUpperCase(c));
            sendDoTurn("/letter " + letter);
        } catch (IOException e) {
            LoggerUtil.INSTANCE.severe("Failed to send letter guess", e);
        }
    }

    /**
     * Wrapper for UI to send a full-word guess as a TURN.
     * Uses /guess <word> to match console behavior and server parsing.
     */
    public void guessWordFromUI(String word) {
        try {
            if (word != null && !word.isBlank()) {
                sendDoTurn("/guess " + word.trim());
            }
        } catch (IOException e) {
            LoggerUtil.INSTANCE.severe("Failed to send word guess", e);
        }
    }

    /**
     * Mark away/back via a TURN payload.
     * The server should interpret "/away" and "/back".
     */
    public void setAwayFromUI(boolean away) {
        try {
            sendDoTurn(away ? "/away" : "/back");
        } catch (IOException e) {
            LoggerUtil.INSTANCE.severe("Failed to send away/back", e);
        }
    }

    private void error(String message) {
        LoggerUtil.INSTANCE.severe(TextFX.colorize(String.format("%s", message), Color.RED));
    }

    // needs to be private now that the enum logic is handling this
    private Client() {
        LoggerUtil.INSTANCE.info("Client Created");
    }

    public boolean isConnected() {
        if (server == null) {
            return false;
        }
        // https://stackoverflow.com/a/10241044
        // Note: these check the client's end of the socket connect; therefore they
        // don't really help determine if the server had a problem
        // and is just for lesson's sake
        return server.isConnected() && !server.isClosed() && !server.isInputShutdown() && !server.isOutputShutdown();
    }

    /**
     * Takes an IP address and a port to attempt a socket connection to a server.
     *
     * @param address
     * @param port
     * @return true if connection was successful
     */
    private boolean connect(String address, int port) {
        try {
            server = new Socket(address, port);
            // channel to send to server
            out = new ObjectOutputStream(server.getOutputStream());
            // channel to listen to server
            in = new ObjectInputStream(server.getInputStream());
            LoggerUtil.INSTANCE.info("Client connected");
            // Use CompletableFuture to run listenToServer() in a separate thread
            CompletableFuture.runAsync(this::listenToServer);
        } catch (UnknownHostException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return isConnected();
    }

    /**
     * <p>
     * Check if the string contains the <i>connect</i> command
     * followed by an IP address and port or localhost and port.
     * </p>
     * <p>
     * Example format: 123.123.123.123:3000
     * </p>
     * <p>
     * Example format: localhost:3000
     * </p>
     * https://www.w3schools.com/java/java_regex.asp
     *
     * @param text
     * @return true if the text is a valid connection command
     */
    private boolean isConnection(String text) {
        Matcher ipMatcher = ipAddressPattern.matcher(text);
        Matcher localhostMatcher = localhostPattern.matcher(text);
        return ipMatcher.matches() || localhostMatcher.matches();
    }

    /**
     * Controller for handling various text commands.
     * <p>
     * Add more here as needed
     * </p>
     *
     * @param text
     * @return true if the text was a command or triggered a command
     * @throws IOException
     */

    private boolean processClientCommand(String text) throws IOException {
        boolean wasCommand = false;
        if (text.startsWith(Constants.COMMAND_TRIGGER)) {
            text = text.substring(1); // remove the /
            if (isConnection("/" + text)) {
                if (myUser.getClientName() == null || myUser.getClientName().isEmpty()) {
                    LoggerUtil.INSTANCE.warning(
                            TextFX.colorize("Please set your name via /name <name> before connecting", Color.RED));
                    return true;
                }
                // replaces multiple spaces with a single space
                // splits on the space after connect (gives us host and port)
                // splits on : to get host as index 0 and port as index 1
                String[] parts = text.trim().replaceAll(" +", " ").split(" ")[1].split(":");
                connect(parts[0].trim(), Integer.parseInt(parts[1].trim()));
                sendClientName(myUser.getClientName());// sync follow-up data (handshake)
                wasCommand = true;
            } else if (text.startsWith(Command.NAME.command)) {
                text = text.replace(Command.NAME.command, "").trim();
                if (text == null || text.length() == 0) {
                    LoggerUtil.INSTANCE
                            .warning(TextFX.colorize("This command requires a name as an argument", Color.RED));
                    return true;
                }
                myUser.setClientName(text);// temporary until we get a response from the server
                LoggerUtil.INSTANCE.info(TextFX.colorize(String.format("Name set to %s", myUser.getClientName()),
                        Color.YELLOW));
                wasCommand = true;
            } else if (text.equalsIgnoreCase(Command.LIST_USERS.command)) {
                String message = TextFX.colorize("Known clients:\n", Color.CYAN);
                LoggerUtil.INSTANCE.info(TextFX.colorize("Known clients:", Color.CYAN));
                message += String.join("\n", knownClients.values().stream()
                        .map(c -> String.format("%s %s %s %s",
                                c.getDisplayName(),
                                c.getClientId() == myUser.getClientId() ? " (you)" : "",
                                c.isReady() ? "[x]" : "[ ]",
                                c.didTakeTurn() ? "[T]" : "[ ]"))
                        .toList());
                LoggerUtil.INSTANCE.info(message);
                wasCommand = true;
            } else if (Command.QUIT.command.equalsIgnoreCase(text)) {
                close();
                wasCommand = true;
            } else if (Command.DISCONNECT.command.equalsIgnoreCase(text)) {
                sendDisconnect();
                wasCommand = true;
            } else if (text.startsWith(Command.REVERSE.command)) {
                text = text.replace(Command.REVERSE.command, "").trim();
                sendReverse(text);
                wasCommand = true;
            } else if (text.startsWith(Command.CREATE_ROOM.command)) {
                text = text.replace(Command.CREATE_ROOM.command, "").trim();
                if (text == null || text.length() == 0) {
                    LoggerUtil.INSTANCE
                            .warning(TextFX.colorize("This command requires a room name as an argument", Color.RED));
                    return true;
                }
                sendRoomAction(text, RoomAction.CREATE);
                wasCommand = true;
            } else if (text.startsWith(Command.JOIN_ROOM.command)) {
                text = text.replace(Command.JOIN_ROOM.command, "").trim();
                if (text == null || text.length() == 0) {
                    LoggerUtil.INSTANCE
                            .warning(TextFX.colorize("This command requires a room name as an argument", Color.RED));
                    return true;
                }
                sendRoomAction(text, RoomAction.JOIN);
                wasCommand = true;
            } else if (text.startsWith(Command.LEAVE_ROOM.command) || text.startsWith("leave")) {
                sendRoomAction(text, RoomAction.LEAVE);
                wasCommand = true;
            } else if (text.startsWith(Command.LIST_ROOMS.command)) {
                text = text.replace(Command.LIST_ROOMS.command, "").trim();

                sendRoomAction(text, RoomAction.LIST);
                wasCommand = true;
            } else if (text.equalsIgnoreCase(Command.READY.command)) {
                sendReady();
                wasCommand = true;
            } else if (text.startsWith(Command.EXAMPLE_TURN.command)) {
                text = text.replace(Command.EXAMPLE_TURN.command, "").trim();

                sendDoTurn(text);
                wasCommand = true;

            } else if (text.startsWith(Command.GUESS.command)) {
                text = text.replace(Command.GUESS.command, "").trim();
                if (text.isEmpty()) {
                    LoggerUtil.INSTANCE.warning(TextFX.colorize("Usage: /guess <word>", Color.RED));
                    return true;
                }
                sendDoTurn("/guess " + text);
                wasCommand = true;
            } else if (text.startsWith(Command.LETTER.command)) {
                text = text.replace(Command.LETTER.command, "").trim();
                if (text.isEmpty() || text.length() > 1) {
                    LoggerUtil.INSTANCE.warning(TextFX.colorize("Usage: /letter <char>", Color.RED));
                    return true;
                }
                sendDoTurn("/letter " + text);
                wasCommand = true;
            } else if (text.equalsIgnoreCase(Command.SKIP.command)) {
                sendDoTurn("/skip");
                wasCommand = true;
            }
        }

        return wasCommand;
    }

    // Start Send*() methods
    private void sendDoTurn(String text) throws IOException {
        // NOTE for now using ReadyPayload as it has the necessary properties
        ReadyPayload rp = new ReadyPayload();
        rp.setPayloadType(PayloadType.TURN);
        rp.setReady(true);
        rp.setMessage(text);
        sendToServer(rp);
    }

    /**
     * Sends the client's intent to be ready.
     * Can also be used to toggle the ready state if coded on the server-side
     *
     * @throws IOException
     */
    private void sendReady() throws IOException {
        ReadyPayload rp = new ReadyPayload();
        sendToServer(rp);
    }

    /**
     * Sends a room action to the server
     *
     * @param roomName
     * @param roomAction (join, leave, create)
     * @throws IOException
     */
    private void sendRoomAction(String roomName, RoomAction roomAction) throws IOException {
        Payload payload = new Payload();
        payload.setMessage(roomName);
        switch (roomAction) {
            case RoomAction.CREATE:
                payload.setPayloadType(PayloadType.ROOM_CREATE);
                break;
            case RoomAction.JOIN:
                payload.setPayloadType(PayloadType.ROOM_JOIN);
                break;
            case RoomAction.LEAVE:
                payload.setPayloadType(PayloadType.ROOM_LEAVE);
                break;
            case RoomAction.LIST:
                payload.setPayloadType(PayloadType.ROOM_LIST);
                break;
            default:
                LoggerUtil.INSTANCE.warning(TextFX.colorize("Invalid room action", Color.RED));
                break;
        }
        sendToServer(payload);
    }

    /**
     * Sends a reverse message action to the server
     */
    private void sendReverse(String message) throws IOException {
        Payload payload = new Payload();
        payload.setMessage(message);
        payload.setPayloadType(PayloadType.REVERSE);
        sendToServer(payload);
    }

    /**
     * Sends a disconnect action to the server
     */
    private void sendDisconnect() throws IOException {
        Payload payload = new Payload();
        payload.setPayloadType(PayloadType.DISCONNECT);
        sendToServer(payload);
    }

    /**
     * Sends a message to the server
     */
    private void sendMessage(String message) throws IOException {
        Payload payload = new Payload();
        payload.setMessage(message);
        payload.setPayloadType(PayloadType.MESSAGE);
        sendToServer(payload);
    }

    /**
     * Sends the client's name to the server (what the user desires to be called)
     */
    private void sendClientName(String name) throws IOException {
        ConnectionPayload payload = new ConnectionPayload();
        payload.setClientName(name);
        payload.setPayloadType(PayloadType.CLIENT_CONNECT);
        sendToServer(payload);
    }

    private void sendToServer(Payload payload) throws IOException {
        if (isConnected()) {
            out.writeObject(payload);
            out.flush();
        } else {
            LoggerUtil.INSTANCE.warning(
                    "Not connected to server (hint: type `/connect host:port` without the quotes and replace host/port with the necessary info)");
        }
    }
    // End Send*() methods

    public void start() throws IOException {
        LoggerUtil.INSTANCE.info("Client starting");

        CompletableFuture<Void> inputFuture = CompletableFuture.runAsync(this::listenToInput);

        inputFuture.join();
    }

    /**
     * Listens for messages from the server
     */
    private void listenToServer() {
        try {
            while (isRunning && isConnected()) {
                Payload fromServer = (Payload) in.readObject(); // blocking read
                if (fromServer != null) {
                    processPayload(fromServer);
                } else {
                    LoggerUtil.INSTANCE.info("Server disconnected");
                    break;
                }
            }
        } catch (ClassCastException | ClassNotFoundException cce) {
            LoggerUtil.INSTANCE.severe("Error reading object as specified type:", cce);
        } catch (IOException e) {
            if (isRunning) {
                LoggerUtil.INSTANCE.warning("Connection dropped");
                e.printStackTrace();
            }
        } catch (Exception e) {
            LoggerUtil.INSTANCE.severe("Unexpected error in listenToServer()", e);
        } finally {
            closeServerConnection();
        }
        LoggerUtil.INSTANCE.info("listenToServer thread stopped");
    }

    private void processPayload(Payload payload) {
        switch (payload.getPayloadType()) {
            case CLIENT_CONNECT:// unused
                break;
            case CLIENT_ID:
                processClientData(payload);
                break;
            case DISCONNECT:
                processDisconnect(payload);
                break;
            case MESSAGE:
                processMessage(payload);
                break;
            case REVERSE:
                processReverse(payload);
                break;
            case ROOM_CREATE: // unused
                break;
            case ROOM_JOIN:
                processRoomAction(payload);
                break;
            case ROOM_LEAVE:
                processRoomAction(payload);
                break;
            case SYNC_CLIENT:
                processRoomAction(payload);
                break;
            case ROOM_LIST:
                processRoomsList(payload);
                break;
            case PayloadType.READY:
                processReadyStatus(payload, false);
                break;
            case PayloadType.SYNC_READY:
                processReadyStatus(payload, true);
                break;
            case PayloadType.RESET_READY:
                processResetReady();
                break;
            case PayloadType.PHASE:
                processPhase(payload);
                break;
            case PayloadType.TURN:
            case PayloadType.SYNC_TURN:
                processTurn(payload);
                break;
            case PayloadType.RESET_TURN:
                processResetTurn();
                break;
            case POINTS:
                processPoints(payload);
                break;
            case GAME_STATE:
                processGameState(payload);
                break;
            default:
                LoggerUtil.INSTANCE.warning(TextFX.colorize("Unhandled payload type", Color.YELLOW));
                break;
        }
    }

    // Start process*() methods
    // ekn3 20/11/25

    private void processPoints(Payload payload) {
        if (payload instanceof PointsPayload) {
            PointsPayload pp = (PointsPayload) payload;
            if (knownClients.containsKey(pp.getClientId())) {
                knownClients.get(pp.getClientId()).setPoints(pp.getPoints());
                LoggerUtil.INSTANCE.info(TextFX.colorize(String.format("Client %s now has %d points",
                        pp.getClientId(), pp.getPoints()), Color.YELLOW));
            }
        }
    }

    private void processGameState(Payload payload) {
        if (payload instanceof GameStatePayload) {
            GameStatePayload gsp = (GameStatePayload) payload;
            latestGameState = gsp;
            LoggerUtil.INSTANCE.info(TextFX.colorize(
                    String.format("HANGMAN: %s | Strikes: %d/%d",
                            gsp.getHiddenWord(), gsp.getCurrentStrikes(), gsp.getMaxStrikes()),
                    Color.PURPLE));
        }
    }

    private void processResetTurn() {
        knownClients.values().forEach(cp -> cp.setTookTurn(false));
        System.out.println("Turn status reset for everyone");
    }

    private void processTurn(Payload payload) {
        // Note: For now assuming ReadyPayload (this may be changed later)
        if (!(payload instanceof ReadyPayload)) {
            error("Invalid payload subclass for processTurn");
            return;
        }
        ReadyPayload rp = (ReadyPayload) payload;
        if (!knownClients.containsKey(rp.getClientId())) {
            LoggerUtil.INSTANCE.severe(String.format("Received turn status for client id %s who is not known",
                    rp.getClientId()));
            return;
        }
        User cp = knownClients.get(rp.getClientId());
        cp.setTookTurn(rp.isReady());

        // UI: track current turn + message
        currentTurnClientId = cp.getClientId();
        lastTurnMessage = rp.getMessage();

        if (payload.getPayloadType() != PayloadType.SYNC_TURN) {
            String message = String.format("%s %s their turn", cp.getDisplayName(),
                    cp.didTakeTurn() ? "took" : "reset");
            LoggerUtil.INSTANCE.info(message);
        }
    }

    private void processPhase(Payload payload) {
        currentPhase = Enum.valueOf(Phase.class, payload.getMessage());
        System.out.println(TextFX.colorize("Current phase is " + currentPhase.name(), Color.YELLOW));

        // When the server returns to READY, a session has ended and we're back
        // at the lobby/ready-check state. Reset client-side game data.
        if (currentPhase == Phase.READY) {
            // Reset all known clients' game state
            knownClients.values().forEach(u -> {
                u.setPoints(0);
                u.setReady(false);
                u.setTookTurn(false);
                u.setAway(false);
                u.setSpectator(false);
                u.setSolutionWord(null);
            });

            // Clear cached game state
            latestGameState = null;
            lastTurnMessage = null;
            currentTurnClientId = Constants.DEFAULT_CLIENT_ID;
            gameEvents.clear();
        }
    }

    private void processResetReady() {
        knownClients.values().forEach(cp -> cp.setReady(false));
        System.out.println("Ready status reset for everyone");
    }

    private void processReadyStatus(Payload payload, boolean isQuiet) {
        if (!(payload instanceof ReadyPayload)) {
            error("Invalid payload subclass for processRoomsList");
            return;
        }
        ReadyPayload rp = (ReadyPayload) payload;
        if (!knownClients.containsKey(rp.getClientId())) {
            LoggerUtil.INSTANCE.severe(String.format("Received ready status [%s] for client id %s who is not known",
                    rp.isReady() ? "ready" : "not ready", rp.getClientId()));
            return;
        }
        User cp = knownClients.get(rp.getClientId());
        cp.setReady(rp.isReady());
        if (!isQuiet) {
            System.out.println(
                    String.format("%s is %s", cp.getDisplayName(),
                            rp.isReady() ? "ready" : "not ready"));
        }
    }

    private void processRoomsList(Payload payload) {
        if (!(payload instanceof RoomResultPayload)) {
            error("Invalid payload subclass for processRoomsList");
            return;
        }
        RoomResultPayload rrp = (RoomResultPayload) payload;
        List<String> rooms = rrp.getRooms();
        if (rooms == null || rooms.size() == 0) {
            LoggerUtil.INSTANCE.warning(
                    TextFX.colorize("No rooms found matching your query",
                            Color.RED));
            return;
        }
        LoggerUtil.INSTANCE.info(TextFX.colorize("Room Results:", Color.PURPLE));
        LoggerUtil.INSTANCE.info(
                String.join(System.lineSeparator(), rooms));
    }

    private void processClientData(Payload payload) {
        if (myUser.getClientId() != Constants.DEFAULT_CLIENT_ID) {
            LoggerUtil.INSTANCE.warning(TextFX.colorize("Client ID already set, this shouldn't happen", Color.YELLOW));
        }
        myUser.setClientId(payload.getClientId());
        myUser.setClientName(((ConnectionPayload) payload).getClientName());// confirmation from Server
        knownClients.put(myUser.getClientId(), myUser);
        LoggerUtil.INSTANCE.info(TextFX.colorize("Connected", Color.GREEN));
    }

    private void processDisconnect(Payload payload) {
        if (payload.getClientId() == myUser.getClientId()) {
            knownClients.clear();
            myUser.reset();
            LoggerUtil.INSTANCE.info(TextFX.colorize("You disconnected", Color.RED));
        } else if (knownClients.containsKey(payload.getClientId())) {
            User disconnectedUser = knownClients.remove(payload.getClientId());
            if (disconnectedUser != null) {
                LoggerUtil.INSTANCE
                        .info(TextFX.colorize(String.format("%s disconnected", disconnectedUser.getDisplayName()),
                                Color.RED));
            }
        }
    }

    private void processRoomAction(Payload payload) {
        if (!(payload instanceof ConnectionPayload)) {
            error("Invalid payload subclass for processRoomAction");
            return;
        }
        ConnectionPayload connectionPayload = (ConnectionPayload) payload;
        if (connectionPayload.getClientId() == Constants.DEFAULT_CLIENT_ID) {
            knownClients.clear();
            return;
        }
        switch (connectionPayload.getPayloadType()) {

            case ROOM_LEAVE:
                if (knownClients.containsKey(connectionPayload.getClientId())) {
                    knownClients.remove(connectionPayload.getClientId());
                }
                if (connectionPayload.getMessage() != null) {
                    LoggerUtil.INSTANCE.info(TextFX.colorize(connectionPayload.getMessage(), Color.YELLOW));
                }

                break;
            case ROOM_JOIN:
                if (connectionPayload.getMessage() != null) {
                    LoggerUtil.INSTANCE.info(TextFX.colorize(connectionPayload.getMessage(), Color.GREEN));
                }
            case SYNC_CLIENT:
                if (!knownClients.containsKey(connectionPayload.getClientId())) {
                    User user = new User();
                    user.setClientId(connectionPayload.getClientId());
                    user.setClientName(connectionPayload.getClientName());
                    knownClients.put(connectionPayload.getClientId(), user);
                }
                break;
            default:
                error("Invalid payload type for processRoomAction");
                break;
        }
    }

    private void processMessage(Payload payload) {
        String message = payload.getMessage();
        LoggerUtil.INSTANCE.info(TextFX.colorize(message, Color.BLUE));
        // NEW: store for Game Events panel
        gameEvents.add(message);
    }

    private void processReverse(Payload payload) {
        LoggerUtil.INSTANCE.info(TextFX.colorize(payload.getMessage(), Color.PURPLE));
    }
    // End process*() methods

    /**
     * Listens for keyboard input from the user
     */
    private void listenToInput() {
        try (Scanner si = new Scanner(System.in)) {
            LoggerUtil.INSTANCE.info("Waiting for input");
            while (isRunning) {
                String userInput = si.nextLine();
                if (!processClientCommand(userInput)) {
                    sendMessage(userInput);
                }
            }
        } catch (IOException ioException) {
            LoggerUtil.INSTANCE.severe("Error in listenToInput()", ioException);
        }
        LoggerUtil.INSTANCE.info("listenToInput thread stopped");
    }

    /**
     * Closes the client connection and associated resources
     */
    private void close() {
        isRunning = false;
        closeServerConnection();
        LoggerUtil.INSTANCE.info("Client terminated");
    }

    /**
     * Closes the server connection and associated resources
     */
    private void closeServerConnection() {
        try {
            if (out != null) {
                LoggerUtil.INSTANCE.info("Closing output stream");
                out.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        try {
            if (in != null) {
                LoggerUtil.INSTANCE.info("Closing input stream");
                in.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        try {
            if (server != null) {
                LoggerUtil.INSTANCE.info("Closing connection");
                server.close();
                LoggerUtil.INSTANCE.info("Closed Socket");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        Client client = Client.INSTANCE;
        try {
            client.start();
        } catch (IOException e) {
            System.out.println("Exception from main()");
            e.printStackTrace();
        }
    }
}
