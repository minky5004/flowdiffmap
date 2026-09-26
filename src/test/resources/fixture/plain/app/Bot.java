package app;

import java.util.TimerTask;

public class Bot extends TimerTask {

    private final Store store = new Store();

    @Override
    public void run() {
        save();
    }

    public String status() {
        return "ok";
    }

    void save() {
        store.save();
    }
}
