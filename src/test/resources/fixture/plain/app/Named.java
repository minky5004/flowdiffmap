package app;

public record Named(String name) {

    @Override
    public String toString() {
        return name;
    }
}
