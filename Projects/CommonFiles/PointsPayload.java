package Projects.CommonFiles;

public class PointsPayload extends Payload {
    private int points;

    public PointsPayload() {
        setPayloadType(PayloadType.POINTS);
    }

    public int getPoints() {
        return points;
    }

    public void setPoints(int points) {
        this.points = points;
    }

    @Override
    public String toString() {
        return super.toString() + String.format(" Points: [%d]", points);
    }
}