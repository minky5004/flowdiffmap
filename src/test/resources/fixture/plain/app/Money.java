package app;

import java.io.Serializable;

public class Money implements Comparable<Money>, Serializable {

    @Override
    public int compareTo(Money other) {
        return 0;
    }

    @Override
    public String toString() {
        return "0";
    }
}
