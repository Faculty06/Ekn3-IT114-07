

import Projects.CommonFiles.Constants;
import Projects.CommonFiles.GameTurnPayload;
import Projects.CommonFiles.GameStatePayload;
import Projects.CommonFiles.LoggerUtil;
import Projects.CommonFiles.PointsPayload;
import Projects.CommonFiles.TextFX;
import Projects.CommonFiles.User;
import Projects.CommonFiles.TextFX.Color;
import Projects.Server.GameRoom;
import Projects.Server.Server;
import Projects.Server.ServerThread;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * Implements the Hangman game logic, turn management, and state synchronization.
 */
public class HangmanGameRoom extends GameRoom {

    // --- Game Constants ---
    private static final int MAX_STRIKES = 6;
    private static final int ROUNDS_PER_SESSION = 3;
    private static final int BASE_POINT_PER_LETTER = 10;
    private static final int SOLVE_BONUS_MULTIPLIER = 2; // 2x points for empty spaces on solve
    private static final int TURN_TIMER_SECONDS = 30;

    // --- Game State Variables ---
    private List<String> wordList;
    private String currentWord;
    private String hiddenWord;
    private int currentStrikes;
    private HashSet<Character> guessedLetters;

    private List<ServerThread> playerOrder;
    private int currentPlayerIndex;
    private Timer turnTimer;
    private boolean isTurnActive = false;

    private int roundNumber;
    private boolean isSessionActive = false;

    public HangmanGameRoom(String name) {
        super(name);
        LoggerUtil.INSTANCE.info("HangmanGameRoom created: " + name);
        loadWords();
        resetGame(false); // Initialize state without starting session
    }
    
    // --- Initialization and Cleanup ---

    private void loadWords() {
        wordList = new ArrayList<>();
        // Assuming words.txt is accessible in the root directory relative to execution
        try (BufferedReader reader = new BufferedReader(new FileReader("words.txt"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                wordList.add(line.trim().toUpperCase());
            }
            if (wordList.isEmpty()) {
                LoggerUtil.INSTANCE.severe("Word list is empty. Using default words.");
                wordList.add("DEFAULT");
            }
            LoggerUtil.INSTANCE.info(String.format("Loaded %d words from words.txt", wordList.size()));
        } catch (IOException e) {
            LoggerUtil.INSTANCE.severe("Failed to load words.txt. Using default words.", e);
            wordList.add("DEFAULT");
        }
    }

    private void resetGame(boolean resetPlayers) {
        currentWord = null;
        hiddenWord = null;
        currentStrikes = 0;
        guessedLetters = new HashSet<>();
        playerOrder = new ArrayList<>();
        currentPlayerIndex = 0;
        roundNumber = 0;
        isSessionActive = false;
        
        if (turnTimer != null) {
            turnTimer.cancel();
            turnTimer = null;
            isTurnActive = false;
        }

        if (resetPlayers) {
            // Reset player points and ready status
            clientsInRoom.values().forEach(st -> {
                st.user.setPoints(0);
                st.user.setReady(false);
            });
        }
    }
    
    // --- Session Management ---

    public void checkReadyStatus() {
        if (isSessionActive) return;

        long readyCount = clientsInRoom.values().stream()
                                      .filter(st -> st.user.isReady())
                                      .count();
        
        if (readyCount > 0 && readyCount == clientsInRoom.size()) {
            startGame();
        } else {
            int needed = clientsInRoom.size() - (int) readyCount;
            relay(null, TextFX.colorize(String.format("Waiting for %d more player(s) to type /ready to start session.", needed), Color.CYAN));
        }
    }

    private void startGame() {
        isSessionActive = true;
        roundNumber = 0;
        
        // 1. Initial Player Order (Random)
        playerOrder.clear();
        playerOrder.addAll(clientsInRoom.values());
        Collections.shuffle(playerOrder, ThreadLocalRandom.current());
        
        currentPlayerIndex = 0;
        
        relay(null, TextFX.colorize("!!! HANGMAN SESSION STARTED !!!", Color.GREEN));
        relay(null, TextFX.colorize(String.format("Playing %d rounds. Initial player order: %s", 
            ROUNDS_PER_SESSION, playerOrder.stream().map(st -> st.user.getClientName()).collect(Collectors.joining(", "))), Color.GREEN));
            
        startRound();
    }
    
    // --- Round Management ---

    private void startRound() {
        roundNumber++;
        if (roundNumber > ROUNDS_PER_SESSION) {
            endSession();
            return;
        }
        
        // Pick new word
        currentWord = wordList.get(ThreadLocalRandom.current().nextInt(wordList.size()));
        currentStrikes = 0;
        guessedLetters.clear();
        hiddenWord = currentWord.replaceAll(".", "_");

        relay(null, TextFX.colorize(String.format("--- ROUND %d/%d STARTED ---", roundNumber, ROUNDS_PER_SESSION), Color.YELLOW));
        relay(null, TextFX.colorize(String.format("The word has %d letters. Current word: %s", currentWord.length(), hiddenWord), Color.YELLOW));
        
        syncGameState();
        startTurn();
    }

    private void endRound(boolean wordGuessed) {
        if (!isSessionActive) return;
        
        stopTurnTimer();

        if (wordGuessed) {
            relay(null, TextFX.colorize(String.format("ROUND END: The word was correctly solved! [%s]", currentWord), Color.GREEN));
        } else if (currentStrikes >= MAX_STRIKES) {
            relay(null, TextFX.colorize(String.format("ROUND END: Hangman reached max strikes (%d). The word was [%s]", MAX_STRIKES, currentWord), Color.RED));
        } else {
            // Should not happen if endRound is called correctly, but for safety
            relay(null, TextFX.colorize(String.format("ROUND END: Round finished due to player turns. The word was [%s]", currentWord), Color.YELLOW));
        }
        
        sendScoreboard();

        if (roundNumber < ROUNDS_PER_SESSION) {
            // Reset player ready state for the next round's start (optional, but good for multi-round games)
            clientsInRoom.values().forEach(st -> st.user.setReady(true)); // Keep them ready for continuous rounds
            currentPlayerIndex = 0; // Reset turn order for next round
            startRound();
        } else {
            endSession();
        }
    }

    private void endSession() {
        isSessionActive = false;
        relay(null, TextFX.colorize("!!! SESSION ENDED !!!", Color.RED));
        sendScoreboard(true); // Final Scoreboard
        
        relay(null, TextFX.colorize("Game Over. Type /ready to start a new session.", Color.RED));
        
        // Reset the game state and player data
        resetGame(true);
    }
    
    // --- Turn Management ---

    private void startTurn() {
        if (!isSessionActive || playerOrder.isEmpty()) return;
        
        ServerThread currentPlayer = playerOrder.get(currentPlayerIndex);
        
        relay(null, TextFX.colorize(String.format("It is %s's turn! You have %d seconds.", currentPlayer.getDisplayName(), TURN_TIMER_SECONDS), Color.CYAN));
        syncGameState();
        startTurnTimer(currentPlayer);
    }

    private void startTurnTimer(ServerThread player) {
        stopTurnTimer(); // Ensure any existing timer is stopped
        isTurnActive = true;
        turnTimer = new Timer();
        turnTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                if (player.getClientId() == playerOrder.get(currentPlayerIndex).getClientId()) {
                    relay(null, TextFX.colorize(String.format("%s's turn timed out (skipping).", player.getDisplayName()), Color.RED));
                    endTurn(player);
                }
            }
        }, TURN_TIMER_SECONDS * 1000);
    }
    
    private void stopTurnTimer() {
        if (turnTimer != null) {
            turnTimer.cancel();
            turnTimer.purge();
            turnTimer = null;
            isTurnActive = false;
        }
    }

    private void endTurn(ServerThread player) {
        stopTurnTimer();
        
        // If the round is over (e.g., solved or max strikes hit), don't start the next turn
        if (!isSessionActive || currentStrikes >= MAX_STRIKES || !hiddenWord.contains("_")) {
            endRound(!hiddenWord.contains("_"));
            return;
        }
        
        // Move to the next player
        currentPlayerIndex = (currentPlayerIndex + 1) % playerOrder.size();
        
        if (currentPlayerIndex == 0) {
            // Last player's turn finished, cycle complete
            // This is Condition 3 for Round End
            endRound(false); 
        } else {
            startTurn();
        }
    }

    // --- Game Logic and Player Actions ---

    public void doGameTurn(ServerThread player, GameTurnPayload payload) {
        if (!isSessionActive) {
            player.sendMessage(player.getClientId(), TextFX.colorize("Session is not active. Type /ready to start.", Color.RED));
            return;
        }
        
        if (!isTurnActive || player.getClientId() != playerOrder.get(currentPlayerIndex).getClientId()) {
            player.sendMessage(player.getClientId(), TextFX.colorize("It is not your turn.", Color.RED));
            return;
        }

        String action = payload.getTurnAction();
        String argument = payload.getTurnArgument().toUpperCase().trim();

        try {
            switch (action) {
                case "GUESS":
                    handleGuess(player, argument);
                    break;
                case "LETTER":
                    handleLetter(player, argument);
                    break;
                case "SKIP":
                    handleSkip(player);
                    break;
                default:
                    player.sendMessage(player.getClientId(), TextFX.colorize("Invalid turn action.", Color.RED));
                    break;
            }
        } catch (Exception e) {
            player.sendMessage(player.getClientId(), TextFX.colorize("An error occurred during your turn: " + e.getMessage(), Color.RED));
            LoggerUtil.INSTANCE.severe("Error handling game turn", e);
            endTurn(player);
        }
    }
    
    private void handleGuess(ServerThread player, String wordGuess) {
        if (wordGuess.length() < 2) {
             player.sendMessage(player.getClientId(), TextFX.colorize("Word guess must be at least 2 letters long.", Color.RED));
             return;
        }
        
        if (wordGuess.equals(currentWord)) {
            // Match (Condition 1 Round End)
            int emptySpaces = (int) hiddenWord.chars().filter(c -> c == '_').count();
            int points = emptySpaces * BASE_POINT_PER_LETTER * SOLVE_BONUS_MULTIPLIER;
            
            player.user.setPoints(player.user.getPoints() + points);
            syncPoints(player);
            
            relay(null, TextFX.colorize(String.format("%s guessed the correct word %s and got %d points!", 
                player.getDisplayName(), currentWord, points), Color.GREEN));
                
            hiddenWord = currentWord; // Reveal the word
            syncGameState();
            endRound(true);
            
        } else {
            // Does not match (Turn End)
            currentStrikes++;
            syncGameState();
            
            relay(null, TextFX.colorize(String.format("%s guessed %s and it was wrong.", 
                player.getDisplayName(), wordGuess), Color.RED));
                
            if (currentStrikes >= MAX_STRIKES) {
                endRound(false); // Condition 2 Round End
            } else {
                endTurn(player); // Turn ends
            }
        }
    }

    private void handleLetter(ServerThread player, String letterGuess) {
        if (letterGuess.length() != 1 || !Character.isLetter(letterGuess.charAt(0))) {
            player.sendMessage(player.getClientId(), TextFX.colorize("Letter guess must be a single letter.", Color.RED));
            return;
        }
        
        char guessChar = letterGuess.charAt(0);
        
        if (guessedLetters.contains(guessChar)) {
            player.sendMessage(player.getClientId(), TextFX.colorize(String.format("The letter '%c' has already been guessed.", guessChar), Color.YELLOW));
            endTurn(player); // Turn still ends
            return;
        }
        
        guessedLetters.add(guessChar);
        
        int matches = 0;
        StringBuilder newHiddenWord = new StringBuilder(hiddenWord);
        
        for (int i = 0; i < currentWord.length(); i++) {
            if (currentWord.charAt(i) == guessChar) {
                if (hiddenWord.charAt(i) == '_') {
                    newHiddenWord.setCharAt(i, guessChar);
                    matches++;
                }
            }
        }
        
        if (matches > 0) {
            // Match (Turn End)
            hiddenWord = newHiddenWord.toString();
            int points = matches * BASE_POINT_PER_LETTER;
            
            player.user.setPoints(player.user.getPoints() + points);
            syncPoints(player);
            
            relay(null, TextFX.colorize(String.format("%s guessed '%c' and found %d matches, earning %d points!", 
                player.getDisplayName(), guessChar, matches, points), Color.GREEN));
            
            syncGameState();
            
            if (!hiddenWord.contains("_")) {
                endRound(true); // Word completed (Condition 1 Round End)
            } else {
                endTurn(player); // Turn ends
            }
            
        } else {
            // No Match (Turn End)
            currentStrikes++;
            syncGameState();
            
            relay(null, TextFX.colorize(String.format("%s guessed '%c', which is not in the word.", 
                player.getDisplayName(), guessChar), Color.RED));
                
            if (currentStrikes >= MAX_STRIKES) {
                endRound(false); // Condition 2 Round End
            } else {
                endTurn(player); // Turn ends
            }
        }
    }
    
    private void handleSkip(ServerThread player) {
        relay(null, TextFX.colorize(String.format("%s skipped their turn.", player.getDisplayName()), Color.YELLOW));
        endTurn(player); // Turn ends
    }
    
    // --- Synchronization ---

    /**
     * Sends the current game state (word, strikes, current player) to all clients.
     */
    private void syncGameState() {
        GameStatePayload gsp = new GameStatePayload();
        gsp.setHiddenWord(hiddenWord);
        gsp.setCurrentStrikes(currentStrikes);
        gsp.setMaxStrikes(MAX_STRIKES);
        gsp.setGuessedLetters(guessedLetters);
        gsp.setRoundNumber(roundNumber);
        
        if (isTurnActive && !playerOrder.isEmpty()) {
            gsp.setCurrentPlayerId(playerOrder.get(currentPlayerIndex).getClientId());
        } else {
            gsp.setCurrentPlayerId(Constants.DEFAULT_CLIENT_ID);
        }
        
        clientsInRoom.values().forEach(st -> st.sendToClient(gsp));
    }
    
    /**
     * Sends the updated points for a single player to all clients.
     */
    private void syncPoints(ServerThread player) {
        PointsPayload pp = new PointsPayload();
        pp.setClientId(player.getClientId());
        pp.setPoints(player.user.getPoints());
        clientsInRoom.values().forEach(st -> st.sendToClient(pp));
    }

    /**
     * Sends the current scoreboard to all clients.
     */
    private void sendScoreboard() {
        sendScoreboard(false);
    }
    
    /**
     * Sends the current or final scoreboard to all clients.
     * @param isFinal True if this is the final scoreboard.
     */
    private void sendScoreboard(boolean isFinal) {
        String title = isFinal ? "--- FINAL SCOREBOARD ---" : "--- IN-PROGRESS SCOREBOARD ---";
        relay(null, TextFX.colorize(title, Color.PURPLE));
        
        // Get all players, sort by points descending
        List<User> usersByPoints = clientsInRoom.values().stream()
                .map(st -> st.user)
                .sorted((u1, u2) -> Integer.compare(u2.getPoints(), u1.getPoints()))
                .collect(Collectors.toList());
        
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < usersByPoints.size(); i++) {
            User u = usersByPoints.get(i);
            sb.append(String.format("%d. %s (%d points)\n", i + 1, u.getDisplayName(), u.getPoints()));
        }
        
        relay(null, TextFX.colorize(sb.toString(), Color.PURPLE));
    }
    
    // --- Overrides ---

    @Override
    public void addClient(ServerThread client) {
        super.addClient(client);
        // If a client joins mid-session, sync current state
        if (isSessionActive) {
            syncGameState();
            sendScoreboard();
        }
    }
    
    @Override
    public void removeClient(ServerThread client) {
        // If a player leaves during a session, ensure the game state updates
        super.removeClient(client);
        
        if (playerOrder.contains(client)) {
            // Remove the client from the rotation
            playerOrder.remove(client);
            
            // Adjust current player index if the removed player was before or was the current player
            if (currentPlayerIndex >= playerOrder.size()) {
                 currentPlayerIndex = 0;
            }
            
            // If the removed player was the one whose turn it was, or if the room is now empty, end the turn/round
            if (isTurnActive) {
                 stopTurnTimer();
                 endTurn(null); // End turn without a specific player action
            }
        }
        
        if (clientsInRoom.size() < 1) {
            relay(null, TextFX.colorize("Room is empty. Resetting game session.", Color.RED));
            resetGame(true);
            Server.INSTANCE.removeRoom(this); // Optionally remove room if empty
        } else if (isSessionActive && clientsInRoom.size() < 2) {
            relay(null, TextFX.colorize("Only one player remains. Ending session.", Color.RED));
            endSession();
        }
    }

    @Override
    public void disconnectAll() {
        resetGame(true);
        super.disconnectAll();
    }
}