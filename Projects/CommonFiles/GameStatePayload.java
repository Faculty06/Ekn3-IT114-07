package Projects.CommonFiles;

public class GameStatePayload extends Payload {
    private String hiddenWord; // The word with blanks (e.g., "_ A B C _")
    private int currentStrikes;
    private int maxStrikes;

    public GameStatePayload() {
        setPayloadType(PayloadType.GAME_STATE);
    }

    public String getHiddenWord() {
        return hiddenWord;
    }

    public void setHiddenWord(String hiddenWord) {
        this.hiddenWord = hiddenWord;
    }

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
        "GameStatePayload[type=%s, clientId=%d, hiddenWord=%s, currentStrikes=%d, maxStrikes=%d]",
        getPayloadType(),getClientId(),hiddenWord, currentStrikes,maxStrikes);
    }
} 