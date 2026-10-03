import javax.microedition.lcdui.Display;
import javax.microedition.midlet.MIDlet;

public class CelesteMIDlet extends MIDlet {
    private Game game;

    protected void startApp() {
        if (game == null) {
            game = new Game(this);
        }
        Display.getDisplay(this).setCurrent(game);
        game.start();
    }

    protected void pauseApp() {
        if (game != null) game.pause();
    }

    protected void destroyApp(boolean unconditional) {
        if (game != null) game.stop();
    }

    void exit() {
        destroyApp(true);
        notifyDestroyed();
    }
}
