package Projects.CommonFiles;

public class GameStatePayload extends Payload {
    private String hiddenWord;   // The word with blanks (e.g., "_ A B _ _")
    private String solutionWord; // The actual answer, for spectators only

    private int currentStrikes;
    private int maxStrikes;

    public GameStatePayload() {
        setPayloadType(PayloadType.GAME_STATE);
    }

    // -------------------------
    // Hidden word (normal players)
    // -------------------------
    public String getHiddenWord() {
        return hiddenWord;
    }

    public void setHiddenWord(String hiddenWord) {
        this.hiddenWord = hiddenWord;
    }

    // -------------------------
    // Full answer word
    // -------------------------
    public String getSolutionWord() {
        return solutionWord;
    }

    public void setSolutionWord(String solutionWord) {
        this.solutionWord = solutionWord;
    }

    // -------------------------
    // Strikes
    // -------------------------
    public int getCurrentStrikes() {
        return currentStrikes;
    }

    public void setCurrentStrikes(int currentStrikes) {
        this.currentStrikes = currentStrikes;
    }

    public int getMaxStrikes() {
        return maxStrikes;
    }

    public void setMaxStrikes(int maxStrikes) {
        this.maxStrikes = maxStrikes;
    }

    @Override
    public String toString() {
        return String.format(
            "GameStatePayload[type=%s, clientId=%d, hiddenWord=%s, solutionWord=%s, currentStrikes=%d, maxStrikes=%d]",
            getPayloadType(),
            getClientId(),
            hiddenWord,
            solutionWord,
            currentStrikes,
            maxStrikes
        );
    }
}
