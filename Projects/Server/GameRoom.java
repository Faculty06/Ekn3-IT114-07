package Projects.Server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import Projects.CommonFiles.Constants;
import Projects.CommonFiles.LoggerUtil;
import Projects.CommonFiles.Phase;
import Projects.CommonFiles.TimedEvent;
import Projects.Exceptions.MissingCurrentPlayerException;
import Projects.Exceptions.NotPlayersTurnException;
import Projects.Exceptions.NotReadyException;
import Projects.Exceptions.PhaseMismatchException;
import Projects.Exceptions.PlayerNotFoundException;

public class GameRoom extends BaseGameRoom {
    private static final int MAX_STRIKES = 6;
    private static final int MAX_ROUNDS = 3;
    private static final int POINTS_PER_LETTER = 1;

    // word and game state
    private final List<String> wordList = new ArrayList<>();
    private String currentWord = null;
    private char[] currentBlanks = new char[0];
    private int strikes = 0;
    private final List<Character> guessedLetters = new ArrayList<>();

    // per-player server-side wrapper
    private final ConcurrentHashMap<Long, SeverUser> users = new ConcurrentHashMap<>();
     // used for general rounds (usually phase-based turns)
    private TimedEvent roundTimer = null;

    // used for granular turn handling (usually turn-order turns)
    private TimedEvent turnTimer = null;
    private List<ServerThread> turnOrder = new ArrayList<>();
    private long currentTurnClientId = Constants.DEFAULT_CLIENT_ID;
    private int round = 0;

     public GameRoom(String name) {
        super(name);
        loadWordList();
    }

    // ---------- word list loading ----------
    private void loadWordList() {
        final String RESOURCE = "/Assets/words.csv";
        try (InputStream is = getClass().getResourceAsStream(RESOURCE)) {
            if (is == null) {
                info(String.format("Error: Could not find resource %s", RESOURCE));
                return;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.trim().isEmpty() || line.startsWith("#")) continue;
                    String[] parts = line.split(",");
                    if (parts.length > 0) {
                        String word = parts[0].trim();
                        if (!word.isEmpty()) wordList.add(word.toUpperCase());
                    }
                }
            }
            Collections.shuffle(wordList);
            info(String.format("Loaded %d words", wordList.size()));
        } catch (IOException e) {
            info("Error loading word list: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /** {@inheritDoc} */
    @Override
    protected void onClientAdded(ServerThread sp) {
        // sync GameRoom state to new client
        syncCurrentPhase(sp);
        syncReadyStatus(sp);
        syncTurnStatus(sp);
    }

    /** {@inheritDoc} */
    @Override
    protected void onClientRemoved(ServerThread sp) {
        // Stops the timers so room can clean up
        LoggerUtil.INSTANCE.info("Player Removed, remaining: " + clientsInRoom.size());
        long removedClient = sp.getClientId();
        turnOrder.removeIf(player -> player.getClientId() == sp.getClientId());
        if (clientsInRoom.isEmpty()) {
            resetReadyTimer();
            resetTurnTimer();
            resetRoundTimer();
            onSessionEnd();
        } else if (removedClient == currentTurnClientId) {
            onTurnStart();
        }
    }

    // timer handlers
    private void startRoundTimer() {
        roundTimer = new TimedEvent(30, () -> onRoundEnd());
        roundTimer.setTickCallback((time) -> System.out.println("Round Time: " + time));
    }

    private void resetRoundTimer() {
        if (roundTimer != null) {
            roundTimer.cancel();
            roundTimer = null;
        }
    }

    private void startTurnTimer() {
        turnTimer = new TimedEvent(30, () -> onTurnEnd());
        turnTimer.setTickCallback((time) -> System.out.println("Turn Time: " + time));
    }

    private void resetTurnTimer() {
        if (turnTimer != null) {
            turnTimer.cancel();
            turnTimer = null;
        }
    }
    // end timer handlers

    // lifecycle methods

    /** {@inheritDoc} */
    @Override
    protected void onSessionStart() {
        LoggerUtil.INSTANCE.info("onSessionStart() start");
        changePhase(Phase.IN_PROGRESS);
        currentTurnClientId = Constants.DEFAULT_CLIENT_ID;
        setTurnOrder();
        round = 0;
        LoggerUtil.INSTANCE.info("onSessionStart() end");
        onRoundStart();
    }

    /** {@inheritDoc} */
    @Override
    protected void onRoundStart() {
        LoggerUtil.INSTANCE.info("onRoundStart() start");
        resetRoundTimer();
        resetTurnStatus();
        round++;
        setupNewWord();
        relay(null, String.format("Round %d has started", round));
        LoggerUtil.INSTANCE.info("onRoundStart() end");
        onTurnStart();
    }

    /** {@inheritDoc} */
    @Override
    protected void onTurnStart() {
        LoggerUtil.INSTANCE.info("onTurnStart() start");
        resetTurnTimer();
        try {
            ServerThread currentPlayer = getNextPlayer();
            relay(null, String.format("It's %s's turn", currentPlayer.getDisplayName()));
        } catch (MissingCurrentPlayerException | PlayerNotFoundException e) {

            e.printStackTrace();
        }
        startTurnTimer();
        LoggerUtil.INSTANCE.info("onTurnStart() end");
    }

    // Note: logic between Turn Start and Turn End is typically handled via timers
    // and user interaction
    /** {@inheritDoc} */
    @Override
    protected void onTurnEnd() {
        LoggerUtil.INSTANCE.info("onTurnEnd() start");
        resetTurnTimer(); // reset timer if turn ended without the time expiring
        try {
            // optionally can use checkAllTookTurn();
            if (isLastPlayer()) {
                // if the current player is the last player in the turn order, end the round
                onRoundEnd();
            } else {
                onTurnStart();
            }
        } catch (MissingCurrentPlayerException | PlayerNotFoundException e) {

            e.printStackTrace();
        }
        LoggerUtil.INSTANCE.info("onTurnEnd() end");
    }

    // Note: logic between Round Start and Round End is typically handled via timers
    // and user interaction
    /** {@inheritDoc} */
    @Override
    protected void onRoundEnd() {
        LoggerUtil.INSTANCE.info("onRoundEnd() start");
        resetRoundTimer(); // reset timer if round ended without the time expiring

        syncScoreboard(false);

        LoggerUtil.INSTANCE.info("onRoundEnd() end");
        if (round >= MAX_ROUNDS) {
            onSessionEnd();
        } else {
            onRoundStart();
        }
    }

    /** {@inheritDoc} */
    @Override
    protected void onSessionEnd() {
        LoggerUtil.INSTANCE.info("onSessionEnd() start");
        syncScoreboard(true);
        relay(null, "Game Over");

        //reset server-side player data
        users.values().forEach(u -> u.setPoints(0));
        strikes = 0;
        guessedLetters.clear();
        currentWord = null;
        currentBlanks = new char[0];
        turnOrder.clear();
        currentTurnClientId = Constants.DEFAULT_CLIENT_ID;
        resetReadyStatus();
        resetTurnStatus();
        changePhase(Phase.READY);
        LoggerUtil.INSTANCE.info("onSessionEnd() end");
    }
    // end lifecycle methods

    // send/sync data to ServerThread(s)
    private void sendResetTurnStatus() {
        clientsInRoom.values().forEach(spInRoom -> {
            boolean failedToSend = !spInRoom.sendResetTurnStatus();
            if (failedToSend) {
                removeClient(spInRoom);
            }
        });
    }

    private void sendTurnStatus(ServerThread client, boolean tookTurn) {
        clientsInRoom.values().removeIf(spInRoom -> {
            boolean failedToSend = !spInRoom.sendTurnStatus(client.getClientId(), client.didTakeTurn());
            if (failedToSend) {
                removeClient(spInRoom);
            }
            return failedToSend;
        });
    }

    private void syncTurnStatus(ServerThread incomingClient) {
        clientsInRoom.values().forEach(serverUser -> {
            if (serverUser.getClientId() != incomingClient.getClientId()) {
                boolean failedToSync = !incomingClient.sendTurnStatus(serverUser.getClientId(),
                        serverUser.didTakeTurn(), true);
                if (failedToSync) {
                    LoggerUtil.INSTANCE.warning(
                            String.format("Removing disconnected %s from list", serverUser.getDisplayName()));
                    disconnect(serverUser);
                }
            }
        });
    }

    // end send data to ServerThread(s)

    // misc methods
    private void resetTurnStatus() {
        clientsInRoom.values().forEach(sp -> {
            sp.setTookTurn(false);
        });
        sendResetTurnStatus();
    }

    /**
     * Sets `turnOrder` to a shuffled list of players who are ready.
     */
    private void setTurnOrder() {
        turnOrder.clear();
        turnOrder = clientsInRoom.values().stream().filter(ServerThread::isReady).collect(Collectors.toList());
        Collections.shuffle(turnOrder);
    }

    /**
     * Gets the current player based on the `currentTurnClientId`.
     * 
     * @return
     * @throws MissingCurrentPlayerException
     * @throws PlayerNotFoundException
     */
    private ServerThread getCurrentPlayer() throws MissingCurrentPlayerException, PlayerNotFoundException {
        // quick early exit
        if (currentTurnClientId == Constants.DEFAULT_CLIENT_ID) {
            throw new MissingCurrentPlayerException("Current Player not set");
        }
        return turnOrder.stream()
                .filter(sp -> sp.getClientId() == currentTurnClientId)
                .findFirst()
                // this shouldn't occur but is included as a "just in case"
                .orElseThrow(() -> new PlayerNotFoundException("Current player not found in turn order"));
    }

    /**
     * Gets the next player in the turn order.
     * If the current player is the last in the turn order, it wraps around
     * (round-robin).
     * 
     * @return
     * @throws MissingCurrentPlayerException
     * @throws PlayerNotFoundException
     */
    private ServerThread getNextPlayer() throws MissingCurrentPlayerException, PlayerNotFoundException {
        int index = 0;
        if (currentTurnClientId != Constants.DEFAULT_CLIENT_ID) {
            index = turnOrder.indexOf(getCurrentPlayer()) + 1;
            if (index >= turnOrder.size()) {
                index = 0;
            }
        }
        ServerThread nextPlayer = turnOrder.get(index);
        currentTurnClientId = nextPlayer.getClientId();
        return nextPlayer;
    }

    /**
     * Checks if the current player is the last player in the turn order.
     * 
     * @return
     * @throws MissingCurrentPlayerException
     * @throws PlayerNotFoundException
     */
    private boolean isLastPlayer() throws MissingCurrentPlayerException, PlayerNotFoundException {
        // check if the current player is the last player in the turn order
        return turnOrder.indexOf(getCurrentPlayer()) == (turnOrder.size() - 1);
    }

    private void checkAllTookTurn() {
        int numReady = clientsInRoom.values().stream()
                .filter(sp -> sp.isReady())
                .toList().size();
        int numTookTurn = clientsInRoom.values().stream()
                // ensure to verify the isReady part since it's against the original list
                .filter(sp -> sp.isReady() && sp.didTakeTurn())
                .toList().size();
        if (numReady == numTookTurn) {
            relay(null,
                    String.format("All players have taken their turn (%d/%d) ending the round", numTookTurn, numReady));
            onRoundEnd();
        }
    }

    // start check methods
    private void checkCurrentPlayer(long clientId) throws NotPlayersTurnException {
        if (currentTurnClientId != clientId) {
            throw new NotPlayersTurnException("You are not the current player");
        }
    }

    // end check methods

    // receive data from ServerThread (GameRoom specific)

    /**
     * Handles the turn action from the client.
     * 
     * @param currentUser
     * @param exampleText (arbitrary text from the client, can be used for
     *                    additional actions or information)
     */

     private void setupNewWord() {
        if (wordList.isEmpty()) {
            relay(null, "Error: Word list is empty. Ending session.");
            onSessionEnd();
            return;
        }
        currentWord = wordList.get(new Random().nextInt(wordList.size())).toUpperCase();
        currentBlanks = new char[currentWord.length()];
        for (int i = 0; i < currentBlanks.length; i++)
            currentBlanks[i] = '_';
        strikes = 0;
        guessedLetters.clear();
        syncBlanksAndStrikes();
        info(String.format("New Word chosen, length %d", currentBlanks.length));
    }

    private SeverUser getOrCreateUser(long clientId) {
        return users.computeIfAbsent(clientId, id -> {
            ServerThread st = clientsInRoom.get(id);
            return st == null ? null : new SeverUser(st);
        });
    }

    private void syncBlanksAndStrikes() {
        String blanks = new String(currentBlanks);
        // use GameStatePayload via ServerThread helper
        clientsInRoom.values().forEach(st -> st.sendGameState(blanks, strikes, MAX_STRIKES));
    }

    private boolean isWordComplete() {
        for (char c : currentBlanks)
            if (c == '_')
                return false;
        return true;
    }

    private void syncScoreboard(boolean isFinal) {
        List<SeverUser> sorted = users.values().stream()
                .sorted((a, b) -> Integer.compare(b.getPoints(), a.getPoints())).collect(Collectors.toList());
        StringBuilder sb = new StringBuilder(isFinal ? "\n*** FINAL SCOREBOARD ***\n" : "\n*** Current SCOREBOARD ***\n");
        sorted.forEach(p -> sb.append(String.format("%s: %d\n", p.getClientName(), p.getPoints())));
        sb.append("**************************");
        relay(null, sb.toString());
    }

    protected void handleTurnAction(ServerThread currentUser, String exampleText) {
        // check if the client is in the room
        try {
            checkPlayerInRoom(currentUser);
            checkCurrentPhase(currentUser, Phase.IN_PROGRESS);
            checkCurrentPlayer(currentUser.getClientId());
            checkIsReady(currentUser);
            if (currentUser.didTakeTurn()) {
                currentUser.sendMessage(Constants.DEFAULT_CLIENT_ID, "You have already taken your turn this round");
                return;
            }
            currentUser.setTookTurn(true);
            sendTurnStatus(currentUser, currentUser.didTakeTurn());

            if (exampleText == null || exampleText.isBlank()) {
                currentUser.sendMessage(Constants.DEFAULT_CLIENT_ID, "No command provided.");
                onTurnEnd();
                return;
            } 
             String command = exampleText.toLowerCase().trim();

            if (command.startsWith("/guess")) {
                String wordGuess = command.substring(6).trim();
                processGuess(currentUser, wordGuess);
            } else if (command.startsWith("/letter")) {
                String letterGuess = command.substring(7).trim();
                processLetter(currentUser, letterGuess);
            } else if (command.equals("/skip")) {
                processSkip(currentUser);
            } else {
                currentUser.sendMessage(Constants.DEFAULT_CLIENT_ID,
                        "Unknown game command. Use /guess <word>, /letter <char>, or /skip.");
                onTurnEnd();
            }

        } catch (NotPlayersTurnException e) {
            currentUser.sendMessage(Constants.DEFAULT_CLIENT_ID, "It's not your turn");
            LoggerUtil.INSTANCE.severe("handleTurnAction exception", e);
        } catch (NotReadyException e) {
            // The check method already informs the currentUser
            LoggerUtil.INSTANCE.severe("handleTurnAction exception", e);
        } catch (PlayerNotFoundException e) {
            currentUser.sendMessage(Constants.DEFAULT_CLIENT_ID, "You must be in a GameRoom to do the ready check");
            LoggerUtil.INSTANCE.severe("handleTurnAction exception", e);
        } catch (PhaseMismatchException e) {
            currentUser.sendMessage(Constants.DEFAULT_CLIENT_ID,
                    "You can only take a turn during the IN_PROGRESS phase");
            LoggerUtil.INSTANCE.severe("handleTurnAction exception", e);
        } catch (Exception e) {
            LoggerUtil.INSTANCE.severe("handleTurnAction exception", e);
        }
    }
    
    private void processGuess(ServerThread guesserThread, String wordGuess) {
        if (wordGuess == null || wordGuess.isBlank()) {
            guesserThread.sendMessage(Constants.DEFAULT_CLIENT_ID, "Usage: /guess <word>");
            onTurnEnd();
            return;
        }

        SeverUser guesser = getOrCreateUser(guesserThread.getClientId());
        if (guesser == null) {
            guesserThread.sendMessage(Constants.DEFAULT_CLIENT_ID, "Player data unavailable");
            onTurnEnd();
            return;
        }

        wordGuess = wordGuess.toUpperCase();
        if (wordGuess.equals(currentWord)) {
            int missingLetters = (int) new String(currentBlanks).chars().filter(c -> c == '_').count();
            int pointsEarned = missingLetters * 2;
            guesser.setPoints(guesser.getPoints() + pointsEarned);

            // sync points to all clients
            clientsInRoom.values().forEach(c -> c.sendPoints(guesser.getClientId(), guesser.getPoints()));

            relay(null, String.format("%s guessed the correct word %s and got %d points",
                    guesser.getClientName(), currentWord, pointsEarned));

            // reveal word and end round
            currentBlanks = currentWord.toCharArray();
            syncBlanksAndStrikes();
            onRoundEnd();
        } else {
            strikes++;
            syncBlanksAndStrikes();
            relay(null, String.format("X %s guessed %s and it was wrong.", guesser.getClientName(), wordGuess));

            if (strikes >= MAX_STRIKES) {
                onRoundEnd();
            } else {
                onTurnEnd();
            }
        }
    }

    private void processLetter(ServerThread guesserThread, String letterGuess) {
        if (letterGuess == null || letterGuess.length() != 1) {
            guesserThread.sendMessage(Constants.DEFAULT_CLIENT_ID, "Usage: /letter <single_char>");
            onTurnEnd();
            return;
        }

        char letter = Character.toUpperCase(letterGuess.charAt(0));
        if (guessedLetters.contains(letter)) {
            guesserThread.sendMessage(Constants.DEFAULT_CLIENT_ID, "Letter already guessed: " + letter);
            onTurnEnd();
            return;
        }

        guessedLetters.add(letter);

        int matches = 0;
        for (int i = 0; i < currentWord.length(); i++) {
            if (currentWord.charAt(i) == letter && currentBlanks[i] == '_') {
                currentBlanks[i] = letter;
                matches++;
            }
        }

        SeverUser guesser = getOrCreateUser(guesserThread.getClientId());
        if (guesser == null) {
            guesserThread.sendMessage(Constants.DEFAULT_CLIENT_ID, "Player data unavailable");
            onTurnEnd();
            return;
        }

        if (matches > 0) {
            int pointsEarned = matches * POINTS_PER_LETTER;
            guesser.setPoints(guesser.getPoints() + pointsEarned);
            clientsInRoom.values().forEach(c -> c.sendPoints(guesser.getClientId(), guesser.getPoints()));

            relay(null, String.format("%s guessed %s, %d match(es), earned %d points",
                    guesser.getClientName(), letter, matches, pointsEarned));

            syncBlanksAndStrikes();

            if (isWordComplete()) {
                onRoundEnd();
            } else {
                onTurnEnd();
            }
        } else {
            strikes++;
            syncBlanksAndStrikes();
            relay(null, String.format("X %s guessed %s and it wasn't in the word", guesser.getClientName(), letter));
            if (strikes >= MAX_STRIKES) {
                onRoundEnd();
            } else {
                onTurnEnd();
            }
        }
    }

    private void processSkip(ServerThread skipper) {
        relay(null, String.format("%s skipped their turn", skipper.getDisplayName()));
        onTurnEnd();
    }
    // end receive data from ServerThread (GameRoom specific)
}
