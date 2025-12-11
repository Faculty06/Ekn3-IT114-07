package Projects.CommonFiles;

public class GameStatePayload extends Payload {
    private String hiddenWord;
    private int currentStrikes;
    private int maxStrikes;
    private boolean hardMode;
    private boolean removeStrikeOnCorrectGuess;
    
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
    
    public boolean isHardMode() {
        return hardMode;
    }
    
    public void setHardMode(boolean hardMode) {
        this.hardMode = hardMode;
    }
    
    public boolean isRemoveStrikeOnCorrectGuess() {
        return removeStrikeOnCorrectGuess;
    }
    
    public void setRemoveStrikeOnCorrectGuess(boolean removeStrikeOnCorrectGuess) {
        this.removeStrikeOnCorrectGuess = removeStrikeOnCorrectGuess;
    }
    
    @Override
    public String toString() {
        return String.format("GameState[word=%s, strikes=%d/%d, hardMode=%s, removeStrike=%s]", 
            hiddenWord, currentStrikes, maxStrikes, hardMode, removeStrikeOnCorrectGuess);
    }
}