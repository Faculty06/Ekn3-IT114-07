package Projects.Client;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import Projects.CommonFiles.User;
import Projects.CommonFiles.GameStatePayload;
import Projects.CommonFiles.Phase;

/**
 * Entry point for the Hangman UI client.
 *
 * NOTE: This class expects additional helper methods to exist on Client, and
 * extra flags on User, as described in comments below.
 */
public class HangmanClientUI {

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            HangmanClientFrame frame = new HangmanClientFrame(Client.INSTANCE);
            frame.setVisible(true);
        });
    }
}

/**
 * Main window.
 *
 * Uses a CardLayout for 3 main "pages":
 *  - Connect screen
 *  - Ready Check screen
 *  - Game Area
 */
class HangmanClientFrame extends JFrame {

    private final Client client;

    private final CardLayout cardLayout = new CardLayout();
    private final JPanel cards = new JPanel(cardLayout);

    private final ConnectPanel connectPanel;
    private final ReadyPanel readyPanel;
    private final GamePanel gamePanel;

    private Phase lastPhase = null;
    private String lastTurnMessageSeen = null;
    private final Map<Long, Integer> lastPointsSeen = new HashMap<>();
    private long lastTurnClientIdSeen = -1L;

    // Configuration toggles set in Ready screen
    private boolean removeStrikeOnCorrectGuess = false;
    boolean hardMode = false;          // package-private so GamePanel can see it
    private boolean joinAsSpectator = false;

    HangmanClientFrame(Client client) {
        super("Hangman Client");
        this.client = client;

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1000, 700);
        setLocationRelativeTo(null);

        connectPanel = new ConnectPanel();
        readyPanel = new ReadyPanel();
        gamePanel = new GamePanel();

        cards.add(connectPanel, "CONNECT");
        cards.add(readyPanel, "READY");
        cards.add(gamePanel, "GAME");

        setContentPane(cards);

        showConnect();

        // Poll ~10 times per second for updated state from Client
        Timer uiTimer = new Timer(100, this::pollClientState);
        uiTimer.start();
    }

    private void showConnect() {
        cardLayout.show(cards, "CONNECT");
    }

    private void showReady() {
        cardLayout.show(cards, "READY");
    }

    private void showGame() {
        cardLayout.show(cards, "GAME");
    }

    /**
     * Periodically called to sync UI with Client state:
     *  - phase changes
     *  - user list / points / away/spectator
     *  - game state (strikes, hidden word)
     *  - latest turn (guess history, whose turn)
     *  - generic game events (from MESSAGE payloads)
     */
    private void pollClientState(ActionEvent e) {
        // If not connected, always show Connect screen
        if (!client.isConnected()) {
            lastPhase = null;       // reset so when we do connect, phase changes are noticed
            lastTurnClientIdSeen = -1L;
            showConnect();
            return;
        }

        // 1. Phase changes -> swap between Ready/Game
        Phase currentPhase = client.getCurrentPhase();
        if (currentPhase != lastPhase) {
            lastPhase = currentPhase;
            if (currentPhase == Phase.READY) {
                readyPanel.resetReadyButton();
                gamePanel.resetForNewSession();
                showReady();
            } else if (currentPhase == Phase.IN_PROGRESS) {
                gamePanel.resetForNewRound();
                showGame();
            }
        }

        // 2. Sync user list + points
        ConcurrentHashMap<Long, User> map = client.getKnownClients();
        List<User> users = new ArrayList<>(map.values());
        // Define turn order based on clientId for consistency
        users.sort(Comparator.comparingLong(User::getClientId));

        long currentTurnId = client.getCurrentTurnClientId();

        gamePanel.updateUserList(users, currentTurnId);
        readyPanel.updateUserList(users);

        // 2a. points changes -> log to events
        for (User u : users) {
            int newPoints = u.getPoints();
            Integer oldPoints = lastPointsSeen.get(u.getClientId());
            if (oldPoints == null) {
                lastPointsSeen.put(u.getClientId(), newPoints);
            } else if (oldPoints != newPoints) {
                lastPointsSeen.put(u.getClientId(), newPoints);
                gamePanel.appendEvent(
                        String.format("%s now has %d points", u.getClientName(), newPoints));
            }
        }

        // 2b. Turn owner change -> update label + start countdown
        if (currentTurnId != lastTurnClientIdSeen && currentTurnId != 0L && currentTurnId != -1L) {
            lastTurnClientIdSeen = currentTurnId;

            User turnUser = map.get(currentTurnId);
            String name = (turnUser != null)
                    ? turnUser.getClientName()
                    : ("#" + currentTurnId);

            gamePanel.setCurrentTurnLabel(name);
        }

        // 3. Update game state (strikes + word blanks)
        GameStatePayload gsp = client.getLatestGameState();
        if (gsp != null) {
            gamePanel.updateGameState(gsp, hardMode, joinAsSpectator);
        }

        // 4. Generic game events from server (MESSAGE payloads)
        for (String message : client.drainGameEvents()) {
            gamePanel.appendEvent(message);
        }

        // 5. Turn / guess history + turn-related events
        String lastTurn = client.getLastTurnMessage();
        if (lastTurn != null && !lastTurn.equals(lastTurnMessageSeen)) {
            lastTurnMessageSeen = lastTurn;

            User turnUser = map.get(currentTurnId);
            String name = (turnUser != null) ? turnUser.getClientName()
                                             : ("#" + currentTurnId);

            // In hard mode, don't show guess history in the Game Events panel
            if (!hardMode) {
                // lastTurn is the raw command string from the client, e.g.
                // "/skip", "/letter A", "/guess apple", "/away", "/back"
                String cmd = lastTurn.trim().toLowerCase();

                if (cmd.startsWith("/skip")) {
                    gamePanel.appendEvent(String.format("%s skipped their turn", name));

                } else if (cmd.startsWith("/config")) {
                    // Config messages handled by scoreboard/events, no extra UI text here

                } else if (cmd.startsWith("/letter")) {
                    // Extract the letter after "/letter"
                    String[] parts = lastTurn.trim().split("\\s+");
                    String letterShown = (parts.length >= 2) ? parts[1].toUpperCase() : "?";
                    gamePanel.appendEvent(
                            String.format("%s guessed letter '%s'",
                                    name, letterShown));
                    if (letterShown.length() == 1) {
                        gamePanel.disableLetter(letterShown.charAt(0));
                    }

                } else if (cmd.startsWith("/away")) {
                    if (turnUser != null) {
                        turnUser.setAway(true);   // keep User model in sync
                    }
                    gamePanel.appendEvent(String.format("%s is away", name));

                } else if (cmd.startsWith("/back")) {
                    if (turnUser != null) {
                        turnUser.setAway(false);  // keep User model in sync
                    }
                    gamePanel.appendEvent(String.format("%s is no longer away", name));

                } else if (cmd.startsWith("/guess")) {
                    // Extract the word after "/guess"
                    String[] parts = lastTurn.trim().split("\\s+", 2);
                    String guessedWord = (parts.length >= 2) ? parts[1] : "";
                    gamePanel.appendEvent(
                            String.format("%s guessed word \"%s\"",
                                    name, guessedWord));
                } else {
                    // Fallback for any other turn text
                    gamePanel.appendEvent(
                            String.format("%s performed action: %s",
                                    name, lastTurn));
                }
            }
        }
    }

    // ------------------------------------------------------------------------
    // Connect Panel
    // ------------------------------------------------------------------------
    private class ConnectPanel extends JPanel {
        private final JTextField usernameField = new JTextField(15);
        private final JTextField hostField = new JTextField("localhost", 15);
        private final JTextField portField = new JTextField("3000", 6);
        private final JCheckBox spectatorCheck = new JCheckBox("Join as spectator");

        ConnectPanel() {
            setLayout(new GridBagLayout());
            JPanel inner = new JPanel(new GridBagLayout());
            inner.setBorder(new EmptyBorder(20, 20, 20, 20));

            GridBagConstraints gbc = new GridBagConstraints();
            gbc.insets = new Insets(5, 5, 5, 5);
            gbc.anchor = GridBagConstraints.WEST;

            int row = 0;

            gbc.gridx = 0;
            gbc.gridy = row;
            inner.add(new JLabel("Username:"), gbc);
            gbc.gridx = 1;
            inner.add(usernameField, gbc);
            row++;

            gbc.gridx = 0;
            gbc.gridy = row;
            inner.add(new JLabel("Host:"), gbc);
            gbc.gridx = 1;
            inner.add(hostField, gbc);
            row++;

            gbc.gridx = 0;
            gbc.gridy = row;
            inner.add(new JLabel("Port:"), gbc);
            gbc.gridx = 1;
            inner.add(portField, gbc);
            row++;

            gbc.gridx = 1;
            gbc.gridy = row;
            inner.add(spectatorCheck, gbc);
            row++;

            JButton connectBtn = new JButton("Connect");
            connectBtn.addActionListener(ev -> doConnect());

            gbc.gridx = 0;
            gbc.gridy = row;
            gbc.gridwidth = 2;
            gbc.anchor = GridBagConstraints.CENTER;
            inner.add(connectBtn, gbc);

            add(inner);
        }

        private void doConnect() {
            String username = usernameField.getText().trim();
            String host = hostField.getText().trim();
            String portText = portField.getText().trim();

            if (username.isEmpty() || host.isEmpty() || portText.isEmpty()) {
                JOptionPane.showMessageDialog(this,
                        "Please fill in username, host, and port.",
                        "Missing data",
                        JOptionPane.WARNING_MESSAGE);
                return;
            }

            int port;
            try {
                port = Integer.parseInt(portText);
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(this,
                        "Port must be a number.",
                        "Invalid port",
                        JOptionPane.ERROR_MESSAGE);
                return;
            }

            joinAsSpectator = spectatorCheck.isSelected();

            boolean ok = client.connect(host, port, username);
            if (!ok) {
                JOptionPane.showMessageDialog(this,
                        "Failed to connect to server.",
                        "Connection error",
                        JOptionPane.ERROR_MESSAGE);
                return;
            }

            // NOTE: actual spectator registration should be implemented
            // on the server side (e.g. via a special payload or command).
            showReady();
        }
    }

    // ------------------------------------------------------------------------
    // Ready Check Panel
    // ------------------------------------------------------------------------
    private class ReadyPanel extends JPanel {
        private final JButton readyButton = new JButton("Mark Ready");
        private final JCheckBox removeStrikeCheck =
                new JCheckBox("Correct word guess removes a strike");
        private final JCheckBox hardModeCheck =
                new JCheckBox("Hard Mode (no disabled letters / guess history)");

        // Room controls
        private final JTextField roomField = new JTextField("hangman", 12);
        private final JButton createRoomButton = new JButton("Create Room");
        private final JButton joinRoomButton = new JButton("Join Room");

        private final JTable userTable;
        private final UserTableModel tableModel;

        ReadyPanel() {
            setLayout(new BorderLayout(10, 10));
            setBorder(new EmptyBorder(10, 10, 10, 10));

            tableModel = new UserTableModel();
            userTable = new JTable(tableModel);

            JPanel top = new JPanel(new GridLayout(0, 1, 5, 5));
            top.add(new JLabel("Ready Check", SwingConstants.CENTER));

            // Room controls row
            JPanel roomPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
            roomPanel.add(new JLabel("Room:"));
            roomPanel.add(roomField);
            roomPanel.add(createRoomButton);
            roomPanel.add(joinRoomButton);
            top.add(roomPanel);

            // Config toggles row
            JPanel toggles = new JPanel(new FlowLayout(FlowLayout.CENTER));
            toggles.add(removeStrikeCheck);
            toggles.add(hardModeCheck);
            top.add(toggles);

            add(top, BorderLayout.NORTH);
            add(new JScrollPane(userTable), BorderLayout.CENTER);

            JPanel bottom = new JPanel(new FlowLayout(FlowLayout.CENTER));
            bottom.add(readyButton);
            add(bottom, BorderLayout.SOUTH);

            // ---- Button actions ----

            createRoomButton.addActionListener(e -> {
                String room = roomField.getText().trim();
                if (room.isEmpty()) {
                    JOptionPane.showMessageDialog(this,
                            "Please enter a room name.",
                            "Room name required",
                            JOptionPane.WARNING_MESSAGE);
                    return;
                }
                client.createRoomFromUI(room);
            });

            joinRoomButton.addActionListener(e -> {
                String room = roomField.getText().trim();
                if (room.isEmpty()) {
                    JOptionPane.showMessageDialog(this,
                            "Please enter a room name.",
                            "Room name required",
                            JOptionPane.WARNING_MESSAGE);
                    return;
                }
                client.joinRoomFromUI(room);
            });

            readyButton.addActionListener(e -> {
                removeStrikeOnCorrectGuess = removeStrikeCheck.isSelected();
                hardMode = hardModeCheck.isSelected();
                client.sendConfigFromUI(removeStrikeOnCorrectGuess, hardMode);
                client.toggleReadyFromUI();
            });
        }

        void resetReadyButton() {
            readyButton.setText("Mark Ready");
        }

        void updateUserList(List<User> users) {
            tableModel.setUsers(users);
        }
    }

    // ------------------------------------------------------------------------
    // Game Panel (using JSplitPane)
    // ------------------------------------------------------------------------
    private class GamePanel extends JPanel {
        private final UserTableModel userTableModel = new UserTableModel();
        private final JTable userTable = new JTable(userTableModel);

        private final JTextArea eventsArea = new JTextArea();
        private final JTextArea chatArea = new JTextArea();
        private final JTextField chatInput = new JTextField();

        private final HangmanPanel hangmanPanel = new HangmanPanel();
        private final JLabel wordLabel = new JLabel("_ _ _ _", SwingConstants.CENTER);
        private final JPanel lettersPanel = new JPanel(new GridLayout(3, 9, 3, 3));
        private final Map<Character, JButton> letterButtons = new HashMap<>();

        private final JTextField wordGuessField = new JTextField();
        private final JButton wordGuessButton = new JButton("Guess Word");

        private final JLabel timerLabel = new JLabel("Turn Timer: --", SwingConstants.CENTER);
        private Timer turnTimer;
        private int turnSecondsRemaining = 0;

        private final JCheckBox awayCheck = new JCheckBox("Away");

        private final JLabel currentTurnLabel = new JLabel("Current turn: -",
                SwingConstants.CENTER);

        GamePanel() {
            setLayout(new BorderLayout(10, 10));
            setBorder(new EmptyBorder(10, 10, 10, 10));

            // ---------- LEFT: Players + Away ----------
            JPanel left = new JPanel(new BorderLayout());
            left.add(new JLabel("Players", SwingConstants.CENTER), BorderLayout.NORTH);
            left.add(new JScrollPane(userTable), BorderLayout.CENTER);
            left.add(awayCheck, BorderLayout.SOUTH);
            awayCheck.addActionListener(e ->
                    client.setAwayFromUI(awayCheck.isSelected()));

            // ---------- CENTER: Hangman + word + letters + word guess + timer ----------
            JPanel center = new JPanel(new BorderLayout(5, 5));

            JPanel topCenter = new JPanel(new GridLayout(0, 1, 5, 5));
            topCenter.add(timerLabel);
            topCenter.add(currentTurnLabel);
            center.add(topCenter, BorderLayout.NORTH);

            JPanel hangmanWord = new JPanel(new BorderLayout());
            hangmanWord.add(hangmanPanel, BorderLayout.CENTER);

            wordLabel.setFont(wordLabel.getFont().deriveFont(Font.BOLD, 24f));
            hangmanWord.add(wordLabel, BorderLayout.SOUTH);

            center.add(hangmanWord, BorderLayout.CENTER);

            buildLettersPanel();
            center.add(lettersPanel, BorderLayout.SOUTH);

            // Full-word guess bar at bottom of center
            JPanel wordGuessPanel = new JPanel(new BorderLayout(5, 5));
            wordGuessPanel.add(new JLabel("Full word guess:"), BorderLayout.WEST);
            wordGuessPanel.add(wordGuessField, BorderLayout.CENTER);
            wordGuessPanel.add(wordGuessButton, BorderLayout.EAST);
            wordGuessButton.addActionListener(e -> {
                String guess = wordGuessField.getText().trim();
                if (!guess.isEmpty()) {
                    client.guessWordFromUI(guess);
                    wordGuessField.setText("");
                }
            });

            JPanel centerWrapper = new JPanel(new BorderLayout());
            centerWrapper.add(center, BorderLayout.CENTER);
            centerWrapper.add(wordGuessPanel, BorderLayout.SOUTH);

            // ---------- RIGHT TOP: Game Events ----------
            eventsArea.setEditable(false);
            JPanel eventsPanel = new JPanel(new BorderLayout());
            eventsPanel.add(new JLabel("Game Events", SwingConstants.CENTER),
                    BorderLayout.NORTH);
            eventsPanel.add(new JScrollPane(eventsArea), BorderLayout.CENTER);

            // ---------- RIGHT BOTTOM: Chat ----------
            chatArea.setEditable(false);
            JPanel chatPanel = new JPanel(new BorderLayout());
            chatPanel.add(new JLabel("Chat", SwingConstants.CENTER),
                    BorderLayout.NORTH);
            chatPanel.add(new JScrollPane(chatArea), BorderLayout.CENTER);
            chatPanel.add(chatInput, BorderLayout.SOUTH);

            chatInput.addActionListener(e -> {
                String message = chatInput.getText().trim();
                if (!message.isEmpty()) {
                    client.sendChatFromUI(message);
                    chatInput.setText("");
                }
            });

            // ---------- Split 3: Events <-> Chat (vertical) ----------
            JSplitPane eventsChatSplit = new JSplitPane(
                    JSplitPane.VERTICAL_SPLIT,
                    eventsPanel,
                    chatPanel
            );
            eventsChatSplit.setResizeWeight(0.5); // half events, half chat by default

            // ---------- Split 2: Game (center) <-> Right (events+chat) ----------
            JSplitPane gameRightSplit = new JSplitPane(
                    JSplitPane.HORIZONTAL_SPLIT,
                    centerWrapper,
                    eventsChatSplit
            );
            gameRightSplit.setResizeWeight(0.6); // 60% game, 40% right side

            // ---------- Split 1: Left (players) <-> Game+Right ----------
            JSplitPane mainSplit = new JSplitPane(
                    JSplitPane.HORIZONTAL_SPLIT,
                    left,
                    gameRightSplit
            );
            mainSplit.setResizeWeight(0.2); // 20% players, 80% rest

            add(mainSplit, BorderLayout.CENTER);
        }

        private void buildLettersPanel() {
            lettersPanel.removeAll();
            letterButtons.clear();
            for (char c = 'A'; c <= 'Z'; c++) {
                JButton btn = new JButton(String.valueOf(c));
                btn.addActionListener(e -> {
                    char letter = btn.getText().charAt(0);
                    client.guessLetterFromUI(letter);
                    if (!hardMode) {
                        btn.setEnabled(false);
                    }
                });
                letterButtons.put(c, btn);
                lettersPanel.add(btn);
            }
            lettersPanel.revalidate();
            lettersPanel.repaint();
        }

        void disableLetter(char c) {
            JButton btn = letterButtons.get(Character.toUpperCase(c));
            if (btn != null && !hardMode) {
                btn.setEnabled(false);
            }
        }

        void updateUserList(List<User> users, long currentTurnId) {
            userTableModel.setUsers(users);
            userTableModel.setCurrentTurnClientId(currentTurnId);
        }

        void resetForNewSession() {
            resetForNewRound();
            eventsArea.setText("");
            chatArea.setText("");
            awayCheck.setSelected(false);
        }

        void resetForNewRound() {
            hangmanPanel.setStrikes(0, 0);
            wordLabel.setText("_ _ _ _");
            buildLettersPanel();
            stopTurnTimer();
            timerLabel.setText("Turn Timer: --");
            currentTurnLabel.setText("Current turn: -");
        }

        void updateGameState(GameStatePayload gsp, boolean hardMode, boolean spectatorMode) {
            String hidden = (gsp.getHiddenWord() != null) ? gsp.getHiddenWord() : "";
            wordLabel.setText(hidden);

            hangmanPanel.setStrikes(gsp.getCurrentStrikes(), gsp.getMaxStrikes());

            // If strikes reset, treat as new round (letters re-enabled)
            if (gsp.getCurrentStrikes() == 0) {
                buildLettersPanel();
            }
        }

        void appendEvent(String text) {
            eventsArea.append(text + "\n");
            eventsArea.setCaretPosition(eventsArea.getDocument().getLength());
        }

        void setCurrentTurnLabel(String name) {
            currentTurnLabel.setText("Current turn: " + name);
            // start/restart turn countdown every time a new turn owner is set
            startTurnTimer(600); 
        }

        private void startTurnTimer(int seconds) {
            stopTurnTimer();
            turnSecondsRemaining = seconds;
            timerLabel.setText("Turn Timer: " + turnSecondsRemaining + "s");
            turnTimer = new Timer(1000, e -> {
                turnSecondsRemaining--;
                if (turnSecondsRemaining <= 0) {
                    timerLabel.setText("Turn Timer: 0s");
                    stopTurnTimer();
                } else {
                    timerLabel.setText("Turn Timer: " + turnSecondsRemaining + "s");
                }
            });
            turnTimer.start();
        }

        private void stopTurnTimer() {
            if (turnTimer != null) {
                turnTimer.stop();
                turnTimer = null;
            }
        }
    }
}

/**
 * Table model for user list used in both ReadyPanel and GamePanel.
 *
 * Columns:
 *  - Turn marker
 *  - Username
 *  - ID
 *  - Points
 *  - Status (Ready / Away / Spectator)
 */
class UserTableModel extends AbstractTableModel {
    private final String[] columns = {"Turn", "User", "ID", "Points", "Status"};
    private List<User> users = new ArrayList<>();
    private long currentTurnClientId = -1;

    public void setUsers(List<User> users) {
        this.users = (users != null) ? users : new ArrayList<>();
        fireTableDataChanged();
    }

    public void setCurrentTurnClientId(long id) {
        this.currentTurnClientId = id;
        fireTableDataChanged();
    }

    @Override
    public int getRowCount() {
        return users.size();
    }

    @Override
    public int getColumnCount() {
        return columns.length;
    }

    @Override
    public String getColumnName(int column) {
        return columns[column];
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        User u = users.get(rowIndex);
        switch (columnIndex) {
            case 0:
                return (u.getClientId() == currentTurnClientId) ? "►" : "";
            case 1:
                return u.getClientName();
            case 2:
                return u.getClientId();
            case 3:
                return u.getPoints();
            case 4:
                if (u.isSpectator()) return "Spectator";
                if (u.isAway()) return "Away";
                if (u.isReady()) return "Ready";
                return "";
            default:
                return "";
        }
    }
}

/**
 * Simple Hangman drawing.
 *
 * Uses currentStrikes/maxStrikes to decide how much of the stick figure to show.
 * This satisfies the requirement that the hangman be more than just a number.
 */
class HangmanPanel extends JPanel {
    private int currentStrikes = 0;
    private int maxStrikes = 0;

    HangmanPanel() {
        setPreferredSize(new Dimension(200, 200));
    }

    public void setStrikes(int current, int max) {
        this.currentStrikes = current;
        this.maxStrikes = max;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);

        int w = getWidth();
        int h = getHeight();

        // gallows
        g.drawLine(w / 4, h - 20, w * 3 / 4, h - 20); // base
        g.drawLine(w / 3, h - 20, w / 3, 20);         // pole
        g.drawLine(w / 3, 20, w * 2 / 3, 20);         // top
        g.drawLine(w * 2 / 3, 20, w * 2 / 3, 40);     // rope

        int segments = Math.max(maxStrikes, 6);
        double ratio = (segments == 0) ? 0 : (double) currentStrikes / segments;
        int partsToDraw = (int) Math.round(ratio * 6.0);

        int cx = w * 2 / 3;
        int cy = 40;

        // 1: head
        if (partsToDraw >= 1) {
            g.drawOval(cx - 10, cy, 20, 20);
        }
        // 2: body
        if (partsToDraw >= 2) {
            g.drawLine(cx, cy + 20, cx, cy + 60);
        }
        // 3: left arm
        if (partsToDraw >= 3) {
            g.drawLine(cx, cy + 30, cx - 15, cy + 45);
        }
        // 4: right arm
        if (partsToDraw >= 4) {
            g.drawLine(cx, cy + 30, cx + 15, cy + 45);
        }
        // 5: left leg
        if (partsToDraw >= 5) {
            g.drawLine(cx, cy + 60, cx - 15, cy + 80);
        }
        // 6: right leg
        if (partsToDraw >= 6) {
            g.drawLine(cx, cy + 60, cx + 15, cy + 80);
        }
    }
}
