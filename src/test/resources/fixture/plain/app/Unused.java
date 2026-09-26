package app;

public class Unused {

    public void idle() {
        class Local {
            class Inner {
            }

            void x() {
            }
        }
        new Local().x();
    }
}
